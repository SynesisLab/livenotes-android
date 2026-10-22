package com.sinesislab.livenotes.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sinesislab.livenotes.MainActivity
import com.sinesislab.livenotes.NotesApp
import com.sinesislab.livenotes.R
import com.sinesislab.livenotes.ai.CloudAi
import com.sinesislab.livenotes.ai.Prompts
import com.sinesislab.livenotes.stt.CloudStt
import com.sinesislab.livenotes.store.TsLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The app's core loop — a foreground service so capture + transcription
 * survive the screen turning off (the advantage the PWA never had). One
 * service owns: AudioRecord 16 kHz capture → RMS VAD → realtime-first STT
 * (cloud upload when switched on, on-device whisper otherwise/fallback) →
 * transcript lines → cloud AI live notes every ~20 s, with the raw audio
 * also streaming to recording.wav the whole time. Everything persists
 * under filesDir/data (SessionStore) in the desktop's layout, so a future
 * sync path can hand sessions over untouched.
 */
class CaptureService : Service() {
    private lateinit var app: NotesApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transcribePool = Executors.newSingleThreadExecutor()
    private var recordThread: Thread? = null
    private var recording: RecordingWriter? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    private var sessionStartMs = 0L
    private var slug = ""
    private var notesJob: Job? = null
    private val newMaterial = StringBuilder()
    @Volatile private var newLinesCount = 0
    @Volatile private var notesInFlight = false

