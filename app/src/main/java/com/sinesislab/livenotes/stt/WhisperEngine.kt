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
 * On-device whisper: one native context, single-threaded use (the engine is
 * loaded once per model file; transcriptions run on a private executor so a
 * long utterance never blocks the UI or the capture loop).
 *
 * The model file is downloaded on first use (ggml-base.en-q5_1 ≈ 57 MB via a
 * /models URL you paste in settings — the same mirrors the desktop setup
 * uses). Load state is reported [load state][loadState] for the UI.
 */
class WhisperEngine(context: Context) {
    private val settings = AppSettings(context)
    private val filesDir: File = context.filesDir
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executor = Executors.newSingleThreadExecutor()
    private var loadedPath: String = ""

    enum class LoadState { IDLE, DOWNLOADING, LOADING, READY, ERROR }

    private data class EngineState(
        val state: LoadState = LoadState.IDLE,
        val progress: Int = 0, // 0..100 while DOWNLOADING
        val detail: String? = null,
    )

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    /** Download the model if needed, then init the native context. */
    fun ensureLoaded() {
        scope.launch {
            val path = settings.whisperModelPath
            if (path.isBlank()) {
                _state.value = EngineState(LoadState.ERROR, detail = "No whisper model yet — open settings and download one.")
                return@launch
            }
            _state.value = EngineState(LoadState.LOADING)
            onExecutor {
                val ok = WhisperJni.init(path, threads = maxOf(4, Runtime.getRuntime().availableProcessors() - 2))
                loadedPath = path
                _state.value = EngineState(if (ok) LoadState.READY else LoadState.ERROR, detail = if (ok) null else "Model failed to load")
            }
        }
    }

    /**
     * Transcribe one utterance (16 kHz mono PCM); returns cleaned single-line
     * text — the transcript never wants a stray newline.
     */
    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.Default) {
        executor.submit<String> {
            if (_state.value.state != LoadState.READY) return@submit ""
            WhisperJni.fullTranscribe(samples, SAMPLE_RATE, settings.whisperLanguage)
                .replace(Regex("\\s+"), " ")
                .trim()
        }.get()
    }

    /** Stream-download a model file into filesDir/models with progress. */
    fun downloadModel(fileName: String, url: String, onDone: (File?, String?) -> Unit) {
        _state.value = EngineState(LoadState.DOWNLOADING, progress = 0)
        scope.launch {
            try {
                val dest = File(File(filesDir, "models"), fileName)
                dest.parentFile?.mkdirs()
                val client = okhttp3.OkHttpClient.Builder().build()
                val req = okhttp3.Request.Builder().url(url).build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")
                    val body = res.body ?: throw java.io.IOException("empty body")
                    val total = body.contentLength()
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
                    tmp.renameTo(dest)
                    settings.whisperModelPath = dest.path
                    _state.value = EngineState(LoadState.LOADING)
                    onExecutor {
                        val ok = WhisperJni.init(dest.path, maxOf(4, Runtime.getRuntime().availableProcessors() - 2))
                        loadedPath = dest.path
                        _state.value = EngineState(if (ok) LoadState.READY else LoadState.ERROR, detail = if (ok) null else "Model failed to load")
                        withContext(Dispatchers.Main) { onDone(if (ok) dest else null, if (ok) null else "Model failed to load") }
                    }
                }
            } catch (e: Exception) {
                _state.value = EngineState(LoadState.ERROR, detail = e.message)
                onDone(null, e.message)
            }
        }
    }

    private fun onExecutor(block: () -> Unit) = executor.execute(block)

    companion object {
        const val SAMPLE_RATE = 16000
    }
}