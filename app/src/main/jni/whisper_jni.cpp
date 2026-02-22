#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <android/log.h>
#include "whisper.h"
#include "ggml-backend.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Abort flag for cooperative cancellation of whisper_full() via ggml_abort_callback.
// Set by nativeAbort(), checked before each ggml computation, reset at transcription start.
static volatile int g_abort_flag = 0;

static bool whisper_abort_callback(void * /*user_data*/) {
    if (g_abort_flag != 0) {
        LOGI("abort_callback returning true — stopping whisper_full()");
        return true;
    }
    return false;
}

#ifndef NDEBUG
// Progress callback to trace encoder progress (called by whisper_full during encoding).
// Debug-only: each __android_log_print is a synchronous syscall.
static void voidterm_progress_cb(struct whisper_context * /*ctx*/, struct whisper_state * /*state*/,
                                  int progress, void * /*user_data*/) {
    LOGI("whisper progress: %d%%", progress);
}
#endif

// Data passed to whisper's new_segment_callback when streaming is enabled.
// Accumulates decoded text and forwards it to Java via JNI on each new segment.
struct StreamCallbackData {
    JNIEnv   *env;
    jobject   bridgeRef;    // global ref to WhisperBridge instance
    jmethodID onSegmentMethod;
    std::string accumulated;
    int lastSegment;        // tracks how many segments we've already read
};

static void streaming_segment_callback(struct whisper_context *ctx,
                                        struct whisper_state * /*state*/,
                                        int n_new, void *user_data) {
    auto *data = static_cast<StreamCallbackData *>(user_data);
    if (!data || !data->env || !data->bridgeRef) return;

    // Abort flag check — don't call back into Java if we're aborting
    if (g_abort_flag != 0) return;

    int total = whisper_full_n_segments(ctx);
    // Append only the new segments since last callback
    for (int i = total - n_new; i < total; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text) {
            data->accumulated += text;
        }
    }
    data->lastSegment = total;

    LOGI("streaming_segment_callback: n_new=%d, total=%d, accumulated=%zu chars",
         n_new, total, data->accumulated.size());

    // Forward accumulated text to Java: WhisperBridge.onNativeSegment(String)
    jstring jtext = data->env->NewStringUTF(data->accumulated.c_str());
    if (jtext) {
        data->env->CallVoidMethod(data->bridgeRef, data->onSegmentMethod, jtext);
        data->env->DeleteLocalRef(jtext);
    }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_voidterm_voice_WhisperBridge_nativeInit(JNIEnv *env, jobject /* this */, jstring modelPath,
                                                  jboolean useGpu) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) {
        LOGE("Failed to get model path string");
        return 0;
    }

    LOGI("Loading whisper model from: %s", path);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = useGpu;
    LOGI("use_gpu=%d", cparams.use_gpu);
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("Failed to initialize whisper context");
        return 0;
    }

    LOGI("Whisper model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_voidterm_voice_WhisperBridge_nativeTranscribe(JNIEnv *env, jobject thiz,
                                                       jlong contextPtr, jfloatArray audioData,
                                                       jstring language, jint nThreads,
                                                       jboolean translate, jstring initialPrompt,
                                                       jfloat temperature, jboolean useBeamSearch,
                                                       jint beamSize, jboolean suppressNonSpeech,
                                                       jboolean proportionalContext,
                                                       jboolean streaming) {
    if (contextPtr == 0) {
        LOGE("Whisper context is null");
        return nullptr;
    }

    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);

    jsize audioLength = env->GetArrayLength(audioData);
    if (audioLength == 0) {
        LOGE("Audio data is empty");
        return nullptr;
    }

    // When streaming, we must copy the array because GetPrimitiveArrayCritical
    // blocks the GC and forbids JNI callbacks (which streaming_segment_callback uses).
    // When not streaming, GetPrimitiveArrayCritical is safe and avoids the copy.
    jfloat *audio = nullptr;
    std::vector<float> audioCopy;

    if (streaming) {
        audioCopy.resize(audioLength);
        env->GetFloatArrayRegion(audioData, 0, audioLength, audioCopy.data());
        audio = audioCopy.data();
    } else {
        audio = (jfloat *)env->GetPrimitiveArrayCritical(audioData, nullptr);
        if (!audio) {
            LOGE("Failed to get audio array");
            return nullptr;
        }
    }

    const char *lang = env->GetStringUTFChars(language, nullptr);

    // Get initial prompt string (may be null)
    const char *prompt = nullptr;
    if (initialPrompt != nullptr) {
        prompt = env->GetStringUTFChars(initialPrompt, nullptr);
    }

    // Reset abort flag before each transcription
    g_abort_flag = 0;

    // Select sampling strategy based on user preference
    struct whisper_full_params params = whisper_full_default_params(
            useBeamSearch ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.no_context       = true;   // match official example
    params.offset_ms        = 0;
    params.n_threads = nThreads > 0 ? nThreads : 4;
    params.abort_callback = whisper_abort_callback;
    params.abort_callback_user_data = nullptr;
#ifndef NDEBUG
    params.progress_callback = voidterm_progress_cb;
    params.progress_callback_user_data = nullptr;
#endif
    params.translate = translate;
    params.temperature = temperature;
    params.temperature_inc = 0.0f;    // disable retry at higher temperatures — PTT audio is clear
    params.entropy_thold   = 0.0f;    // disable "failed decode" detection (no fallbacks anyway)
    params.logprob_thold   = 0.0f;
    params.suppress_non_speech_tokens = suppressNonSpeech;

    // Streaming: timestamps MUST be enabled (no_timestamps = false) so whisper creates
    // segment boundaries via timestamp tokens. Without timestamps, the decoder produces
    // a single segment and the new_segment_callback fires only once at the end.
    // Timestamp tokens don't appear in whisper_full_get_segment_text() output.
    // Non-streaming: no_timestamps = true, single_segment = true (one segment, fastest path).
    params.no_timestamps = streaming ? false : true;
    params.single_segment = streaming ? false : true;

    if (useBeamSearch) {
        params.beam_search.beam_size = beamSize > 0 ? beamSize : 5;
    }

    if (prompt && prompt[0] != '\0') {
        params.initial_prompt = prompt;
    }

    if (lang) {
        // whisper.cpp natively accepts "auto" to auto-detect language then transcribe.
        // Do NOT use params.detect_language — that flag detects and returns without transcribing.
        params.language = lang;
    }

    // Proportional audio context: only encode mel frames covering actual audio length
    // instead of the full 30s (1500 frames). Dramatically reduces encoder time for short PTT.
    if (proportionalContext) {
        // 320 = WHISPER_HOP_LENGTH (160) * encoder stride (2)
        // +16 frames padding for safety
        int needed = (int)ceil((double)audioLength / 320.0) + 16;
        params.audio_ctx = (needed < 1500) ? needed : 1500;
    }

    // Set up streaming callback if enabled
    StreamCallbackData *streamData = nullptr;
    if (streaming) {
        jclass bridgeClass = env->GetObjectClass(thiz);
        jmethodID onSegmentMethod = env->GetMethodID(bridgeClass, "onNativeSegment",
                                                      "(Ljava/lang/String;)V");
        if (onSegmentMethod) {
            streamData = new StreamCallbackData();
            streamData->env = env;
            streamData->bridgeRef = env->NewGlobalRef(thiz);
            streamData->onSegmentMethod = onSegmentMethod;
            streamData->lastSegment = 0;

            params.new_segment_callback = streaming_segment_callback;
            params.new_segment_callback_user_data = streamData;
        } else {
            LOGE("Failed to find onNativeSegment method — streaming disabled");
        }
    }

    LOGI(">>> whisper_full() ENTER: %d samples (%.1fs), lang=%s, n_threads=%d, translate=%d, beam=%d, temp=%.2f, audio_ctx=%d, streaming=%d",
         audioLength, audioLength / 16000.0f, lang ? lang : "auto", params.n_threads,
         (int)translate, (int)useBeamSearch, temperature, params.audio_ctx, (int)streaming);

    whisper_reset_timings(ctx);  // match official example

    int result = whisper_full(ctx, params, audio, audioLength);

    LOGI("<<< whisper_full() EXIT: result=%d", result);

    // Clean up streaming resources
    if (streamData) {
        env->DeleteGlobalRef(streamData->bridgeRef);
        delete streamData;
        streamData = nullptr;
    }

    if (!streaming) {
        env->ReleasePrimitiveArrayCritical(audioData, audio, JNI_ABORT);
    }
    if (lang) env->ReleaseStringUTFChars(language, lang);
    if (prompt) env->ReleaseStringUTFChars(initialPrompt, prompt);

    if (result != 0) {
        LOGE("Whisper transcription failed with code: %d", result);
        return nullptr;
    }

#ifndef NDEBUG
    // Print encoder/decoder/sampling timings to logcat (debug only — each log is a syscall)
    whisper_print_timings(ctx);
#endif

    int numSegments = whisper_full_n_segments(ctx);
    std::string transcription;
    transcription.reserve(256);

    for (int i = 0; i < numSegments; i++) {
        const char *segmentText = whisper_full_get_segment_text(ctx, i);
        if (segmentText) {
            transcription += segmentText;
        }
    }

    LOGI("Transcription complete: %d segments, %zu chars", numSegments, transcription.size());
    return env->NewStringUTF(transcription.c_str());
}

