package com.sinesislab.livenotes.store

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App settings + the provider API key. Posture change from the desktop app
 * (PLAN.md, native-Android section): the desktop keeps keys server-side,
 * but here the PHONE is the client of record — the key lives on the device,
 * wrapped by an AndroidKeyStore AES/GCM key (hardware-backed where
 * available). Nothing is synced anywhere by default.
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("livenotes-settings", Context.MODE_PRIVATE)

    data class CloudConfig(
        val api: String, // "openai" (OpenAI-compatible) | "anthropic"
        val baseUrl: String,
        val model: String,
    )

    var cloud: CloudConfig
        get() = CloudConfig(
            api = prefs.getString("cloud.api", "openai") ?: "openai",
            baseUrl = prefs.getString("cloud.baseUrl", "https://api.openai.com/v1") ?: "",
            model = prefs.getString("cloud.model", "gpt-4o-mini") ?: "",
        )
        set(value) {
            prefs.edit()
                .putString("cloud.api", value.api)
                .putString("cloud.baseUrl", value.baseUrl)
                .putString("cloud.model", value.model)
                .apply()
        }

    /** The stored API key (plaintext only at use sites); null when unset. */
    var apiKey: String?
        get() = prefs.getString("cloud.key.wrapped", null)?.let { KeystoreCrypto.decrypt(it) }
        set(value) {
            if (value.isNullOrBlank()) {
                prefs.edit().remove("cloud.key.wrapped").apply()
            } else {
                prefs.edit().putString("cloud.key.wrapped", KeystoreCrypto.encrypt(value)).apply()
            }
        }

    /** True only when a stored key actually DECRYPTS — a blob left unreadable
     *  (Keystore reset after an uninstall) counts as "not stored", so the UI
     *  never claims a key it can't use. */
    val hasApiKey: Boolean
        get() = try {
            !decryptQuiet(prefs.getString("cloud.key.wrapped", null)).isNullOrBlank()
        } catch (e: Exception) {
            false
        }

    private fun decryptQuiet(wrapped: String?): String? = wrapped?.let { KeystoreCrypto.decrypt(it) }

    /** whisper.cpp model to load; downloaded into filesDir, so stored by path. */
    var whisperModelPath: String
        get() = prefs.getString("whisper.modelPath", "") ?: ""
        set(value) = prefs.edit().putString("whisper.modelPath", value).apply()

    var whisperLanguage: String
        get() = prefs.getString("whisper.language", "auto") ?: "auto"
        set(value) = prefs.edit().putString("whisper.language", value).apply()
}

/** AES/GCM at-rest wrap; the master key never leaves the Keystore. */
object KeystoreCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "livenotes-api-key"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12 // GCM's standard 96-bit IV — Android Keystore refuses anything else

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(wrapped: String): String? =
        try {
            val data = Base64.decode(wrapped, Base64.NO_WRAP)
            val spec = GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), spec)
            String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // visible in logcat (tag: keystore-fail): a blob that can't open is
            // indistinguishable from "no key stored" unless someone looks
            android.util.Log.e("keystore-fail", "decrypt failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
}