package com.sinesislab.livenotes.stt

import android.content.Context
import com.sinesislab.livenotes.store.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * On-device whisper: one native context, used through a single-thread
 * executor (a longer utterance queues behind the previous one — the same
 * rule as the desktop's transcription queue).
 *
 * The model file downloads on first use (ggml-base.en-q5_1 ≈ 57 MB from the
 * mirror in settings; any .bin URL works). Load state is visible in
 * [state] and drives the status chip.
 */
class WhisperEngine(context: Context) {
    private val settings = AppSettings(context)
    private val filesDir: File = context.filesDir
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executor = Executors.newSingleThreadExecutor()
    private val cpuThreads = maxOf(4, Runtime.getRuntime().availableProcessors() - 2)

    enum class LoadState { IDLE, DOWNLOADING, LOADING, READY, ERROR }

    data class EngineState(
        val state: LoadState = LoadState.IDLE,
        val progress: Int = 0, // 0..100 while DOWNLOADING
        val detail: String? = null,
    )

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    /** Download (if needed) then load. Safe to call repeatedly. */
    fun ensureLoaded() {
        when (_state.value.state) {
            LoadState.READY, LoadState.DOWNLOADING, LoadState.LOADING -> return
            else -> {}
        }
        val path = settings.whisperModelPath
        if (path.isBlank()) {
            _state.value = EngineState(LoadState.ERROR, detail = "No whisper model yet — settings → download one.")
            return
        }
        _state.value = EngineState(LoadState.LOADING)
        executor.submit<Boolean> {
            val ok = WhisperJni.init(path, cpuThreads)
            _state.value = EngineState(if (ok) LoadState.READY else LoadState.ERROR, detail = if (ok) null else "Model failed to load")
            ok
        }
    }

    /**
     * Transcribe one 16 kHz utterance. BLOCKING (runs on this call's thread) —
     * all callers are background threads (the capture pool); this must never
     * be called from the main thread.
     */
    fun transcribe(samples: FloatArray): String {
        if (_state.value.state != LoadState.READY) return ""
        val native = executor.submit<String> { WhisperJni.fullTranscribe(samples, SAMPLE_RATE, settings.whisperLanguage) }
        return try {
            native.get().replace(Regex("\\s+"), " ").trim()
        } catch (e: java.util.concurrent.ExecutionException) {
            ""
        }
    }

    /** Stream-download a model file into filesDir/models, then load it. */
    fun downloadModel(fileName: String, url: String, onDone: (File?, String?) -> Unit) {
        val dest = File(File(filesDir, "models"), fileName)
        if (dest.exists() && dest.length() > 1024 * 1024) {
            onDone(dest, null)
            return
        }
        _state.value = EngineState(LoadState.DOWNLOADING, progress = 0)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
                    client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { res ->
                        if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")
                        val body = res.body ?: throw java.io.IOException("empty body")
                        val total = body.contentLength()
                        dest.parentFile?.mkdirs()
                        val tmp = File(dest.path + ".part")
                        body.byteStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var read = 0L
                                while (true) {
                                    val r = input.read(buf)
                                    if (r < 0) break
                                    out.write(buf, 0, r)
                                    read += r
                                    if (total > 0) {
                                        val p = (read * 100 / total).toInt()
                                        if (p != _state.value.progress) _state.value = EngineState(LoadState.DOWNLOADING, progress = p)
                                    }
                                }
                            }
                        }
                    }
                }
                val tmp = File(dest.path + ".part")
                if (tmp.exists() && !tmp.renameTo(dest)) throw java.io.IOException("rename failed")
                settings.whisperModelPath = dest.path
                _state.value = EngineState(LoadState.LOADING)
                val ok = executor.submit<Boolean> { WhisperJni.init(dest.path, cpuThreads) }.get()
                _state.value = EngineState(if (ok) LoadState.READY else LoadState.ERROR, detail = if (ok) null else "Model failed to load")
                withContext(Dispatchers.Main) { onDone(dest, if (ok) null else "Model failed to load") }
            } catch (e: Exception) {
                _state.value = EngineState(LoadState.ERROR, detail = e.message)
                withContext(Dispatchers.Main) { onDone(null, e.message) }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}