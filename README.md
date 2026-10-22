# 🎙 Live Notes — Android

**The phone records, transcribes, and takes notes — independently.** On-device capture, on-device whisper.cpp, notes through the cloud AI you configure once — plus a live Android 16 notification with the recording's own progress bar and Pause/End controls on it.

This is the native Android companion to **[transcribe-livenotes](https://github.com/SynesisLab/transcribe-livenotes)** — the desktop/web app (Node + whisper.cpp + Ollama/cloud) it grew out of. The phone is the client of record here: sessions mirror the desktop's on-disk layout, so a future sync pushes them into a desktop server untouched.

## What it does

- **Record** — every session streams its raw audio to `recording.wav` (16 kHz mono PCM) as it's captured; hour-long sessions cost flat RAM, and a crash mid-session is repaired on the next playback or share. Pause stops the audio file *and* the transcription (recorder-style compressed timeline); the ⏺ clock reads the WAV's own length every second, so it never lies.
- **Live transcribe** — realtime-first ladder: whisper.cpp runs on-device (NEON path, model downloaded in-app); if you switch on cloud STT, utterances upload to a `/audio/transcriptions`-compatible host under the same key the notes AI uses (~1 s round trip), and any miss — offline, bad key, wrong host — falls through to whisper instantly. Traditional Chinese (`繁體中文`) anchors the output script through a conditioning prompt on both engines; per-session language beats the global default.
- **Live AI notes** — the cloud model compiles new transcript material into growing markdown bullets every ~20 s while talk happens (decoupled: the model's slowness never blocks speech→text). A per-session toggle at the start page turns notes off for the session; still flippable mid-session.
- **Post-recording analyze** — end a session (the wind-down: the tail utterances drain onto the transcript, one final notes pass runs, stages shown), then ✨ Re-analyze the full transcript into *Key points / Decisions / Action items / Open questions / Highlights* — analysis runs are kept (`analysis.md` + numbered archives).
- **Session metadata** — name (display), **topic** (the AI's session context — never displayed, it steers every live-notes call and the re-analysis), language, and the live-notes toggle, all per session.

Everything — transcript (spoken-at `HH:MM` stamps), notes, analyses, audio — shares via the system sheet as real files (`.txt` / `.md` / `.wav`), and session titles can be renamed after the fact.

## The notification is a live notification

On Android 16+ the foreground-service notification is a **ProgressStyle live notification** (`NotificationCompat.ProgressStyle` + `setRequestPromotedOngoing`, `POST_PROMOTED_NOTIFICATIONS` in the manifest): the progress bar *is* the session's audio timeline — one segment per minute, deep purple where the VAD heard speech — with the tracker riding the file's head. Pause and End sit right on it and work from the lockscreen. Below Android 16 it renders as a plain ongoing notification. `POST_NOTIFICATIONS` is prompted on first mic grant.

## Architecture

```
 microphone ── AudioRecord 16 kHz ──▶ foreground service ──▶ RMS VAD (400 ms pre-roll,
                                                             800 ms end-silence — same constants
                                                             as the desktop's useRecorder.js)
                                                  │
                recording.wav ◀── every frame ────┤  (lossless path, never gated by STT)
                                                  │
                                                  ▼
                                     realtime-first ladder:
                                       cloud /audio/transcriptions (when switched on) 
                                       → whisper.cpp on-device fallback (NDK/JNI)
                                                  │
                                     transcript "- [HH:MM] text" (spoken-at)
                                                  │
              filesDir/data/sessions/<slug>/ ◀── persistence (desktop's layout)
                                                  │
                                                  ▼
                                     cloud AI (OkHttp, OpenAI-compatible or
                                     Anthropic Messages or Ollama): live-notes
                                     ticker (~20 s) + end-of-session re-analysis ✨
```

- **Foreground service** (`FOREGROUND_SERVICE_MICROPHONE`) — capture continues with the screen off; that's the native advantage a PWA never had. A session left with the screen away re-opens straight into recording mode.
- **whisper on-device** — the model (~57 MB `ggml-base` one-time download inside the app) lives in `filesDir/models`, loaded once, shared; quality presets up to `large-v3-turbo` are one tap in settings (the realtime-first ordering favors the fast model on phones). The JNI grew a `prompt` argument (`initial_prompt` + `carry_initial_prompt`) for script anchoring.
- **Timestamps stay spoken-at** — they come from the VAD chunker on this side, matching the desktop's contract.
- **Cloud AI is the notes engine** (desktop decision D6/D8) — notes stream token-by-token into a live markdown panel; the same configured key powers cloud STT. One key, two uses.
- **API key on the device** — the documented posture change from the desktop: the key is wrapped with an AndroidKeyStore AES/GCM key and never read back to the screen.
- **Sessions** live in `filesDir/data` in the desktop's exact layout: `sessions.json` (index: slug/title/timestamps/language/topic) + per-slug `transcript.md`, `latest.txt`, `autonotes.md`, `analysis.md` (+ `analysis-N.md` runs), `recording.wav`.

## Install a release

Download the `.apk` from the [latest release](https://github.com/SynesisLab/livenotes-android/releases), then either:

```
adb install -r livenotes-debug.apk
```

or open it on the phone and let the installer handle it (the debug key signs these builds; for daily use, build your own as below).

## Build it

1. Android Studio (Ladybug or newer) with the **NDK 28.2+** and **CMake 3.22+** (SDK Manager). AGP 8.9.1 / Gradle 8.11.1 / compileSdk 36 — the wrapper handles Gradle.
2. Vendor the engine:
   ```bash
   git clone https://github.com/SynesisLab/livenotes-android
   cd livenotes-android
   git submodule update --init app/src/main/cpp/whisper
   ```
   (`whisper.cpp` is a git submodule — clone with `--recurse-submodules` and skip this.)
3. Open the project, let it sync, run on an arm64-v8a device (or x86_64 emulator).
4. First run: allow microphone (+ notifications) → settings → **⤓ download a whisper model** → paste your cloud key/base URL/model → ⚡ Test connection → back out and record.

## Conventions

- Kotlin + Compose (Material3), no third-party UI libs; OkHttp is the only network dependency; `org.json` (platform-embedded) for JSON — keeping the desktop repo's "no surprise dependencies" ethos.
- Everything under `filesDir/data` mirrors the desktop tree; never commit or sync that directory.
- Prompt texts are cross-repo-locked to `transcribe-livenotes`' [`server/ai/prompts.js`](https://github.com/SynesisLab/transcribe-livenotes/blob/main/server/ai/prompts.js) — an intentional duplication with a sync comment on both sides (the session-topic fold-in uses the desktop's exact `profileCtx` shape).

## License

MIT — see [LICENSE](LICENSE).