package com.sinesislab.livenotes.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sinesislab.livenotes.AppContainer
import com.sinesislab.livenotes.ai.CloudAi
import com.sinesislab.livenotes.ai.Prompts
import com.sinesislab.livenotes.capture.AutoNotesState
import com.sinesislab.livenotes.capture.CaptureBus
import com.sinesislab.livenotes.capture.CaptureService
import com.sinesislab.livenotes.stt.WhisperEngine
import com.sinesislab.livenotes.store.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal screens: list → session (record → live transcript + AI notes →
 * stop) → settings (cloud AI + whisper model). Re-analysis UI is the next
 * milestone (its prompts + storage already ship in this repo — see README).
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

@Composable
private fun ListScreen(container: AppContainer, onNew: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🎙 Live Notes", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text("⚙ settings") }
        }
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("＋ New recording") }
        container.sessions.all.forEach { s ->
            ElevatedCard(onClick = { onOpen(s.slug) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        s.updatedAt.take(16).replace('T', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionScreen(container: AppContainer, slug: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val sessions = container.sessions
    var session by remember { mutableStateOf(if (slug.isBlank()) null else sessions.get(slug)) }
    var mode by remember { mutableStateOf(if (slug.isBlank()) "idle" else "ended") } // idle | recording | ended
    var title by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val lines by CaptureBus.transcript.collectAsState()
    val notes by CaptureBus.notes.collectAsState()
    val engine by container.whisper.state.collectAsState()

    // entering an existing session: the files fill the bus
    LaunchedEffect(session?.slug) {
        val s = session?.slug ?: return@LaunchedEffect
        CaptureBus.transcript.value = sessions.transcriptLines(s)
        CaptureBus.notes.value = AutoNotesState(sessions.loadAutonotes(s), null, busy = false)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (mode != "recording") TextButton(onClick = onBack) { Text("← back") }
            Text(
                session?.title ?: "New recording",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            val chip = when (engine.state) {
                WhisperEngine.LoadState.READY -> "🔒 whisper ready"
                WhisperEngine.LoadState.DOWNLOADING -> "⤓ ${engine.progress}%"
                WhisperEngine.LoadState.LOADING -> "loading…"
                WhisperEngine.LoadState.ERROR -> "⚠ whisper"
                WhisperEngine.LoadState.IDLE -> "whisper"
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
            Button(
                onClick = {
                    scope.launch {
                        val meta = sessions.create(title.trim())
                        session = meta
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
                    Text(
                        notes.content.ifBlank { "AI notes appear within ~20 s of speech…" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp).verticalScroll(rememberScrollState()),
                    )
                }
            } else {
                // transcript takes the whole body
                LiveTranscriptPane(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp))
            }
            Button(
                onClick = { CaptureService.stop(context); mode = "ended" },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text("■ End session") }
        }

        if (mode == "ended") {
            val s = session
            if (s != null) {
                EndedTabs(container, s.slug, Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp))
                TextButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) { Text("← all sessions") }
            }
        }
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
    LaunchedEffect(lines.size) {
        // follow the tail only while the user is already reading at the bottom
        val atBottom = (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 1
        if (lines.isNotEmpty() && atBottom) listState.animateScrollToItem(lines.size - 1)
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
 *  and the ✨ comprehensive re-analysis (streamed; runs kept as files). Each
 *  of transcript/notes exports via the system share-sheet. */
@Composable
private fun EndedTabs(container: AppContainer, slug: String, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf("transcript") }
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "transcript" to "Transcript",
                "notes" to "Live notes",
                "analysis" to "✨ Analysis",
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
                Column(Modifier.weight(1f).padding(top = 10.dp)) {
                    Text(container.sessions.loadAutonotes(slug).ifBlank { "(the AI has no notes yet for this session)" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
                ExportRow(container, slug, kind = "notes")
            }
            "analysis" -> AnalysisPane(container, slug, Modifier.weight(1f).padding(top = 10.dp))
        }
    }
}

/** Share-sheet export row: transcript keeps its timestamps; notes ship as-is. */
@Composable
private fun ExportRow(container: AppContainer, slug: String, kind: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(modifier = Modifier.padding(top = 4.dp)) {
        TextButton(
            onClick = {
                val text = if (kind == "transcript") container.sessions.transcriptText(slug) else container.sessions.loadAutonotes(slug)
                if (text.isBlank()) return@TextButton
                val title = (container.sessions.get(slug)?.title ?: slug) +
                    " — " + (if (kind == "transcript") "transcript" else "live notes")
                try {
                    context.startActivity(
                        android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_SUBJECT, title)
                                .putExtra(android.content.Intent.EXTRA_TEXT, text),
                            "Export $kind",
                        ),
                    )
                } catch (e: Exception) {
                    // no share target available — nothing user-visible to do
                }
            },
        ) { Text("⤓ Export ($kind)") }
    }
}

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

/** The ✨ tab: one-tap comprehensive re-analysis of the full transcript,
 *  streamed token-by-token; runs are kept as analysis-<n>.md. */
@Composable
private fun AnalysisPane(container: AppContainer, slug: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var stream by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val lastRun = remember(slug) { container.sessions.loadAnalysis(slug) } // (count, latest content)

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
                                systemPrompt = Prompts.analysisSystem(null),
                                userPrompt = Prompts.analysisUser(null, transcript),
                                api = settings.api,
                                baseUrl = settings.baseUrl,
                                apiKey = container.settings.apiKey,
                                onToken = { tok -> acc.append(tok); stream = acc.toString() },
                            )
                            val result = acc.toString().trim()
                            if (result.isBlank()) throw IllegalStateException("The analysis came back empty.")
                            container.sessions.saveAnalysis(slug, result)
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
                    Text(shown, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// (no helper needed — fixed-height panes keep the layout stable)

@Composable
private fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
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

        Text("Transcription (on-device whisper)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        val currentModel = settings.whisperModelPath.substringAfterLast('/')
        when (engine.state) {
            WhisperEngine.LoadState.DOWNLOADING -> Text("⤓ downloading… ${engine.progress}%", modifier = Modifier.padding(top = 6.dp))
            WhisperEngine.LoadState.READY -> Text("✓ model loaded — recording works offline.", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            WhisperEngine.LoadState.LOADING -> Text("loading whisper…", modifier = Modifier.padding(top = 6.dp))
            WhisperEngine.LoadState.ERROR -> Text("⚠ ${engine.detail ?: "whisper needs attention"}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            WhisperEngine.LoadState.IDLE -> Text("model idle", modifier = Modifier.padding(top = 6.dp))
        }
        // quality ladder — the big multilingual model listed FIRST (mishears
        // of vocabulary were the base model's doing; the phone has the RAM)
        val hf = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
        listOf(
            "ggml-large-v3-turbo-q5_0.bin" to "Best — multilingual (547 MB)",
            "ggml-base-q5_1.bin" to "Starter — multilingual (57 MB)",
            "ggml-small.en.bin" to "Middle — English only (465 MB)",
            "ggml-base.en-q5_1.bin" to "Starter — English only (57 MB)",
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            listOf(
                "auto" to "Auto",
                "en" to "English",
                "zh" to "中文",
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
            value = settings.whisperLanguage.takeUnless { it in listOf("", "auto", "en", "zh", "ja", "es") } ?: "",
            onValueChange = { code -> if (code.length <= 6) settings.whisperLanguage = code.trim() },
            label = { Text("Other language code (e.g. fr, de, ko…)") },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            singleLine = true,
        )
        message?.let { Text(it, modifier = Modifier.padding(top = 12.dp)) }
    }
}