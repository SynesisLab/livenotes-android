package com.sinesislab.livenotes.capture

import com.sinesislab.livenotes.store.TsLine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** What a live AI-notes tick streams vs the settled state after a tick. */
data class AutoNotesState(val content: String, val updatedAtEpoch: Long?, val busy: Boolean)

/** State of the audio being recorded (its file lives at
 *  SessionStore.recordingFile(slug)): the WAV's own length — pauses don't
 *  count — and whether the stream is currently paused. */
data class RecordingLive(val recordedMs: Long, val paused: Boolean = false)

/** What's still finishing after ■ End: the tail utterances draining through
 *  the transcription queue, then one final forced live-notes pass. */
data class WrapUp(val transcriptDone: Boolean, val notesDone: Boolean)

/**
 * The service → UI channel: a session recording pushes its transcript lines
 * and notes state here; Compose screens collect these instead of holding
 * their own copies, so rotation or navigating away keeps the session intact.
 */
object CaptureBus {
    val transcript = MutableStateFlow<List<TsLine>>(emptyList())
    val notes = MutableStateFlow(AutoNotesState("", null, busy = false))
    /** Live-notes toggle (per device); OFF = transcript-only mode: the
     *  service skips the AI ticker and the UI hides the notes pane. */
    val notesEnabled = MutableStateFlow(true)
    val notesStreaming = MutableSharedFlow<String>(extraBufferCapacity = 64, replay = 1)
    /** Non-null while a capture runs — the session's audio is streaming into
     *  recording.wav; screens show the ⏺ elapsed clock from startedAtEpoch. */
    val recording = MutableStateFlow<RecordingLive?>(null)
    /** Non-null from the End press until the UI consumes the done state (the
     *  next session start re-clears it) — the wrapping-up panel runs off it. */
    val wrapUp = MutableStateFlow<WrapUp?>(null)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 16, replay = 1)
}