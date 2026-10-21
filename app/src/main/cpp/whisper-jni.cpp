// Minimal JNI bridge over whisper.cpp for the app: init once with a model
// path, then transcribe 16 kHz Float32 PCM utterance buffers. Same shape as
// the desktop server's use — the spoken-at timestamps come from the app's
// VAD (client side), so the engine itself returns plain text.
#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "whisper-jni", __VA_ARGS__)

static struct whisper_context *g_ctx = nullptr;
static int g_threads = 4;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sinesislab_livenotes_stt_WhisperJni_init(JNIEnv *env, jobject, jstring jModelPath, jint threads)
{
    if (g_ctx)
    {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
    const char *path = env->GetStringUTFChars(jModelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU-only on phones; avoids surprise backends
    g_ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jModelPath, path);
    if (!g_ctx)
    {
        LOGI("model load failed");
        return JNI_FALSE;
    }
    g_threads = threads > 0 ? threads : 4;
    LOGI("model loaded, threads=%d", g_threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sinesislab_livenotes_stt_WhisperJni_free(JNIEnv *, jobject)
{
    if (g_ctx)
    {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sinesislab_livenotes_stt_WhisperJni_fullTranscribe(
    JNIEnv *env, jobject,
    jfloatArray jSamples, jint sampleRate, jstring jLanguage)
{
    if (!g_ctx)
        return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(jSamples);
    jfloat *samples = env->GetFloatArrayElements(jSamples, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_special = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_context = true;
    params.n_threads = g_threads;

    const char *lang = jLanguage ? env->GetStringUTFChars(jLanguage, nullptr) : nullptr;
    // "auto" is whisper.cpp's own language-detect spelling
    const std::string language = (lang && lang[0]) ? std::string(lang) : "auto";
    if (lang)
        env->ReleaseStringUTFChars(jLanguage, lang);
    params.language = language.c_str();

    // current whisper.cpp takes the params struct BY VALUE
    const int rc = whisper_full(g_ctx, params, samples, n);
    env->ReleaseFloatArrayElements(jSamples, samples, JNI_ABORT);
    if (rc != 0)
    {
        LOGI("whisper_full failed: %d", rc);
        return env->NewStringUTF("");
    }

    std::string out;
    const int nseg = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < nseg; ++i)
        out += whisper_full_get_segment_text(g_ctx, i);
    return env->NewStringUTF(out.c_str());
}