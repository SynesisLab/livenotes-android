package com.sinesislab.livenotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sinesislab.livenotes.AppContainer
import com.sinesislab.livenotes.ai.CloudAi
import com.sinesislab.livenotes.ai.Prompts
import com.sinesislab.livenotes.capture.AutoNotesState
import com.sinesislab.livenotes.capture.CaptureBus
import com.sinesislab.livenotes.capture.CaptureService
import com.sinesislab.livenotes.capture.RecordingLive
import com.sinesislab.livenotes.capture.RecordingWriter
import com.sinesislab.livenotes.stt.WhisperEngine
import com.sinesislab.livenotes.store.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Minimal screens: list → session (record → live transcript + AI notes →
 * stop → transcript/notes/analysis/audio tabs) → settings (cloud AI,
 * speech-to-text engine, whisper model).
 */

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🎙", style = MaterialTheme.typography.displayLarge)
        Text("Live Notes needs your microphone", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Recording starts with it; while on-device whisper transcribes, nothing is uploaded anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 20.dp)) { Text("Allow microphone") }
    }
}

sealed interface Screen {
    data object List : Screen
    data class Session(val slug: String) : Screen
    data object Settings : Screen
}

@Composable
fun RootScaffold(container: AppContainer) {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    // edge-to-edge is on, so every screen keeps clear of the system bars —
    // without this the header buttons sit under the status bar (the emulator
    // proved it: taps at the settings label land in the status-bar zone)
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        when (val s = screen) {
            is Screen.List -> ListScreen(
                container,
                onNew = { screen = Screen.Session("") },
                onOpen = { slug -> screen = Screen.Session(slug) },
                onSettings = { screen = Screen.Settings },
            )
            is Screen.Session -> SessionScreen(container, slug = s.slug, onBack = { screen = Screen.List })
            is Screen.Settings -> SettingsScreen(container, onBack = { screen = Screen.List })
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ListScreen(container: AppContainer, onNew: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    // batch management: HOLD a card to enter selection, tap toggles, delete
    // asks through its own confirm screen (the desktop's confirm prompt
    // pattern, sized for thumbs)
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var confirming by remember { mutableStateOf(false) }
    val inSelection = selection.isNotEmpty()

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        if (inSelection) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { selection = emptySet() }) { Text("✕") }
                Text("${selection.size} selected", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { confirming = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("🗑 Delete") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎙 Live Notes", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSettings) { Text("⚙ settings") }
            }
        }
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("＋ New recording") }
        container.sessions.all.forEach { s ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                content = {
                    Box(
                        Modifier
                            // fillMaxWidth is not cosmetic: without it the
                            // clickable Box is CONTENT-sized (the title's
                            // width) — taps anywhere right of ~the title
                            // landed on dead card surface ("it has to be the
                            // far left to trigger")
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { if (inSelection) selection = selection.toggle(s.slug) else onOpen(s.slug) },
                                onLongClick = { selection = selection + s.slug },
                            )
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                (if (inSelection && s.slug in selection) "☑ " else if (inSelection) "☐ " else "") + s.title,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                s.updatedAt.take(16).replace('T', ' '),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (s.slug == CaptureService.activeSlug) {
                                Text(
                                    "⏺ recording live",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                },
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete ${selection.size} session${if (selection.size == 1) "" else "s"}?") },
            text = { Text("Their transcripts, live notes and analyses will be deleted permanently. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        selection.forEach { container.sessions.delete(it) }
                        selection = emptySet()
                        confirming = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

private fun Set<String>.toggle(v: String): Set<String> = if (v in this) this - v else this + v

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SessionScreen(container: AppContainer, slug: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val sessions = container.sessions
    var session by remember { mutableStateOf(if (slug.isBlank()) null else sessions.get(slug)) }
    // a session whose capture service still runs re-opens straight into
    // recording mode (the user left the screen mid-session)
    var mode by remember { mutableStateOf(if (slug.isBlank()) "idle" else if (slug == CaptureService.activeSlug) "recording" else "ended") } // idle | recording | ended
    // the back gesture follows the same rule as the back button: free to
    // leave except mid-session (an accidental swipe shouldn't feel like it
    // ended the recording — the service would happily keep recording anyway)
    androidx.activity.compose.BackHandler(enabled = mode != "recording") { onBack() }
    var title by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val lines by CaptureBus.transcript.collectAsState()
    val notes by CaptureBus.notes.collectAsState()
    val engine by container.whisper.state.collectAsState()
    val recordingLive by CaptureBus.recording.collectAsState()
    val wrapUp by CaptureBus.wrapUp.collectAsState()

    // entering an existing session: the files fill the bus
    LaunchedEffect(session?.slug) {
        val s = session?.slug ?: return@LaunchedEffect
        CaptureBus.transcript.value = sessions.transcriptLines(s)
        CaptureBus.notes.value = AutoNotesState(sessions.loadAutonotes(s), null, busy = false)
    }
    // the notification's ■ End (or the system) can close the session from
    // outside the screen — follow the service out of recording mode, but only
    // once its stream has actually opened (the null-before-open window at a
    // fresh start must not flip it). The wrap-up stages ride the same effect:
    // End opens the wrapping-up panel from either entry point, and the done
    // state flips it to the ended tabs (consuming itself).
    var recStarted by remember { mutableStateOf(false) }
    LaunchedEffect(recordingLive, mode, wrapUp) {
        if (recordingLive != null) recStarted = true
        if (recordingLive == null && recStarted && (mode == "recording" || mode == "wrapping")) mode = "ended"
        val w = wrapUp
        if (w != null) {
            if (w.transcriptDone && w.notesDone) {
                CaptureBus.wrapUp.value = null
                if (mode == "recording" || mode == "wrapping") mode = "ended"
            } else if (mode == "recording") {
                mode = "wrapping"
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (mode != "recording") TextButton(onClick = onBack) { Text("← back") }
            // ended sessions: tap the title to rename it (✎ is the cue; the
            // slug — and every file path — stays as the id)
            Text(
                (session?.title ?: "New recording") + if (mode == "ended") " ✎" else "",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = if (mode == "ended") {
                    Modifier.clickable { renameText = session?.title ?: ""; renaming = true }
                } else {
                    Modifier
                },
            )
            Spacer(Modifier.weight(1f))
            val base = when (engine.state) {
                WhisperEngine.LoadState.READY -> "🔒 whisper ready"
                WhisperEngine.LoadState.DOWNLOADING -> "⤓ ${engine.progress}%"
                WhisperEngine.LoadState.LOADING -> "loading…"
                WhisperEngine.LoadState.ERROR -> "⚠ whisper"
                WhisperEngine.LoadState.IDLE -> "whisper"
            }
            // cloud STT switched on: the chip names both rungs — the upload is
            // the realtime answer, the loaded engine is its fallback
            val chip = if (container.settings.cloudSttEnabled && container.settings.cloud.api == "openai") {
                "☁ realtime — $base"
            } else {
                base
            }
            Text(chip, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }

        if (mode == "idle") {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Session name (optional)") },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                singleLine = true,
            )
            // the TOPIC is a separate item from the name: it isn't displayed
            // anywhere — it's the context the live-notes AI and the
            // re-analysis receive, so the notes stay anchored to what the
            // meeting is actually about
            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it },
                label = { Text("Session topic (optional)") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
            )
            // PER-SESSION language: each session carries its own (the topic
            // goes to the AI as session context; the global whisper setting
            // stays as the device default)
            var lang by remember { mutableStateOf("auto") }
            // a FlowRow, not a fixed Row: six language entries overflow one phone line
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
                listOf(
                    "auto" to "Auto",
                    "en" to "English",
                    "zh-Hant" to "繁體中文",
                    "ja" to "日本語",
                    "es" to "Español",
                ).forEach { (code, label) ->
                    val active = lang == code
                    ElevatedButton(
                        onClick = { lang = code },
                        enabled = !active,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text(if (active) "🔒 $label" else label, style = MaterialTheme.typography.labelMedium) }
                }
            }
            // PER-SESSION live notes, chosen at start (default ON): decided
            // here, applied to just this capture — and still flippable
            // mid-session from the recording row
            var notesForSession by remember { mutableStateOf(CaptureBus.notesEnabled.value) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                ElevatedButton(
                    onClick = { notesForSession = !notesForSession },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        if (notesForSession) "✨ live notes ON" else "📝 transcript only",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    "AI notes for this session",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        val meta = sessions.create(title.trim(), lang, topic.trim())
                        session = meta
                        CaptureBus.notesEnabled.value = notesForSession
                        CaptureBus.transcript.value = emptyList()
                        CaptureService.start(context, meta.slug)
                        mode = "recording"
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(84.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White),
            ) { Text("● Record", style = MaterialTheme.typography.titleLarge) }
            Text(
                "Tap to start — capture keeps running with the screen off.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (mode == "recording") {
            val notesEnabled by CaptureBus.notesEnabled.collectAsState()
            // transcript-only mode: the toggle lives here so speech never has
            // to stop to switch the AI notes off
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                ElevatedButton(
                    onClick = { CaptureBus.notesEnabled.value = !CaptureBus.notesEnabled.value },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(if (notesEnabled) "✨ live notes ON" else "📝 transcript only", style = MaterialTheme.typography.labelMedium) }
                Spacer(Modifier.weight(1f))
                recordingLive?.let { live ->
                    // the ⏺ clock: the session's audio-recording, surfaced live
                    RecordingClock(
                        live,
                        sessions.recordingFile(session?.slug ?: ""),
                        Modifier.padding(start = 8.dp),
                    )
                    ElevatedButton(
                        onClick = { CaptureService.togglePause(context) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text(if (live.paused) "▶ Resume" else "⏸ Pause", style = MaterialTheme.typography.labelMedium) }
                }
            }
            if (notesEnabled) {
                // weight-constrained panes: an unbounded LazyColumn child grows
                // with content until the column clips it — the "transcript
                // disappears" bug when notes are on
                LiveTranscriptPane(Modifier.weight(1.1f).fillMaxWidth().padding(top = 10.dp))
                Column(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp)) {
                    Row {
                        Text("LIVE NOTES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        if (notes.busy) Text("  ● AI writing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    // the AI notes are markdown — rendered in a SCROLLING pane:
                    // a bare MarkdownBlock clips everything past its weight
                    // share once notes outgrow the screen
                    MarkedScroll(
                        notes.content.ifBlank { "*AI notes appear within ~20 s of speech…*" },
                        Modifier.weight(1f).padding(top = 4.dp),
                    )
                }
            } else {
                // transcript takes the whole body
                LiveTranscriptPane(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp))
            }
            Button(
                // the wind-down is its own mode: the queued transcript drains
                // and the final notes pass show real progress, then the tabs
                onClick = { CaptureService.end(context); mode = "wrapping" },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text("■ End session") }
        }

        if (mode == "wrapping") {
            // the wind-down, shown not assumed: the loop's tail flushes, the
            // queued utterances drain onto the transcript, one forced notes
            // pass runs, and only then do the ended tabs open
            Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Text("wrapping up…", modifier = Modifier.padding(start = 10.dp))
                }
                Text(
                    if (wrapUp?.transcriptDone == true) "✓ transcript complete" else "… draining the transcription queue",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (wrapUp?.transcriptDone == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    if (wrapUp?.notesDone == true) "✓ notes complete" else "… final live-notes pass",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (wrapUp?.notesDone == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                recordingLive?.let { live ->
                    Text(
                        "⏺ ${clock(live.recordedMs.toInt())} of audio kept",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        if (mode == "ended") {
            val s = session
            if (s != null) {
                EndedTabs(container, s.slug, Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp))
                TextButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) { Text("← all sessions") }
            }
        }
    }
    // rename-after-the-fact: only the title changes; slug, files and history
    // stay put — the list picks the new title up on the way back
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { if (it.length <= 120) renameText = it },
                    label = { Text("Session title") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    val sl = session?.slug
                    if (sl != null) {
                        sessions.rename(sl, renameText)
                        session = sessions.get(sl)
                    }
                    renaming = false
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

/** The recording-mode transcript: fills its weight share and AUTO-SCROLLS —
 *  the first build pinned a fixed-height list with no scrolling, so new
 *  speech kept appending below the fold while notes streamed (verified on a
 *  real device: transcript.md had the lines, the pane didn't). */
@Composable
private fun LiveTranscriptPane(modifier: Modifier = Modifier) {
    val lines by CaptureBus.transcript.collectAsState()
    val listState = rememberLazyListState()
    // Follow-the-tail v2. The v1 visibility heuristic (follow = "last visible
    // item was the last item") stuck at the top on re-entering a long session
    // and on wrapped long lines the moment content overflowed, and the v1
    // animate-scroll mid-flight layout reads made it worse. Contract now:
    // the ONLY thing that lifts the pin is the user's own drag; the only
    // thing that re-arms it is dragging back to the tail; each new line SNAPS
    // instantly (a million-pixel offset is clamped into the item — wrapped
    // tails and all).
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> follow = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    follow = info.totalItemsCount <= 1 || last >= info.totalItemsCount - 1
                }
                else -> {}
            }
        }
    }
    LaunchedEffect(lines.size) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1, 1_000_000)
    }
    ElevatedCard(modifier = modifier) {
        if (lines.isEmpty()) {
            Text("speech appears here…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp))
        } else {
            LazyColumn(state = listState, modifier = Modifier.padding(12.dp)) {
                items(lines) { l ->
                    Text(
                        "${l.t}  ${l.text}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** Ended-state tabs: the full timestamped transcript, the AI's live notes,
 *  the ✨ comprehensive re-analysis (streamed; runs kept as files), and the
 *  session's own recording (▶ Audio). Each exports via the share-sheet —
 *  the audio as an actual .wav file. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EndedTabs(container: AppContainer, slug: String, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf("transcript") }
    Column(modifier) {
        // a FlowRow, not a fixed Row: with the fourth tab added, the Row
        // overflowed and the ▶ Audio pill rendered as a BLANK sliver (the
        // "white pill" bug) — wrapping is the honest fix at any phone width
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "transcript" to "Transcript",
                "notes" to "Live notes",
                "analysis" to "✨ Analysis",
                "audio" to "▶ Audio",
            ).forEach { (key, label) ->
                ElevatedButton(onClick = { tab = key }, enabled = tab != key) { Text(label, style = MaterialTheme.typography.labelMedium) }
            }
        }
        when (tab) {
            "transcript" -> {
                EndedTranscript(container, slug, Modifier.weight(1f).padding(top = 10.dp))
                ExportRow(container, slug, kind = "transcript")
            }
            "notes" -> {
                // the AI bullets are markdown — scrolled like the live pane
                MarkedScroll(
                    container.sessions.loadAutonotes(slug).ifBlank { "*(the AI has no notes yet for this session)*" },
                    Modifier.weight(1f).padding(top = 10.dp),
                )
                ExportRow(container, slug, kind = "notes")
            }
            "analysis" -> AnalysisPane(container, slug, Modifier.weight(1f).padding(top = 10.dp))
            "audio" -> AudioPane(container, slug, Modifier.weight(1f).padding(top = 10.dp))
        }
    }
}

/** Share-sheet export button → hands the receiver an actual file. */
@Composable
private fun ExportRow(container: AppContainer, slug: String, kind: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(modifier = Modifier.padding(top = 4.dp)) {
        TextButton(
            onClick = { exportAsFile(context, container, slug, kind) },
        ) { Text("⤓ Export ($kind)") }
    }
}

/** Markdown in pure Compose — no markdown dependency: the AI surfaces emit a
 *  well-known subset (##/### headings, bullet lists, checklists, **bold**,
 *  *italic*, `code`) matching the desktop's prompt contract, so a tiny
 *  renderer covers it all. */
private val INLINE_MD = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`")

private fun String.mdInline(): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in INLINE_MD.findAll(this@mdInline)) {
        if (m.range.first > i) append(this@mdInline.substring(i, m.range.first))
        val marker = this@mdInline[m.range.first]
        pushStyle(
            when {
                marker == '`' -> SpanStyle(fontFamily = FontFamily.Monospace)
                this@mdInline[m.range.first + 1] == '*' -> SpanStyle(fontWeight = FontWeight.Bold)
                else -> SpanStyle(fontStyle = FontStyle.Italic)
            }
        )
        append(m.value.trimStart('*', '`', ' ').trimEnd('*', '`', ' '))
        pop()
        i = m.range.last + 1
    }
    if (i < this@mdInline.length) append(this@mdInline.substring(i))
}

@Composable
private fun MarkdownBlock(text: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        for (raw in text.lineSequence()) {
            val ln = raw.trimEnd()
            when {
                ln.isBlank() -> Spacer(Modifier.height(4.dp))
                ln.startsWith("### ") -> Text(ln.substring(4).mdInline(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                ln.startsWith("## ") -> Text(ln.substring(3).mdInline(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                ln.startsWith("# ") -> Text(ln.substring(2).mdInline(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Regex("^[-*]\\s+\\[[ xX]]\\s+(.*)$").matchEntire(ln) != null -> {
                    val m = Regex("^[-*]\\s+\\[[ xX]]\\s+(.*)$").matchEntire(ln)!!
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (m.groupValues[1].isNotBlank() && m.value.contains("[xX]")) "☑ " else "☐ ")
                        Text(m.groupValues[1].mdInline())
                    }
                }
                Regex("^[-*]\\s+(.*)$").matchEntire(ln) != null -> Row {
                    Text(Regex("^[-*]\\s+(.*)$").matchEntire(ln)!!.let { "• " })
                    Text(Regex("^[-*]\\s+(.*)$").matchEntire(ln)!!.groupValues[1].mdInline())
                }
                Regex("^\\d+\\.\\s+(.*)$").matchEntire(ln) != null -> Column { Text(ln.mdInline()) }
                else -> Text(ln.mdInline(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Export as an actual file (a phone's share sheet with a raw text blob is
 * useless — the ask was "a text file"): transcript → .txt (keeps the
 * HH:MM stamps), AI notes / analysis → .md (they're markdown docs by
 * contract), the session's audio → the real .wav. Shares the written file
 * through FileProvider so Drive/Notes/chat apps can receive it as a document.
 */
private fun exportAsFile(context: android.content.Context, container: AppContainer, slug: String, kind: String) {
    val prettyTitle = container.sessions.get(slug)?.title ?: slug
    val safeName = prettyTitle.lowercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
        .joinToString("").foldToSingleDash().trim('-').take(60).ifBlank { "session" }
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file: File
    var mime = "text/plain"
    when (kind) {
        // the recording is binary — a copy of the real file goes to the sheet
        "recording" -> {
            val src = container.sessions.recordingFile(slug)
            if (!src.isFile) return
            file = File(dir, "$safeName-recording.wav")
            src.copyTo(file, overwrite = true)
            mime = "audio/wav"
        }
        else -> {
            val text = when (kind) {
                "transcript" -> container.sessions.transcriptText(slug)
                "notes" -> container.sessions.loadAutonotes(slug)
                else -> container.sessions.loadAnalysis(slug).second
            }
            if (text.isBlank()) return
            file = File(dir, "$safeName-$kind.${if (kind == "transcript") "txt" else "md"}")
            file.writeText(text)
        }
    }
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    try {
        context.startActivity(
            android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "$prettyTitle — $kind")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Export $kind as a file",
            ),
        )
    } catch (e: Exception) {
        // no share target — the file still exists in cacheDir/exports
    }
}

private fun String.foldToSingleDash(): String =
    StringBuilder().also { sb ->
        for (c in this) if (!(c == '-' && sb.isNotEmpty() && sb.last() == '-')) sb.append(c)
    }.toString()

@Composable
private fun EndedTranscript(container: AppContainer, slug: String, modifier: Modifier = Modifier) {
    val lines = remember(slug) { container.sessions.transcriptLines(slug) }
    LazyColumn(modifier) {
        items(lines) { l ->
            Text("${l.t}  ${l.text}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
        }
        if (lines.isEmpty()) item {
            Text("no speech was captured.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The live ⏺ clock — built the unbreakable way: it stats the WAV's own
 *  length every second (the file is ground truth; no flow can lag behind it).
 *  `paused` comes off the bus so the frozen value shows ⏸. */
@Composable
private fun RecordingClock(live: RecordingLive, file: File, modifier: Modifier = Modifier) {
    var tickMs by remember {
        mutableIntStateOf((maxOf(0L, file.length() - RecordingWriter.HEADER_BYTES) / 32).toInt())
    }
    LaunchedEffect(file, live.paused) {
        while (true) {
            tickMs = (maxOf(0L, file.length() - RecordingWriter.HEADER_BYTES) / 32).toInt()
            delay(1000)
        }
    }
    Text(
        "${if (live.paused) "⏸" else "⏺"} ${clock(tickMs)}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

/** Markdown inside a vertical-scroll pane — the raw MarkdownBlock clips any
 *  content past its weight share (long sessions outgrow the screen). */
@Composable
private fun MarkedScroll(text: String, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        MarkdownBlock(text)
    }
}

/** The ▶ Audio tab: the session's recording.wav — the capture loop streamed
 *  every frame here beside the transcription — with play/pause, a seek bar,
 *  and the file handed over as a real .wav. A crashed session is repaired on
 *  entry: the header patch rewrites its lengths from the file's true size. */
@Composable
private fun AudioPane(container: AppContainer, slug: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(slug) { container.sessions.recordingFile(slug) }
    // repair first (~8 bytes, idempotent), then anything may open the file
    LaunchedEffect(slug) { if (file.isFile) withContext(Dispatchers.IO) { RecordingWriter.patchHeader(file) } }

    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var totalMs by remember { mutableIntStateOf(0) }
    var posMs by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    val playing = player?.isPlaying == true

    DisposableEffect(slug) {
        onDispose {
            val p = player
            player = null
            try { p?.stop() } catch (e: Exception) {}
            p?.release()
        }
    }
    // position poller at 3 Hz — as smooth as a seek bar needs; paused while
    // the user drags so the slider never fights their thumb
    LaunchedEffect(player != null) {
        while (player != null) {
            if (!dragging) player?.let { posMs = it.currentPosition }
            delay(300)
        }
    }

    if (!file.isFile) {
        Column(modifier) {
            Text("no audio recorded for this session", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "no file was written — this session predates audio recording, or its writer never opened.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        return
    }

    Column(modifier) {
        Text(
            "16 kHz mono — ${"%.1f".format(file.length() / 1e6)} MB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = {
                val p = player
                if (p != null) {
                    // already prepared: pause ⇄ start reuses the same player
                    if (p.isPlaying) p.pause() else p.start()
                } else {
                    scope.launch {
                        val mp = android.media.MediaPlayer()
                        try {
                            withContext(Dispatchers.IO) {
                                RecordingWriter.patchHeader(file)
                                mp.setDataSource(file.path)
                                mp.prepare()
                            }
                            mp.setOnCompletionListener { ended ->
                                player = null
                                posMs = 0
                                ended.release()
                            }
                            totalMs = mp.duration
                            posMs = 0
                            mp.start()
                            player = mp
                        } catch (e: Exception) {
                            try { mp.release() } catch (e: Exception) {}
                            CaptureBus.errors.tryEmit("Audio playback failed: ${e.message}")
                        }
                    }
                }
            }) { Text(if (playing) "⏸ Pause" else "▶ Play") }
            Text(
                "${clock(posMs)} / ${if (totalMs > 0) clock(totalMs) else "–:––"}",
                style = MaterialTheme.typography.labelMedium,
                color = if (totalMs > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { exportAsFile(context, container, slug, "recording") }) { Text("⤓ Share") }
        }
        if (totalMs > 0) {
            Slider(
                value = (posMs.toFloat() / totalMs).coerceIn(0f, 1f),
                onValueChange = { frac ->
                    dragging = true
                    posMs = (frac.coerceIn(0f, 1f) * totalMs).toInt()
                },
                onValueChangeFinished = {
                    dragging = false
                    player?.seekTo(posMs)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** m:ss (h:mm:ss past the hour) — one clock for the live ⏺ chip and the
 *  Audio tab alike. */
private fun clock(ms: Int): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

/** The ✨ tab: one-tap comprehensive re-analysis of the full transcript,
 *  streamed token-by-token; runs are kept as analysis-<n>.md. */
@Composable
private fun AnalysisPane(container: AppContainer, slug: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var stream by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // a VAR state (not a frozen remember): after a run it must reflect the
    // saved file, or the pane reverts to the pre-run snapshot — the
    // "flashes then back to just the button" bug
    var lastRun by remember(slug) { mutableStateOf(container.sessions.loadAnalysis(slug)) } // (count, latest content)

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    running = true
                    stream = ""
                    error = null
                    scope.launch {
                        try {
                            val transcript = container.sessions.transcriptText(slug)
                            if (transcript.isBlank()) throw IllegalStateException("Nothing to analyze — no speech captured yet.")
                            val settings = container.settings.cloud
                            val acc = StringBuilder()
                            CloudAi.chatStream(
                                model = settings.model,
                                systemPrompt = Prompts.analysisSystem(container.sessions.get(slug)?.topic),
                                userPrompt = Prompts.analysisUser(null, transcript),
                                api = settings.api,
                                baseUrl = settings.baseUrl,
                                apiKey = container.settings.apiKey,
                                onToken = { tok -> acc.append(tok); stream = acc.toString() },
                            )
                            val result = acc.toString().trim()
                            if (result.isBlank()) throw IllegalStateException("The analysis came back empty.")
                            container.sessions.saveAnalysis(slug, result)
                            // refresh so the pane lands ON the new run
                            lastRun = container.sessions.loadAnalysis(slug)
                        } catch (e: Exception) {
                            error = e.message ?: "Re-analysis failed."
                        } finally {
                            running = false
                            stream = ""
                        }
                    }
                },
                enabled = !running,
            ) { Text(if (running) "analyzing…" else if (lastRun.first > 0) "↻ Re-analyze again" else "✨ Re-analyze transcript") }
            if (lastRun.first > 0) {
                Text("run #${lastRun.first} saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // the analysis exports as a .md file like the other surfaces
            val context = androidx.compose.ui.platform.LocalContext.current
            TextButton(
                onClick = { exportAsFile(context, container, slug, "analysis") },
                enabled = lastRun.first > 0,
            ) { Text("⤓ Export (analysis)") }
        }
        if (error != null) {
            Text("✗ $error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        val shown = if (stream.isNotEmpty()) stream else lastRun.second
        if (shown.isBlank() && !running) {
            Text("Re-reading the WHOLE transcript once: key points, decisions, action items, open questions.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        } else {
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                item {
                    // the analysis is markdown — render it
                    MarkdownBlock(shown)
                }
            }
        }
    }
}

// (no helper needed — fixed-height panes keep the layout stable)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler { onBack() }
    val settings = container.settings
    // re-load the persisted model on entry — the engine state resets to IDLE
    // on every app launch, and only the capture service normally loads it;
    // without this the screen would claim "No model yet" after it exists
    LaunchedEffect(Unit) { container.whisper.ensureLoaded() }
    var api by remember { mutableStateOf(settings.cloud.api) }
    var baseUrl by remember { mutableStateOf(settings.cloud.baseUrl) }
    var model by remember { mutableStateOf(settings.cloud.model) }
    var keyInput by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val engine by container.whisper.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("← back") }
        Text("Note-taking AI (cloud)", style = MaterialTheme.typography.titleMedium)
        // three wire flavors — pick the one your provider speaks
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            listOf(
                "openai" to "OpenAI-compatible",
                "ollama" to "Ollama",
                "anthropic" to "Anthropic",
            ).forEach { (kind, label) ->
                ElevatedButton(
                    onClick = { api = kind },
                    enabled = api != kind,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(label, style = MaterialTheme.typography.labelMedium) }
            }
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL (OpenAI-compatible; Anthropic later)") },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            singleLine = true,
        )
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(if (settings.hasApiKey) "API key — stored (type to replace)" else "API key") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
            // the key must never read back off the screen (or a screenshot)
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
        )
        ElevatedButton(
            onClick = {
                if (keyInput.isNotBlank()) settings.apiKey = keyInput
                settings.cloud = com.sinesislab.livenotes.store.AppSettings.CloudConfig(api = api, baseUrl = baseUrl.trim(), model = model.trim())
                scope.launch {
                    message = withContext(Dispatchers.IO) {
                        runCatching {
                            CloudAi.chatStreamBlocking(
                                model = model.trim(),
                                systemPrompt = "You are a note-taking assistant. Reply with exactly: ok",
                                userPrompt = "Test",
                                onToken = {},
                                api = api,
                                baseUrl = baseUrl.trim(),
                                apiKey = settings.apiKey,
                            )
                            "✓ connection works"
                        }.getOrElse { e -> "✗ ${e.message ?: "failed"}" }
                    }
                }
            },
            modifier = Modifier.padding(top = 10.dp),
        ) { Text("⚡ Test connection") }

        Text("Speech-to-text engine", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        // the realtime ladder, as a switch: cloud STT answers each utterance
        // in ~1 s (whisper on a phone can't); whisper stays loaded either way
        // and takes every utterance the cloud misses — never the reverse
        var sttOn by remember { mutableStateOf(settings.cloudSttEnabled) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            ElevatedButton(
                onClick = { sttOn = true; settings.cloudSttEnabled = true },
                enabled = !sttOn,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("☁ cloud — realtime", style = MaterialTheme.typography.labelMedium) }
            ElevatedButton(
                onClick = { sttOn = false; settings.cloudSttEnabled = false },
                enabled = sttOn,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("🔒 on-device whisper", style = MaterialTheme.typography.labelMedium) }
        }
        Text(
            if (sttOn) "each utterance uploads to <base URL>/audio/transcriptions under the note-taking AI's key; on-device whisper takes over on any upload miss (no network, no key, wrong host)."
            else "fully offline — no audio leaves the device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (sttOn && api != "openai") {
            Text(
                "the note-taking AI is set to a non-OpenAI host, which has no /audio/transcriptions endpoint — utterances will fall back to whisper.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (sttOn && !settings.hasApiKey) {
            Text(
                "⚠ no API key stored yet — set it under the note-taking AI above, or whisper transcribes everything.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (sttOn) {
            var sttModel by remember { mutableStateOf(settings.cloudSttModel) }
            OutlinedTextField(
                value = sttModel,
                onValueChange = { m -> if (m.length <= 64) { sttModel = m; settings.cloudSttModel = m.trim() } },
                label = { Text("STT model (whisper-1, gpt-4o-transcribe…)") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                singleLine = true,
            )
        }
        Text("Fallback: on-device whisper", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp))
        val currentModel = settings.whisperModelPath.substringAfterLast('/')
        when (engine.state) {
            WhisperEngine.LoadState.DOWNLOADING -> Text("⤓ downloading… ${engine.progress}%", modifier = Modifier.padding(top = 6.dp))
            WhisperEngine.LoadState.READY -> Text("✓ model loaded — recording works offline.", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            WhisperEngine.LoadState.LOADING -> Text("loading whisper…", modifier = Modifier.padding(top = 6.dp))
            WhisperEngine.LoadState.ERROR -> Text("⚠ ${engine.detail ?: "whisper needs attention"}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            WhisperEngine.LoadState.IDLE -> Text("model idle", modifier = Modifier.padding(top = 6.dp))
        }
        // quality ladder — REAL-TIME-FIRST: on a phone CPU the small, fast
        // model IS the recommendation (near-large accuracy belongs to
        // desktop-class hardware; the 547 MB turbo ran ~87 s per 10 s of
        // speech on a flagship device)
        val hf = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
        listOf(
            "ggml-base-q5_1.bin" to "Fast — multilingual, realtime (57 MB)",
            "ggml-small.en.bin" to "Middle — English only (465 MB, less realtime)",
            "ggml-large-v3-turbo-q5_0.bin" to "Highest quality — slow on a phone (547 MB)",
            "ggml-base.en-q5_1.bin" to "Fast — English only (57 MB)",
        ).forEach { (fileName, label) ->
            val active = currentModel == fileName
            ElevatedButton(
                onClick = {
                    message = null
                    container.whisper.downloadModel(fileName, hf + fileName) { _, err ->
                        message = err ?: "✓ $fileName ready — next recording uses it"
                    }
                },
                enabled = engine.state != WhisperEngine.LoadState.DOWNLOADING && !active,
                modifier = Modifier.padding(top = 6.dp),
            ) { Text(if (active) "🔒 $label — active" else "⤓ $label", style = MaterialTheme.typography.labelMedium) }
        }

        Text("Language", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            listOf(
                "auto" to "Auto",
                "en" to "English",
                "zh-Hant" to "繁體中文",
                "ja" to "日本語",
                "es" to "Español",
            ).forEach { (code, label) ->
                val active = settings.whisperLanguage == code
                ElevatedButton(
                    onClick = { settings.whisperLanguage = code },
                    enabled = !active,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(if (active) "🔒 $label" else label, style = MaterialTheme.typography.labelMedium) }
            }
        }
        // any other of whisper's ~90 language codes
        OutlinedTextField(
            value = settings.whisperLanguage.takeUnless { it in listOf("", "auto", "en", "zh", "zh-Hant", "ja", "es") } ?: "",
            onValueChange = { code -> if (code.length <= 6) settings.whisperLanguage = code.trim() },
            label = { Text("Other language code (e.g. fr, de, ko…)") },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            singleLine = true,
        )
        message?.let { Text(it, modifier = Modifier.padding(top = 12.dp)) }
    }
}