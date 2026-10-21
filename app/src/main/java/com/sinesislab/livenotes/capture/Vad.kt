package com.sinesislab.livenotes.capture

/**
 * The desktop client's RMS VAD, ported 1:1 (src/hooks/useRecorder.js):
 * slow-adapting noise floor, on/off thresholds, 400 ms pre-roll, 800 ms
 * end-silence, 400 ms minimum speech, 15 s max utterance. Feed it 16 kHz
 * frames; it hands back complete utterances with the epoch of when the
 * speech BEGAN (so transcript timestamps stay spoken-at, as on desktop).
 */
class Vad(private val onUtterance: (samples: FloatArray, spokenAtEpochMs: Long) -> Unit) {
    private companion object {
        const val RATE = 16000
        const val PRE_ROLL_MS = 400
        const val END_SILENCE_MS = 600 // the desktop's 800 traded latency for chop-resistance; on-device this closes utterances snappier
        const val MIN_SPEECH_MS = 400
        const val MAX_UTTERANCE_MS = 15000
        const val MIN_SAMPLES = 8000 // 0.5 s — same floor as sendPcm on desktop
    }

    private var speaking = false
    private var frameCount = 0L
    private var noiseFloor = 0.003
    private var frames = ArrayList<FloatArray>()
    private val preRoll = ArrayList<FloatArray>()
    private var preRollMs = 0.0
    private var silenceMs = 0.0
    private var speechMs = 0.0
    private var totalMs = 0.0
    private var startedAt = 0L

    fun feed(chunk: FloatArray, nowMs: Long) {
        if (chunk.isEmpty()) return
        val ms = chunk.size * 1000.0 / RATE
        var sum = 0.0
        for (v in chunk) sum += v.toDouble() * v
        val r = kotlin.math.sqrt(sum / chunk.size)
        // instrumented: silence-vs-speech is the first suspect when text
        // never appears; ~1 log per second, plus a line on speech start/stop
        frameCount++
        if (frameCount % 12L == 1L) android.util.Log.d("livenotes", "vad rms=$r floor=$noiseFloor speaking=$speaking")
        if (!speaking) noiseFloor = (noiseFloor * 0.95 + r * 0.05).coerceIn(0.0005, 0.02)
        val onThreshold = maxOf(noiseFloor * 3.5, 0.005)
        val offThreshold = onThreshold * 0.6

        if (!speaking && r > onThreshold) {
            speaking = true
            frames = ArrayList(preRoll)
            startedAt = nowMs - (preRollMs + speechWindowMs).toLong()
            preRoll.clear()
            preRollMs = 0.0
            silenceMs = 0.0
            speechMs = 0.0
            totalMs = 0.0
        }

        if (speaking) {
            frames.add(chunk)
            totalMs += ms
            if (r > offThreshold) {
                speechMs += ms
                silenceMs = 0.0
            } else {
                silenceMs += ms
            }
            if ((silenceMs >= END_SILENCE_MS && speechMs >= MIN_SPEECH_MS) || totalMs >= MAX_UTTERANCE_MS) {
                finish(nowMs)
            }
        } else {
            preRoll.add(chunk)
            preRollMs += ms
            while (preRollMs > PRE_ROLL_MS && preRoll.size > 1) {
                preRollMs -= preRoll.removeAt(0).size * 1000.0 / RATE
            }
        }
    }

    /** Flush any trailing speech (call on stop). */
    fun flush(nowMs: Long) {
        if (speaking) finish(nowMs)
    }

    private val speechWindowMs: Double
        get() = frames.sumOf { it.size.toDouble() } * 1000.0 / RATE

    private fun finish(nowMs: Long) {
        val taken = frames
        frames = ArrayList()
        speaking = false
        silenceMs = 0.0
        speechMs = 0.0
        totalMs = 0.0
        if (taken.isEmpty()) return
        var total = 0
        for (f in taken) total += f.size
        if (total < MIN_SAMPLES) return
        val samples = FloatArray(total)
        var off = 0
        for (f in taken) {
            f.copyInto(samples, off)
            off += f.size
        }
        onUtterance(samples, startedAt)
    }
}