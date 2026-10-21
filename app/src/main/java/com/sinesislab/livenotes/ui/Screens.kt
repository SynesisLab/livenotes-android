package com.sinesislab.livenotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
            LazyColumn(Modifier.weight(1.1f).fillMaxWidth().padding(top = 12.dp).height(160.dp)) {
                items(lines) { l -> Text("${l.t}  ${l.text}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
            }
            // the AI notes panel: streaming tokens first, then the settled content
            val notesShown = notes.content
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp)) {
                Row {
                    Text("LIVE NOTES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (notes.busy) Text("  ● AI writing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    notesShown.ifBlank { "AI notes appear within ~20 s of speech…" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp).verticalScroll(rememberScrollState()),
                )
            }
            Button(
                onClick = {
                    CaptureService.stop(context)
                    mode = "ended"
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text("■ End session") }
        }

        if (mode == "ended") {
            Text("Session ended — the transcript and live notes are saved on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
            Text("(Re-analysis runs in the next milestone — the storage and prompts are ready, see README.)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("← all sessions") }
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
        when (engine.state) {
            WhisperEngine.LoadState.READY -> Text("✓ model loaded — recording works offline.", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            WhisperEngine.LoadState.DOWNLOADING -> Text("⤓ downloading… ${engine.progress}%", modifier = Modifier.padding(top = 6.dp))
            else -> {
                Text("No model yet — download the recommended starter (≈ 57 MB, one time):", modifier = Modifier.padding(top = 6.dp))
                ElevatedButton(
                    onClick = {
                        container.whisper.downloadModel(
                            "ggml-base.en-q5_1.bin",
                            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin",
                        ) { _, err -> message = err ?: "✓ model ready" }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("⤓ Download ggml-base.en-q5_1.bin") }
            }
        }
        message?.let { Text(it, modifier = Modifier.padding(top = 12.dp)) }
    }
}