JNIEXPORT void JNICALL
Java_com_voidterm_voice_WhisperBridge_nativeFree(JNIEnv *env, jobject /* this */, jlong contextPtr) {
    if (contextPtr == 0) {
        LOGI("Context already null, nothing to free");
        return;
    }

    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);
    whisper_free(ctx);
    LOGI("Whisper context freed");
}

JNIEXPORT jboolean JNICALL
Java_com_voidterm_voice_WhisperBridge_nativeIsLoaded(JNIEnv *env, jobject /* this */, jlong contextPtr) {
    return contextPtr != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_voidterm_voice_WhisperBridge_nativeAbort(JNIEnv * /*env*/, jobject /* this */) {
    g_abort_flag = 1;
    LOGI("Abort flag set — whisper_full() will stop at next ggml computation");
}

JNIEXPORT jstring JNICALL
Java_com_voidterm_voice_WhisperBridge_nativeGetSystemInfo(JNIEnv *env, jobject /* this */) {
    std::string info;

    // whisper.cpp build info (NEON, FP16, Vulkan flags, etc.)
    info += "system_info: ";
    info += whisper_print_system_info();
    info += "\n";

    // Registered ggml backends (CPU, Vulkan, etc.)
    size_t n = ggml_backend_reg_count();
    info += "backends (" + std::to_string(n) + "):";
    for (size_t i = 0; i < n; i++) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        info += " ";
        info += ggml_backend_reg_name(reg);
    }

    LOGI("System info: %s", info.c_str());
    return env->NewStringUTF(info.c_str());
}

} // extern "C"
