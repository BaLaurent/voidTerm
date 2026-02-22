#include <jni.h>
#include <string>
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

// Progress callback to trace encoder progress (called by whisper_full during encoding).
static void voidterm_progress_cb(struct whisper_context * /*ctx*/, struct whisper_state * /*state*/,
                                  int progress, void * /*user_data*/) {
    LOGI("whisper progress: %d%%", progress);
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
Java_com_voidterm_voice_WhisperBridge_nativeTranscribe(JNIEnv *env, jobject /* this */,
                                                       jlong contextPtr, jfloatArray audioData,
                                                       jstring language, jint nThreads) {
    if (contextPtr == 0) {
        LOGE("Whisper context is null");
        return nullptr;
    }

    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);

    jfloat *audio = env->GetFloatArrayElements(audioData, nullptr);
    jsize audioLength = env->GetArrayLength(audioData);

    if (!audio || audioLength == 0) {
        LOGE("Audio data is null or empty");
        if (audio) env->ReleaseFloatArrayElements(audioData, audio, JNI_ABORT);
        return nullptr;
    }

    const char *lang = env->GetStringUTFChars(language, nullptr);

    // Reset abort flag before each transcription
    g_abort_flag = 0;

    // Match official whisper.android example params as closely as possible
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.no_timestamps    = true;
    params.single_segment   = false;
    params.no_context       = true;   // match official example
    params.offset_ms        = 0;
    params.n_threads = nThreads > 0 ? nThreads : 4;
    params.abort_callback = whisper_abort_callback;
    params.abort_callback_user_data = nullptr;
    params.progress_callback = voidterm_progress_cb;
    params.progress_callback_user_data = nullptr;
    if (lang) {
        params.language = lang;
    }

    LOGI(">>> whisper_full() ENTER: %d samples (%.1fs), lang=%s, n_threads=%d",
         audioLength, audioLength / 16000.0f, lang ? lang : "auto", params.n_threads);

    whisper_reset_timings(ctx);  // match official example

    int result = whisper_full(ctx, params, audio, audioLength);

    LOGI("<<< whisper_full() EXIT: result=%d", result);

    env->ReleaseFloatArrayElements(audioData, audio, JNI_ABORT);
    if (lang) env->ReleaseStringUTFChars(language, lang);

    if (result != 0) {
        LOGE("Whisper transcription failed with code: %d", result);
        return nullptr;
    }

    // Print encoder/decoder/sampling timings to logcat (same as official example)
    whisper_print_timings(ctx);

    int numSegments = whisper_full_n_segments(ctx);
    std::string transcription;

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
