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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * The app's core loop — a foreground service so capture + transcription
 * survive the screen turning off (the advantage the PWA never had). One
 * service owns: AudioRecord 16 kHz capture → RMS VAD → on-device whisper →
 * transcript lines → cloud AI live notes every ~20 s. Everything persists
 * under filesDir/data (SessionStore) in the desktop's layout, so a future
 * sync path can hand sessions over untouched.
 */
class CaptureService : Service() {
    private lateinit var app: NotesApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transcribePool = Executors.newSingleThreadExecutor()
    private var recordThread: Thread? = null
    @Volatile private var running = false
    private var slug = ""
    private var notesJob: Job? = null
    private val newMaterial = StringBuilder()
    @Volatile private var notesInFlight = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as NotesApp
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        app.container.whisper.ensureLoaded()
        startForeground(NOTIFICATION_ID, buildNotification(meta.title))
        startCapture()
        if (notesJob == null) notesJob = scope.launch { notesTicker() }
        return START_STICKY
    }

    /// Capture loop: whisper runs on the transcribe pool, so the read loop
    /// never waits behind a long transcription (multiple utterances queue up
    /// and are fed to the engine one at a time, in order).
    private fun startCapture() {
        if (running) return
        val sessions = app.container.sessions
        val vad = Vad { samples, spokenAt ->
            transcribePool.execute {
                val text = app.container.whisper.transcribe(samples)
                if (text.isNotEmpty()) {
                    sessions.appendLine(slug, text)
                    CaptureBus.transcript.tryEmit(sessions.transcriptLines(slug))
                    synchronized(newMaterial) { newMaterial.append('\n').append(text) }
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
        record.startRecording()
        val loop = Runnable {
            val buf = ShortArray(FRAME_SAMPLES)
            while (running) {
                val n = record.read(buf, 0, FRAME_SAMPLES)
                if (n <= 0) continue
                val f32 = FloatArray(n)
                for (i in 0 until n) f32[i] = buf[i] / 32768f
                // wall clock: the VAD stamps spoken-at epochs, which become the
                // transcript's HH:MM — same contract as the desktop client
                vad.feed(f32, System.currentTimeMillis())
            }
            vad.flush(System.currentTimeMillis())
            record.stop()
            record.release()
        }
        recordThread = Thread(loop, "livenotes-capture").also { it.start() }
    }

    private suspend fun notesTicker() {
        while (true) {
            delay(TICK_MS)
            tick(force = false)
        }
    }

    private suspend fun tick(force: Boolean) {
        if (notesInFlight) return
        synchronized(newMaterial) {
            if (!force && newMaterial.length < MIN_NEW_CHARS) return
            if (newMaterial.isEmpty()) return
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
            CloudAi.chatStream(
                model = cloud.model,
                systemPrompt = Prompts.autoNotesSystem(existing),
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
        // One last transcription-independent tick so the tail of speech isn't
        // left waiting for a beat that never comes — blocking, on the
        // transcribe pool, because coroutines die with this scope.
        try {
            transcribePool.submit {
                synchronized(newMaterial) { if (newMaterial.isEmpty()) return@submit }
                val sessions = app.container.sessions
                val existing = sessions.loadAutonotes(slug)
                val material = synchronized(newMaterial) { newMaterial.toString() }
                try {
                    val acc = StringBuilder()
                    val cloud = app.container.settings.cloud
                    CloudAi.chatStreamBlocking(
                        model = cloud.model,
                        systemPrompt = Prompts.autoNotesSystem(existing),
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
            }.get()
        } catch (e: Exception) {
            /* service teardown must never wedge on a hung provider — the
               hard timeout inside chatStreamBlocking bounds this already */
        }
        transcribePool.shutdown()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(title: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Recording — $title")
            .setContentText("Transcribing on-device; notes run on the cloud AI")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
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
        const val RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SAMPLES = RATE * 80 / 1000 // 1280 — 80 ms frames
        private const val TICK_MS = 20000L
        private const val MIN_NEW_CHARS = 250
        private const val MAX_NEW_MATERIAL = 6000
        const val EXTRA_SLUG = "slug"

        fun start(ctx: Context, slug: String) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CaptureService::class.java).putExtra(EXTRA_SLUG, slug))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CaptureService::class.java))
        }
    }
}