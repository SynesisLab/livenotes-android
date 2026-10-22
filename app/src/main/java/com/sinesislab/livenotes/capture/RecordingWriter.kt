package com.sinesislab.livenotes.capture

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Streams the capture loop's frames into the session's audio recording —
 * one 44-byte-header WAV that grows in place, so "record the voices" holds
 * RAM flat no matter how long the session runs:
 *
 *   start → template header (lengths zeroed) + raw PCM appended per frame
 *   stop  → RIFF/data lengths patched in place from the file's true size
 *
 * The patch is idempotent and exposed standalone ([patchHeader]): a crash
 * mid-session leaves a correctly-sized file with a still-zeroed header, and
 * every later open (playback, share) repairs it first. Writer methods are
 * synchronized because the stop path and the service teardown can both reach
 * them (a join that times out leaves the loop still appending).
 */
class RecordingWriter(private val file: File, private val sampleRate: Int) {
    private var raf: RandomAccessFile? = null
    private var pack = ByteArray(0)
    @Volatile private var written: Long = 0

    /** Create the WAV — or, resuming after a crash, re-open the existing
     *  file and append. False means recording is not possible at all. */
    @Synchronized
    fun open(): Boolean = try {
        file.parentFile?.mkdirs()
        val r = RandomAccessFile(file, "rw")
        val magic = if (r.length() >= 4) ByteArray(4).also { b -> r.seek(0); r.readFully(b) } else ByteArray(0)
        if (magic.contentEquals("RIFF".toByteArray())) {
            r.seek(r.length()) // resume-in-place: the previous data stays
        } else {
            r.seek(0)
            r.setLength(0)
            writeTemplateHeader(r, sampleRate)
        }
        raf = r
        true
    } catch (e: Exception) {
        android.util.Log.e("livenotes", "recording open failed: ${e.message}")
        raf = null
        false
    }

    /** Append one capture frame (samples[0] until count, straight from the
     *  AudioRecord short buffer) in the WAV's little-endian on-disk order.
     *  Any write failure disables the writer — transcription is the primary
     *  path; the recording can give up quietly mid-session. */
    @Synchronized
    fun append(samples: ShortArray, count: Int) {
        val r = raf ?: return
        try {
            if (pack.size < count * 2) pack = ByteArray(count * 2)
            var i = 0
            for (j in 0 until count) {
                val v = samples[j].toInt()
                pack[i++] = (v and 0xFF).toByte()
                pack[i++] = ((v shr 8) and 0xFF).toByte()
            }
            r.write(pack, 0, count * 2)
            written += count * 2
        } catch (e: Exception) {
            android.util.Log.e("livenotes", "recording append failed: ${e.message}")
            try { r.close() } catch (e: Exception) {}
            raf = null
        }
    }

    /** PCM bytes written so far — the ⏺ clock's and the notification's time
     *  source (the file's own length; pauses never count). */
    fun writtenBytes(): Long = written

    /** Stop-and-patch; idempotent because the capture loop and the service
     *  teardown can both call it — only the first close matters, and the
     *  patch re-derives its lengths from the file. */
    @Synchronized
    fun finish() {
        try { raf?.close() } catch (e: Exception) { /* patch still valid on the data */ }
        raf = null
        patchHeader(file)
    }

    companion object {
        const val HEADER_BYTES = 44

        /** Rewrite the RIFF/data lengths from the file's actual size, wherever
         *  it stands — the stop-time patch, and the post-crash repair before
         *  playback/share. Returns the PCM byte length (0 when unusable). */
        fun patchHeader(file: File): Long = try {
            if (file.isFile) {
                RandomAccessFile(file, "rw").use { r ->
                    val data = r.length() - HEADER_BYTES
                    if (data >= 0) {
                        r.seek(4)
                        writeIntLe(r, 36 + data)
                        r.seek(40)
                        writeIntLe(r, data)
                    }
                    if (r.length() >= HEADER_BYTES) data else 0
                }
            } else 0
        } catch (e: Exception) {
            android.util.Log.e("livenotes", "recording patch failed: ${e.message}")
            0
        }

        private fun writeTemplateHeader(r: RandomAccessFile, sampleRate: Int) {
            val out = ByteArrayOutputStream(HEADER_BYTES)
            fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            fun int(v: Int) {
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
                out.write((v shr 16) and 0xFF)
                out.write((v shr 24) and 0xFF)
            }
            out.write("RIFF".toByteArray())
            int(36) // placeholder RIFF size — the real one lands at finish
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            int(16); short(1); short(1) // fmt chunk=16 | PCM | mono
            int(sampleRate); int(2 * sampleRate); short(2); short(16) // rate | byte rate | align | bits
            out.write("data".toByteArray())
            int(0) // placeholder data size — patched at finish
            r.setLength(44)
            r.write(out.toByteArray())
        }

        private fun writeIntLe(r: RandomAccessFile, v: Long) {
            r.write((v and 0xFF).toInt())
            r.write(((v shr 8) and 0xFF).toInt())
            r.write(((v shr 16) and 0xFF).toInt())
            r.write(((v shr 24) and 0xFF).toInt())
        }
    }
}