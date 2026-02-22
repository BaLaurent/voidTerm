#include <jni.h>
#include <string>
#include <android/log.h>
#include "include/whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Thread count for inference — capped at 4 to leave cores for audio/UI/VR compositor.
// Quest 2 XR2 has 8 cores (4 perf + 4 efficiency); using half avoids starving the system.
static const int WHISPER_THREAD_COUNT = 4;

// Abort flag for cooperative cancellation of whisper_full() via ggml_abort_callback.
// Set by nativeAbort(), checked before each ggml computation, reset at transcription start.
static volatile int g_abort_flag = 0;

static bool whisper_abort_callback(void * /*user_data*/) {
    return g_abort_flag != 0;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_voidterm_voice_WhisperBridge_nativeInit(JNIEnv *env, jobject /* this */, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) {
        LOGE("Failed to get model path string");
        return 0;
    }

    LOGI("Loading whisper model from: %s", path);
    struct whisper_context_params cparams = whisper_context_default_params();
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
                                                       jstring language) {
    if (contextPtr == 0) {
        LOGE("Whisper context is null");
        return env->NewStringUTF("");
    }

    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);

    jfloat *audio = env->GetFloatArrayElements(audioData, nullptr);
    jsize audioLength = env->GetArrayLength(audioData);

    if (!audio || audioLength == 0) {
        LOGE("Audio data is null or empty");
        if (audio) env->ReleaseFloatArrayElements(audioData, audio, JNI_ABORT);
        return env->NewStringUTF("");
    }

    const char *lang = env->GetStringUTFChars(language, nullptr);

    // Reset abort flag before each transcription
    g_abort_flag = 0;

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.no_timestamps = true;
    params.single_segment = false;
    params.n_threads = WHISPER_THREAD_COUNT;
    params.abort_callback = whisper_abort_callback;
    params.abort_callback_user_data = nullptr;
    if (lang) {
        params.language = lang;
    }

    LOGI("Starting transcription: %d samples, lang=%s", audioLength, lang ? lang : "auto");

    int result = whisper_full(ctx, params, audio, audioLength);

    env->ReleaseFloatArrayElements(audioData, audio, JNI_ABORT);
    if (lang) env->ReleaseStringUTFChars(language, lang);

    if (result != 0) {
        LOGE("Whisper transcription failed with code: %d", result);
        return env->NewStringUTF("");
    }

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

} // extern "C"
