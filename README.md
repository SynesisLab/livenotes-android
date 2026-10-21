# 🎙 Live Notes — Android

**The phone records, transcribes, and takes notes — independently.** On-device capture, on-device whisper.cpp, notes through a cloud AI API you configure once. No desktop, no server, no LAN juggling required for the capture loop.

The sibling project **[transcribe-livenotes](https://github.com/SynesisLab/transcribe-livenotes)** is the desktop app (Node + whisper.cpp + Ollama/cloud); this repo is its native Android companion, promoted to the plan's mobile-of-record (PLAN.md Phase 6 there — "the Android APK should be able to independently record, transcribe and send it off to the cloud for processing and note taking").

## Architecture

```
 microphone ── AudioRecord 16 kHz ──▶ foreground service ──▶ RMS VAD (400 ms pre-roll,
                                                             800 ms end-silence — same constants
                                                             as the desktop's useRecorder.js)
                                                  │
                                                  ▼
                                     whisper.cpp on-device (NDK/JNI,
                                     app/src/main/cpp/whisper — per ABI)
                                                  │
                                     transcript "- [HH:MM] text" (spoken-at)
                                                  │
              filesDir/data/sessions/<slug>/ ◀── persistence (desktop's layout)
                                                  │
                                                  ▼
                                     cloud AI (OkHttp, OpenAI-compatible or
                                     Anthropic Messages API): live-notes
                                     ticker (~20 s) + end-of-session
                                     comprehensive re-analysis ✨
```

- **Foreground service** (`FOREGROUND_SERVICE_MICROPHONE`) — capture continues with the screen off. This is the native advantage the desktop PWA never had.
- **whisper on-device** — the model (~57 MB for `ggml-base.en-q5_1`, one-time download inside the app) lives in `filesDir/models`; the engine is loaded once and shared.
- **Timestamps stay spoken-at** — they come from the VAD chunker on this side, so the transcript has the same contract as the desktop's.
- **Cloud AI is the notes engine** (desktop decision D6/D8) — notes stream token-by-token into a live panel; "End session → ✨ Re-analyze" re-reads the whole transcript and organizes key points / decisions / action items / open questions / highlights. Prompt texts are kept in lockstep with the desktop (`ai/Prompts.kt` ↔ `server/ai/prompts.js`).
- **API key on the device** — the documented posture change: the desktop keeps keys server-side, but here the phone IS the client of record. The key is wrapped with an AndroidKeyStore AES/GCM key (`store/AppSettings.kt`) and never logged.
- **Sessions** live in `filesDir/data` in the desktop's exact layout (`sessions.json` + `sessions/<slug>/transcript.md`, `autonotes.md`, `analysis*.md`, `latest.txt`) — a later sync/upload milestone can push them into a LiveNotes desktop server as-is.

## Status / roadmap

The repo ships the **foundation**: Gradle + NDK/CMake wiring for whisper.cpp, the capture service + VAD, the JNI engine wrapper, the cloud AI client, Keystore-key storage, session storage, and a minimal Compose UI (list → record → transcript + live notes → stop → settings with model download + Test connection).

Next milestones (each is a PR-sized step):

1. **Compile + run on a device** (`git submodule add https://github.com/ggerganov/whisper.cpp app/src/main/cpp/whisper`, build in Android Studio; see below) — this session's scaffold was written without an Android toolchain: expect small compile fixes at first sync, that's what "foundation" means.
2. **End-of-session ✨ Re-analyze UI** — the prompt family (`Prompts.analysisSystem/User`), storage (`SessionStore.saveAnalysis/loadAnalysis`, runs kept as `analysis-N.md` + latest mirrored to `analysis.md`) and the streamed cloud call are ready; this milestone is the screen + the map/merge port for transcripts beyond one context window (port `chunkTranscript`/`batchByChars` from `server/autonotes.js`).
3. **Model picker + language setting in the recording screen** (language is already stored; the setting is).
4. **Key-posture UX** — paste key → wrapped immediately; "remove" clears.
5. **Desktop sync (optional)** — POST sessions to a LiveNotes server over LAN/Tailscale so phone sessions appear on the desktop dashboard; the PWA remains the zero-app fallback.
6. Local export: share-sheet `.md` for transcript + notes + runs.

## Build it

1. Android Studio (Ladybug or newer) with the **NDK** and **CMake 3.22+** installed (SDK Manager).
2. Vendor the engine:
   ```bash
   git clone https://github.com/SynesisLab/livenotes-android
   cd livenotes-android
   git submodule add https://github.com/ggerganov/whisper.cpp app/src/main/cpp/whisper
   ```
3. Open the project in Android Studio and let it sync (it will fetch the Gradle wrapper once). Run on an arm64-v8a device or an x86_64 emulator.
4. First run: allow the microphone → settings → **⤓ Download ggml-base.en-q5_1.bin** → paste your cloud key + model → ⚡ Test connection → back out and record.

## Conventions

- Kotlin + Compose (Material3), no third-party UI libs; OkHttp is the only network dependency; `org.json` (platform-embedded) for JSON — keeping the desktop repo's "no surprise dependencies" ethos.
- Everything under `filesDir/data` mirrors the desktop tree; never commit or sync that directory.
- The prompt texts are cross-repo-locked to `transcribe-livenotes`' `server/ai/prompts.js` — an intentional duplication with a sync comment on both sides.

## License

MIT — see [LICENSE](LICENSE).