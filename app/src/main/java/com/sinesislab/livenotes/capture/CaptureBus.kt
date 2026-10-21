package com.sinesislab.livenotes.capture

import com.sinesislab.livenotes.store.TsLine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** What a live AI-notes tick streams vs the settled state after a tick. */
data class AutoNotesState(val content: String, val updatedAtEpoch: Long?, val busy: Boolean)

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
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 16, replay = 1)
}