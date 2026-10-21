package com.sinesislab.livenotes.stt

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Cloud speech-to-text: the app records with the phone's mic, sends each
 * captured segment to an /audio/transcriptions-compatible provider, and
 * gets text back in ~0.5–1 s — the only way a phone keeps up in real time.
 * Local whisper remains the fallback and the fully-offline mode. API keys
 * are write-only and never live in this module: the caller supplies one per
 * call (the same key the notes AI uses — one key, two uses).
 */
object CloudStt {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS) // a dead provider must never queue itself ahead of later utterances
        .build()

    private fun writeShortLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    /** 16 kHz float samples → single-channel 16-bit PCM WAV bytes
     *  (a 44-byte RIFF header + little-endian samples). */
    fun encodeWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + samples.size * 2)
        out.write("RIFF".toByteArray())
        writeIntLE(out, 36 + 2 * samples.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(byteArrayOf(16, 0, 0, 0)) // fmt chunk size = 16
        out.write(byteArrayOf(1, 0)) // audio format = PCM
        out.write(byteArrayOf(1, 0)) // one channel
        writeIntLE(out, sampleRate)
        writeIntLE(out, 2 * sampleRate) // byte rate
        out.write(byteArrayOf(2, 0)) // block align
        out.write(byteArrayOf(16, 0)) // bits per sample
        out.write("data".toByteArray())
        writeIntLE(out, 2 * samples.size)
        for (sample in samples) {
            val clamped = when {
                sample < -1f -> -1f
                sample > 1f -> 1f
                else -> sample
            }
            val raw = (if (clamped < 0f) clamped * 32768f else clamped * 32767f).toInt()
            writeShortLE(out, raw)
        }
        return out.toByteArray()
    }

    /**
     * One utterance → text over the /audio/transcriptions shape (OpenAI's
     * endpoint; any compatible host takes the same POST). BLOCKING — the
     * capture pool threads are the only callers. Callers treat a throw as
     * "miss": the ladder falls to on-device whisper, so the client's
     * timeouts stay short and the fallback stays quiet.
     */
    fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
        baseUrl: String,
        apiKey: String?,
        model: String = "whisper-1",
        language: String = "auto",
    ): String {
        val base = baseUrl.trim().trimEnd('/')
        val url = if (base.endsWith("/audio/transcriptions")) base else "$base/audio/transcriptions"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", encodeWav(samples, sampleRate).toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .apply { if (language.isNotBlank() && language != "auto") addFormDataPart("language", language) }
            .build()
        val req = Request.Builder()
            .url(url)
            .post(body)
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                val err = try { res.body?.string()?.take(300) } catch (e: Exception) { "" }
                throw java.io.IOException("STT failed: HTTP ${res.code} $err")
            }
            val text = JSONObject(res.body?.string() ?: "").optString("text")
            if (text.isBlank()) throw java.io.IOException("STT returned no text")
            return text.trim()
        }
    }
}