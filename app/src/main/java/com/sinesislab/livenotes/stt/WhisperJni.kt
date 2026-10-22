package com.sinesislab.livenotes.stt

/** Native declarations; the C++ side is app/src/main/cpp/whisper-jni.cpp. */
object WhisperJni {
    init {
        System.loadLibrary("whisper-jni")
    }

    external fun init(modelPath: String, threads: Int): Boolean
    external fun free()
    external fun fullTranscribe(samples: FloatArray, sampleRate: Int, language: String, prompt: String): String
}