    // the notification's controls: plain pending-service intents, always
    // pointing back at this (still-live) service
    private val pausePi by lazy {
        PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_TOGGLE_PAUSE), PendingIntent.FLAG_IMMUTABLE)
    }
    private val endPi by lazy {
        PendingIntent.getService(this, 2, Intent(this, CaptureService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE)
    }
    private val appPi by lazy {
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as NotesApp
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // the notification's own buttons re-enter here — they aren't new sessions
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> {
                if (running) togglePause() else stopSelf()
                return START_STICKY
            }
            ACTION_END -> {
                if (running) finishAndStop() else stopSelf()
                return START_STICKY
            }
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            CaptureBus.errors.tryEmit("Microphone permission missing.")
            stopSelf()
            return START_NOT_STICKY
        }
        val target = intent?.getStringExtra(EXTRA_SLUG)
        val meta = target?.let { app.container.sessions.get(it) }
        if (meta == null) {
            CaptureBus.errors.tryEmit("Unknown session — start a new one.")
            stopSelf()
            return START_NOT_STICKY
        }
        slug = target
        activeSlug = target
        // a new session clears whatever wind-down flag the last one left visible
        CaptureBus.wrapUp.value = null
        app.container.whisper.ensureLoaded()
        startForeground(NOTIFICATION_ID, buildLiveNotification())
        startCapture()
        if (notesJob == null) notesJob = scope.launch { notesTicker() }
        return START_STICKY
    }

    /// Capture loop: whisper runs on the transcribe pool, so the read loop
    /// never waits behind a long transcription (multiple utterances queue up
    /// and are fed to the engine one at a time, in order).
    /// The transcript is the LIVE path: each finished utterance appends to an
    /// in-memory list and emits immediately — no full-file re-read per line.
    /// The AI notes are the DECOUPLED path: the ticker only acts once a
    /// couple of lines have piled up, so the model's slowness can never hold
    /// back speech-to-text.
    /// And the recording is the LOSSLESS path: every 80 ms frame streams to
    /// recording.wav right here on the record loop — whisper and the cloud
    /// never gate the on-disk audio.
    private val lines = mutableListOf<TsLine>()

    private fun startCapture() {
        if (running) return
        // a STICKY restart re-seeds the live list from the stored transcript
        lines.clear()
        lines.addAll(app.container.sessions.transcriptLines(slug))
        val vad = Vad { samples, spokenAt ->
            transcribePool.execute {
                val t0 = System.currentTimeMillis()
                val settings = app.container.settings
                // REALTIME-FIRST LADDER: cloud STT when switched on (a ~1 s
                // round trip per utterance); any miss — no network, bad key,
                // wrong host — falls through to on-device whisper, which also
                // waits out a mid-session model load
                var src = "whisper"
                var text = ""
                // PER-SESSION language: the session's own choice wins; blank
                // means the device default
                val sessionLang = app.container.sessions.get(slug)?.language.orEmpty()
                val language = sessionLang.ifBlank { settings.whisperLanguage }
                if (settings.cloudSttEnabled && settings.cloud.api == "openai") {
                    text = try {
                        CloudStt.transcribe(
                            samples, RATE,
                            baseUrl = settings.cloud.baseUrl,
                            apiKey = settings.apiKey,
                            model = settings.cloudSttModel,
                            language = language,
                        )
                    } catch (e: Exception) {
                        android.util.Log.d("livenotes", "cloud stt miss: ${e.message?.take(200)}")
                        ""
                    }
                    if (text.isNotEmpty()) src = "cloud"
                }
                if (text.isEmpty()) text = app.container.whisper.transcribe(samples, language)
                // instrument the live path: if speech stops becoming text, the
                // log answers WHERE — no utterance at all, empty inference, a
                // slow one, or which rung answered
                android.util.Log.d(
                    "livenotes",
                    "utterance ${"%.1f".format(samples.size / 16_000f)}s spoken@${spokenAt} src=$src inf=${System.currentTimeMillis() - t0}ms -> ${if (text.isEmpty()) "(empty)" else text.take(60)}",
                )
                if (text.isNotEmpty()) {
                    val line = app.container.sessions.appendLine(slug, text, spokenAt)
                    synchronized(lines) { lines.add(line) }
                    CaptureBus.transcript.tryEmit(synchronized(lines) { lines.toList() })
                    postNotification() // the live notification shows transcript life too
                    synchronized(newMaterial) {
                        newMaterial.append('\n').append(text)
                        newLinesCount++
                    }
                }
            }
        }
        val minBuf = AudioRecord.getMinBufferSize(RATE, CHANNEL_IN, ENCODING)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            RATE,
            CHANNEL_IN,
            ENCODING,
            maxOf(minBuf, RATE * 2 * 16), // ≥ 16 s of byte headroom
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            CaptureBus.errors.tryEmit("Could not open the microphone.")
            stopSelf()
            return
        }
        running = true
        // record the voices: everything the VAD hears also streams to
        // recording.wav. A failed open or a dead file never stops the STT
        // paths — this session just transcribes without a replayable audio.
        val writer = RecordingWriter(app.container.sessions.recordingFile(slug), RATE)
        if (writer.open()) {
            recording = writer
            sessionStartMs = System.currentTimeMillis()
            CaptureBus.recording.value = RecordingLive(0, paused = false)
        } else {
            CaptureBus.errors.tryEmit("Audio recording failed to open — transcribing without one.")
        }
        record.startRecording()
        val loop = Runnable {
            val buf = ShortArray(FRAME_SAMPLES)
            while (running) {
                val n = record.read(buf, 0, FRAME_SAMPLES)
                if (n <= 0) continue
                // paused: keep draining the hardware, write and interpret nothing
                if (paused) continue
                recording?.append(buf, n)
                val f32 = FloatArray(n)
                for (i in 0 until n) f32[i] = buf[i] / 32768f
                // wall clock: the VAD stamps spoken-at epochs, which become the
                // transcript's HH:MM — same contract as the desktop client
                vad.feed(f32, System.currentTimeMillis())
            }
            vad.flush(System.currentTimeMillis())
            record.stop()
            record.release()
            // normal exit patches-and-closes here; a teardown that lands
            // while the loop is still running is covered again in onDestroy
            recording?.finish()
        }
        recordThread = Thread(loop, "livenotes-capture").also { it.start() }
        // 1 Hz state keeper: the ⏺ clock reads the RECORDING's own length —
        // 16 kHz × 2 bytes = 32 bytes of PCM per millisecond of audio, so
        // paused time never ticks — and the notification re-posts quietly
        // every 15 s for the background case
        scope.launch {
            var tick = 0
            while (running) {
                delay(1000)
                tick++
                val ms = (recording?.writtenBytes() ?: 0L) / 32
                CaptureBus.recording.value = RecordingLive(ms, paused)
                if (tick % 15 == 0) postNotification()
            }
        }
    }

    private suspend fun notesTicker() {
        while (true) {
            delay(TICK_MS)
            tick(force = false)
        }
    }

    private suspend fun tick(force: Boolean) {
        if (notesInFlight) return
        if (!CaptureBus.notesEnabled.value) return // transcript-only mode: no AI work at all
        synchronized(newMaterial) {
            if (newMaterial.isEmpty()) return
            if (!force) {
                // DECOUPLED from the transcript on purpose: the AI compile
                // starts only once a COUPLE OF LINES have piled up — the
                // model's catch-up time never blocks speech→text
                if (newLinesCount < 2 && newMaterial.length < 600) return
            }
        }
        val sessions = app.container.sessions
        val existing = sessions.loadAutonotes(slug)
        val material: String
        synchronized(newMaterial) {
            material = newMaterial.toString()
            newMaterial.setLength(0)
        }
        notesInFlight = true
        try {
            val acc = StringBuilder()
            val cloud = app.container.settings.cloud
            // the session's topic = the AI's session context (topic, not title)
            val topic = sessions.get(slug)?.topic
            CloudAi.chatStream(
                model = cloud.model,
                systemPrompt = Prompts.autoNotesSystem(existing, topic),
                userPrompt = Prompts.autoNotesUser(existing, material.takeLast(MAX_NEW_MATERIAL)),
                api = cloud.api,
                baseUrl = cloud.baseUrl,
                apiKey = app.container.settings.apiKey,
                onToken = { tok ->
                    acc.append(tok)
                    // stream into the panel like the desktop's 300 ms throttle,
                    // coarser here: per token is fine (one update per token)
                    CaptureBus.notesStreaming.tryEmit(buildNotes(existing, acc.toString()))
                },
            )
            val delta = acc.toString().trim()
            if (delta.isNotEmpty()) {
                sessions.saveAutonotes(slug, buildNotes(existing, delta))
                CaptureBus.notes.tryEmit(AutoNotesState(sessions.loadAutonotes(slug), System.currentTimeMillis(), busy = false))
            }
        } catch (e: Exception) {
            CaptureBus.errors.tryEmit("AI notes failed: ${e.message}")
        } finally {
            notesInFlight = false
        }
    }

    private fun buildNotes(existing: String, delta: String): String {
        val trimmed = delta.trim()
        return if (existing.isBlank()) trimmed else "$existing\n\n$trimmed"
    }

    override fun onDestroy() {
        running = false
        try { recordThread?.join(1000) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        // the loop's own finish covers the normal stop; this catch-all lands
        // the header when the join timed out or the loop died — patchHeader
        // re-derives lengths from the file, so a second call is free
        try { recording?.finish() } catch (e: Exception) {}
        recording = null
        paused = false
        activeSlug = null
        CaptureBus.recording.value = null
        // FIRE-AND-FORGET — the earlier teardown blocked on .get() here, and
        // Service.onDestroy runs on the MAIN thread: if the pool was
        // mid-inference or mid-AI-compile, the process froze solid until
        // that call finished — the system timed out trying to collect its
        // backtrace (ANR), and from the user's side the app stopped
        // producing a transcript entirely. Nothing that expensive belongs
        // on the main thread.
        try {
            transcribePool.submit {
                // teardown note-compile honors the transcript-only toggle too
                if (!CaptureBus.notesEnabled.value) return@submit
                synchronized(newMaterial) { if (newMaterial.isEmpty()) return@submit }
                val sessions = app.container.sessions
                val existing = sessions.loadAutonotes(slug)
                val material = synchronized(newMaterial) { newMaterial.toString() }
                try {
                    val acc = StringBuilder()
                    val cloud = app.container.settings.cloud
                    val topic = sessions.get(slug)?.topic
                    CloudAi.chatStreamBlocking(
                        model = cloud.model,
                        systemPrompt = Prompts.autoNotesSystem(existing, topic),
                        userPrompt = Prompts.autoNotesUser(existing, material.takeLast(MAX_NEW_MATERIAL)),
                        api = cloud.api,
                        baseUrl = cloud.baseUrl,
                        apiKey = app.container.settings.apiKey,
                        onToken = {},
                    ).let { delta ->
                        if (delta.trim().isNotEmpty()) sessions.saveAutonotes(slug, buildNotes(existing, delta))
                    }
                } catch (e: Exception) {
                    // the UI is gone by now; the material stays queued in the
                    // transcript file for the next analysis pass
                }
            }
        } catch (e: Exception) {
            /* teardown must never wedge on a wedged pool */
        }
        transcribePool.shutdown()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** ⏸ ⇄ ▶ the session's audio: file writing and VAD both stop for the
     *  pause (the timeline compresses, recorder-style); clock, screen and
     *  notification all follow at once. */
    private fun togglePause() {
        paused = !paused
        CaptureBus.recording.value = CaptureBus.recording.value?.copy(paused = paused)
        postNotification()
    }

    private fun liveTitle(): String =
        if (paused) "⏸ Paused — ${metaTitle()}" else "Recording — ${metaTitle()}"

    private fun liveText(): String {
        val s = ((CaptureBus.recording.value?.recordedMs ?: 0L) / 1000).toInt()
        val count = synchronized(lines) { lines.size }
        val last = synchronized(lines) { lines.lastOrNull() }
        return "%d:%02d".format(s / 60, s % 60) + " of audio · $count transcript lines" +
            (last?.let { " — ${it.text.take(50)}" } ?: "")
    }

    /** The LIVE recording notification: the recording's own length, the
     *  transcript's life, and the two controls a recorder needs — pause and
     *  end — usable even from the lockscreen.
     *  Android 16+: a ProgressStyle live notification (requested promoted via
     *  the compat opt-in + POST_PROMOTED_NOTIFICATIONS) — the bar IS the
     *  session's audio timeline: one segment per minute, deep purple where
     *  the VAD heard speech that minute, the tracker riding the file's head.
     *  Older devices fall back to the plain ongoing form (compat-style
     *  rendering), so this stays a single code path. */
    private fun buildLiveNotification(): Notification {
        val recMs = CaptureBus.recording.value?.recordedMs ?: 0L
        val minutes = maxOf(1L, recMs / 60_000).toInt()
        // speech-activity map by LOCAL wall minute since the stream opened
        val spoken = HashSet<Int>()
        if (sessionStartMs > 0) {
            fun wallMinute(ms: Long): Int =
                java.util.Calendar.getInstance().apply { timeInMillis = ms }.let {
                    it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
                }
            val startMin = wallMinute(sessionStartMs)
            synchronized(lines) {
                for (l in lines) {
                    val parts = l.t.split(":")
                    if (parts.size == 2) {
                        val h = parts[0].toIntOrNull()
                        val m = parts[1].toIntOrNull()
                        if (h != null && m != null) {
                            val idx = ((h * 60 + m) - startMin + 1440) % 1440
                            if (idx < minutes + 1) spoken.add(idx)
                        }
                    }
                }
            }
        }
        val SPOKEN = 0xFF6750A4.toInt() // the app's primary purple
        val QUIET = 0xFFE8DEF8.toInt()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOnlyAlertOnce(true)
            .setContentTitle(liveTitle())
            .setContentText(liveText())
            .setContentIntent(appPi)
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Resume" else "Pause",
                pausePi,
            )
            .addAction(android.R.drawable.ic_lock_power_off, "End", endPi)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgress(minutes.coerceAtMost(100))
                    .setProgressSegments(
                        (0 until minutes).map { mi ->
                            NotificationCompat.ProgressStyle.Segment(1).setColor(if (mi in spoken) SPOKEN else QUIET)
                        }
                    )
                    .setProgressPoints(listOf(NotificationCompat.ProgressStyle.Point(-1).setColor(SPOKEN)))
                    .setProgressTrackerIcon(
                        androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.drawable.ic_launcher_foreground),
                    )
            )
            .build()
    }

    private fun metaTitle(): String = app.container.sessions.get(slug)?.title ?: "session"

    /** Fire-and-forget re-post — a wedged notification system must never take
     *  down the transcript thread that asked for an update. */
    private fun postNotification() {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildLiveNotification())
        } catch (e: Exception) {
            android.util.Log.d("livenotes", "notification post failed: ${e.message}")
        }
    }

    /**
     * The clean End: release capture, wait out everything still queued (the
     * loop's VAD-flush queues the tail utterance first, then a FIFO marker in
     * the pool returns after the whole queue drained), force the final notes
     * pass over whatever material remains, and only then actually stop. The
     * UI's wrapping-up panel streams these two stages instead of the button
     * pretending instant truth.
     */
    private fun finishAndStop() {
        running = false
        paused = false
        scope.launch {
            CaptureBus.wrapUp.value = WrapUp(transcriptDone = false, notesDone = false)
            // 1) the record loop's exit (its flush is what queues the tail)
            try { recordThread?.join(5000) } catch (e: InterruptedException) { }
            recordThread = null
            withContext(Dispatchers.IO) {
                // FIFO marker: .get() returns after every queued utterance
                transcribePool.submit<Boolean> { true }.get()
            }
            CaptureBus.wrapUp.value = WrapUp(transcriptDone = true, notesDone = false)
            // 2) the live notes: honor the toggle (transcript-only means no
            //    AI work), wait out a compile that was mid-flight, then force
            var guard = 0
            while (notesInFlight && guard++ < 120) delay(500)
            if (CaptureBus.notesEnabled.value) {
                try { tick(force = true) } catch (e: Exception) {}
            }
            CaptureBus.wrapUp.value = WrapUp(transcriptDone = true, notesDone = true)
            stopSelf()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 11
        private const val ACTION_TOGGLE_PAUSE = "com.sinesislab.livenotes.capture.TOGGLE_PAUSE"
        private const val ACTION_END = "com.sinesislab.livenotes.capture.END"
        /** Set while capture runs — screens re-open a session straight into
         *  recording mode when its service is still live. */
        @Volatile var activeSlug: String? = null
        const val RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SAMPLES = RATE * 80 / 1000 // 1280 — 80 ms frames
        private const val TICK_MS = 20000L
        // the AI-compile gate lives in tick(): once a couple of lines pile up
        // (≥2 new lines or ≥600 chars) — never transcript-blocking
        private const val MAX_NEW_MATERIAL = 6000
        const val EXTRA_SLUG = "slug"

        fun start(ctx: Context, slug: String) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CaptureService::class.java).putExtra(EXTRA_SLUG, slug))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CaptureService::class.java))
        }

        /** The screen's shared End: a clean wind-down (the tail transcript
         *  + one final notes pass) then stop — the notification's End uses
         *  the same action. */
        fun end(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_END))
        }

        /** ⏸/▶ the current session (the screen's pause button; when no
         *  service runs, nothing armed the notification, so nothing fires). */
        fun togglePause(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_TOGGLE_PAUSE))
        }
    }
}