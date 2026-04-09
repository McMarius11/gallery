# Echo App - Project Context

This is a fork of Google's **AI Edge Gallery** Android app, rebranded as **Echo** - a voice-first on-device AI assistant with Kokoro TTS, Whisper ASR, and a telephony-style call UI.

## Branch Structure

| Branch | Purpose |
|--------|---------|
| `main` | Mirror of upstream (Google). No custom changes here. |
| `echo` | Main development branch with all Echo customizations. |

**Rule:** Never commit Echo-specific changes to `main`. Always work on `echo`.

## Remotes

| Remote | URL | Purpose |
|--------|-----|---------|
| `origin` | `McMarius11/gallery` (GitHub) | Your fork |
| `upstream` | `google-ai-edge/gallery` (GitHub) | Original Google repo |

## Upstream Update Workflow

```bash
git fetch upstream
git checkout main
git merge upstream/main
git push origin main
git checkout echo
git merge main
# Resolve conflicts if any, then:
git push origin echo
```

## Building & CI/CD Pipeline

### CI Pipeline (`.github/workflows/build_android.yaml`)

On every push to `echo` or `main` (when `Android/` files change):

1. **Lint & Unit Tests** job runs first:
   - `./gradlew lintDebug --continue` (results uploaded as `lint-results` artifact)
   - `./gradlew test --continue` (results uploaded as `test-results` artifact)
   - Lint uses `abortOnError = false` so warnings don't block builds

2. **Build Echo APK** job (depends on lint/test):
   - Builds debug + release APKs
   - Signs release APK using GitHub Secrets
   - Renames APKs with version info: `echo-v1.0.11-abc1234-release.apk`
   - Uploads both as GitHub Actions artifacts (30 day retention)

3. **Auto GitHub Release** (only on push to `echo`):
   - Creates a GitHub Release tagged `v{versionName}-{versionCode}` (e.g. `v1.0.11-23`)
   - Attaches the signed release APK
   - Overwrites existing release for the same version

### In-App Auto-Update System

The app checks for updates on startup via GitHub Releases API:
- **`AppUpdateChecker.kt`**: Queries `/repos/mcmarius11/gallery/releases/latest`, compares `versionCode`
- **`AppUpdateDialog.kt`**: Shows update dialog, downloads via `DownloadManager`, installs via `FileProvider`
- Users can dismiss per-version (stored in `SharedPreferences`)
- Requires `REQUEST_INSTALL_PACKAGES` permission in AndroidManifest

### Versioning

Version is defined in `Android/src/app/build.gradle.kts`:
- `versionCode = 23` — integer, must increment for each release (used by auto-update)
- `versionName = "1.0.11"` — user-facing version string
- Release tag format: `v{versionName}-{versionCode}` (e.g. `v1.0.11-23`)

**To release a new version:** Increment `versionCode` (and optionally `versionName`) in `build.gradle.kts`, commit, and push to `echo`. The CI will build, sign, and create the GitHub Release automatically. The app will detect the new version on next startup.

### Signing

Release APKs are signed via GitHub Secrets:
- `SIGNING_KEYSTORE_BASE64` — base64-encoded `.jks` keystore
- `SIGNING_KEYSTORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

### Manual Local Build

```bash
cd Android/src
./gradlew assembleDebug
# APK output: app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease
# APK output: app/build/outputs/apk/release/app-release.apk (debug-signed without secrets)
```

### Build Requirements

- **JDK 21** (Temurin recommended)
- **Android SDK** compileSdk 35, minSdk 31, targetSdk 35
- **Architecture:** arm64-v8a only (native libs are arm64 only)
- **Gradle 8.10.2** (via wrapper)

## What Was Changed (Echo vs Upstream)

### 1. Rebranded to "Echo"
All user-facing "Edge Gallery"/"AI Edge Gallery" strings replaced.
- `strings.xml`, `AndroidManifest.xml`, `HomeScreen.kt`, `settings.gradle.kts`
- `DownloadRepository.kt`, `GemmaTermsOfUseDialog.kt`
- Package name `com.google.ai.edge.gallery` left unchanged (too invasive to rename)

### 2. Fixed "0 Models" bug
Added `task.updateTrigger` after loading model allowlist.
- `ModelManagerViewModel.kt`: after allowlist models are added to tasks

### 3. Removed unused task tiles
Dagger providers commented out (code still exists) to minimize merge conflicts:
- Prompt Lab (`LlmSingleTurnTaskModule.kt`)
- Tiny Garden (`TinyGardenTaskModule.kt`)
- Mobile Actions (`MobileActionsModule.kt`)
- Agent Skills (`AgentChatTaskModule.kt`)
- Ask Image (`LlmAskImageModule` in `LlmChatTaskModule.kt`)
- Audio Scribe (`LlmAskAudioModule` in `LlmChatTaskModule.kt`)

### 4. Added Voice tile
New `LlmVoiceTask` in `LlmChatTaskModule.kt`.
- Registered as `BuiltInTaskId.LLM_VOICE = "llm_voice"`
- Uses same chat screen as AI Chat but with `voiceMode = true`
- Voice models populated from same allowlist as LLM_CHAT models

### 5. Kokoro TTS (Text-to-Speech)
On-device neural TTS using Kokoro via sherpa-onnx. Replaces Android system TTS.
- **KokoroTtsEngine** (`tts/KokoroTtsEngine.kt`): ONNX-based inference, 22,050 Hz, sentence-level streaming
- **KokoroModelManager** (`tts/KokoroModelManager.kt`): Downloads model from HuggingFace (`kokoro-en-v0_19`), tracks status via `KokoroModelStatus` (NOT_DOWNLOADED, DOWNLOADING, READY, ERROR)
- **TtsManager** (`ui/common/chat/TtsManager.kt`): Singleton, manages engine lifecycle, voice selection
- **11 voices:** Alloy, Bella, Nicole, Sarah, Sky (Female), Adam, Michael (Male), Emma, Isabella (British F), George, Lewis (British M)
- Model files stored in `files/kokoro/` (model.onnx, voices.bin, tokens.txt, espeak-ng-data)
- Download progress shown as indicator in Voice tile

### 6. On-Device ASR (Whisper via sherpa-onnx)
Fallback speech recognition when Google SpeechRecognizer is unavailable.
- **SherpaAsrEngine** (`tts/SherpaAsrEngine.kt`): Whisper small (int8 quantized), 16 kHz, 80-dim MFCC
- **AsrModelManager** (`tts/AsrModelManager.kt`): Downloads encoder/decoder/tokens from HuggingFace
- **FeatureConfig** (`com/k2fsa/sherpa/onnx/OfflineRecognizer.kt`): sampleRate, featureDim, dither (0.0 to prevent crashes)
- Automatic silence detection (RMS < 400, 1500ms), min speech 300ms
- `HoldToDictateViewModel` checks `SpeechRecognizer.isRecognitionAvailable()` to pick engine

### 7. Telephony Call Mode
Full-screen phone-call-style UI for continuous voice conversation.
- **TelephonyCallScreen** (`ui/telephony/TelephonyCallScreen.kt`): Dark immersive UI with animated avatar orb, amplitude feedback, call timer (MM:SS), phase status text
- **TelephonyViewModel** (`ui/telephony/TelephonyViewModel.kt`): CallPhase enum (IDLE, LISTENING, PROCESSING, SPEAKING), tracks call state
- **ConversationLoopController** (`ui/telephony/ConversationLoopController.kt`): Orchestrates Listen → LLM → Speak → Listen loop, barge-in support (interrupts TTS), max 5 consecutive errors before stopping
- Accessed from Voice tile's "Call Maya" button
- Uses KokoroTtsEngine for TTS during calls

### 8. Persona Presets
Selectable personality presets for Maya.
- **PersonaPresets** (`data/PersonaPresets.kt`): Maya (Standard), Maya (Unfiltered), Maya (Flirty), Custom
- Displayed as chips in Config Dialog → "System prompt" tab
- Clicking a chip populates the prompt editor; users can further customize

### 9. Voice Selection
Users can choose from 11 Kokoro voices.
- Settings icon (gear) → Config Dialog → "Model Configs" tab → Voice selector
- Config key: `VOICE_SELECTION` in `data/Config.kt`
- Applied via `TtsManager.setVoice()` immediately, no model reinitialization needed
- Only shown when `TtsManager.getAvailableVoices()` is non-empty

### 10. Debug Logs Viewer
In-app log viewer for troubleshooting.
- **DebugLogsDialog** (`ui/home/DebugLogsDialog.kt`): Full-screen dialog, "Show all" / "Errors only" filter, copy-to-clipboard
- Uses `AppLogReader` helper
- Error messages during download failures link here: "Check Settings → Debug logs"

### 11. System Prompt Editing
Both Chat and Voice tiles allow editing via settings icon.
- Settings icon (gear) → "System prompt" tab in config dialog
- System prompt preserved on session reset
- Changes applied immediately via `resetSession(systemInstruction = ...)`

### 12. Voice Mode Input
Tap-to-speak as default input in Voice tile.
- `voiceMode` flag flows: `LlmChatScreen` → `ChatViewWrapper` → `ChatView` → `ChatPanel` → `MessageInputText`
- Tap mic button to start listening, auto-stops on silence
- VoiceRecognizerOverlay shows full-screen feedback during recognition
- Keyboard/voice toggle via icon button
- Uses `HoldToDictateViewModel` (Hilt-injected, always created unconditionally)

### 13. In-App Auto-Update
Checks GitHub Releases API on app start, shows update dialog if newer version exists.
- **AppUpdateChecker** (`ui/home/AppUpdateChecker.kt`): GitHub API query, versionCode comparison, dismiss tracking
- **AppUpdateDialog** (`ui/home/AppUpdateDialog.kt`): Download via DownloadManager, install via FileProvider
- Wired into `HomeScreen.kt` via `LaunchedEffect`

## Key Files

| File | Role |
|------|------|
| `ui/home/HomeScreen.kt` | Home screen with app title, task tiles, and update check |
| `ui/home/AppUpdateChecker.kt` | GitHub Releases API update checker |
| `ui/home/AppUpdateDialog.kt` | Update download and install dialog |
| `ui/home/DebugLogsDialog.kt` | In-app debug log viewer |
| `ui/home/SettingsDialog.kt` | App settings (theme, etc.) |
| `ui/llmchat/LlmChatTaskModule.kt` | Chat + Voice task definitions and Dagger modules |
| `ui/llmchat/LlmChatScreen.kt` | Chat screen with voiceMode and TTS integration |
| `ui/telephony/TelephonyCallScreen.kt` | Full-screen phone call UI |
| `ui/telephony/TelephonyViewModel.kt` | Call state management |
| `ui/telephony/ConversationLoopController.kt` | Listen → LLM → Speak → Listen loop |
| `ui/common/chat/ChatPanel.kt` | Message list and input area |
| `ui/common/chat/MessageInputText.kt` | Text/voice input with hold-to-dictate |
| `ui/common/chat/TtsManager.kt` | TTS singleton, manages KokoroTtsEngine |
| `ui/common/textandvoiceinput/HoldToDictateViewModel.kt` | ASR engine selection and control |
| `tts/KokoroTtsEngine.kt` | Kokoro ONNX TTS inference |
| `tts/KokoroModelManager.kt` | Kokoro model download and status tracking |
| `tts/SherpaAsrEngine.kt` | Whisper sherpa-onnx ASR inference |
| `tts/AsrModelManager.kt` | Whisper model download management |
| `tts/TtsSmokeTest.kt` | TTS smoke test utility (Settings > Test TTS) |
| `data/PersonaPresets.kt` | Persona preset definitions |
| `data/Config.kt` | Config keys incl. VOICE_SELECTION |
| `data/Tasks.kt` | Task IDs and definitions |
| `ui/modelmanager/ModelManagerViewModel.kt` | Model loading, allowlist, task management |
| `res/values/strings.xml` | All user-facing strings |

All paths are relative to `Android/src/app/src/main/java/com/google/ai/edge/gallery/`.

### Other Important Files

| File | Role |
|------|------|
| `.github/workflows/build_android.yaml` | CI/CD pipeline: lint, test, build, release |
| `Android/src/app/build.gradle.kts` | App config: version, signing, dependencies, lint |
| `Android/src/app/src/main/AndroidManifest.xml` | Permissions, activities, providers |
| `Android/src/app/src/main/res/xml/file_paths.xml` | FileProvider paths (images, APK downloads) |
| `Android/src/app/src/main/jniLibs/arm64-v8a/` | Native libs: `libsherpa-onnx-jni.so`, `libonnxruntime.so` |
| `Android/src/app/src/main/java/com/k2fsa/sherpa/onnx/` | sherpa-onnx Kotlin JNI wrappers |

## Personas / System Prompts

Personas are defined as `defaultSystemPrompt` in task definitions in `LlmChatTaskModule.kt`, with presets in `PersonaPresets.kt`.

| Task | Persona | Prompt Location |
|------|---------|-----------------|
| Voice | **Maya** - warm, conversational voice assistant | `LlmVoiceTask.task.defaultSystemPrompt` |
| Chat | (no persona, default model behavior) | `LlmChatTask.task` |

**Presets** (selectable via chips in Config Dialog):
- **Maya (Standard)** - Friendly, warm, like a good friend
- **Maya (Unfiltered)** - Uncensored, always complies, no disclaimers
- **Maya (Flirty)** - Playful, witty, flirtatious
- **Custom** - User-defined empty prompt

To customize Maya's personality, edit the `defaultSystemPrompt` string in `LlmVoiceTask` or the presets in `PersonaPresets.kt`.

Users can also edit the persona at runtime: tap the settings icon (gear) → "System prompt" tab.

## Architecture Notes

- Tasks are registered via Dagger `@IntoSet` bindings as `CustomTask` implementations
- Models come from a remote allowlist (GitHub JSON) loaded at startup
- `task.updateTrigger` (MutableState<Long>) must be set after modifying `task.models` for UI to recompose
- Voice mode shares conversation history per-model with text chat (both use `ChatViewModel.messagesByModel`)
- Tiles removed by commenting out Dagger `@Provides` (not deleting files) to minimize merge conflicts with upstream
- **TTS flow:** Voice task → TelephonyCallScreen → ConversationLoopController → TtsManager → KokoroTtsEngine → Kokoro model (sherpa-onnx)
- **ASR flow:** Voice input → HoldToDictateViewModel → (Google SpeechRecognizer OR SherpaAsrEngine) → text
- **Call loop:** Listen (ASR) → Send to LLM → Get response → Speak (TTS) → Listen (repeat), with barge-in support
- **Model downloads:** KokoroModelManager and AsrModelManager handle download from HuggingFace with progress tracking, error handling, and retry
- **Update flow:** App start → AppUpdateChecker → GitHub API → compare versionCode → show AppUpdateDialog → DownloadManager → FileProvider install

### sherpa-onnx Native Library

- **Version:** v1.12.36
- **Bundled as:** Pre-built `.so` files in `jniLibs/arm64-v8a/` (no Maven dependency)
- **Files:** `libsherpa-onnx-jni.so` (5.1MB), `libonnxruntime.so` (19MB)
- **Used by:** Both Kokoro TTS (`KokoroTtsEngine`) and Whisper ASR (`SherpaAsrEngine`)
- **Kotlin wrappers:** `com/k2fsa/sherpa/onnx/` — must match the native lib version exactly
- **CRITICAL:** If the Kotlin wrapper data classes don't match the JNI expectations of the native lib, it causes SIGABRT (not catchable by try/catch). Always update wrappers when changing the native lib version.

### Crash Handling

- **xCrash** catches both Java exceptions AND native SIGABRT/SIGSEGV
- Initialized in `GalleryApplication.attachBaseContext()`
- Crash tombstones checked on next app start via `NativeCrashHandler.checkPendingCrash()`
- **SherpaAsrEngine** has `initFailed` flag + canary pattern to prevent repeated native crashes
- **KokoroTtsEngine** has the same canary pattern (ported from ASR): SharedPreferences flags set before/cleared after each JNI call. If SIGABRT kills the process, the flag persists → detected on next start → crash counter incremented → TTS disabled after 2 crashes. Also logs memory info, AudioTrack state, and exact sentence being spoken at crash time to `tts_crash_trace.txt`.
- **ConversationLoopController** has a 45s TTS timeout watchdog — if `onSpeakingDone` never fires (native crash), it recovers the conversation loop instead of hanging forever in SPEAKING phase
- **TtsManager** wraps `speak()` with try-catch and always invokes `onDone` callback even on error
- **AsrModelManager** validates model file sizes (not just existence) to detect corrupt downloads
- **TtsSmokeTest** (Settings > "Test TTS") runs 7 progressive tests to systematically diagnose TTS issues
- **DebugLogsDialog** has a "TTS Trace" button to view `tts_crash_trace.txt` directly without needing a crash

### Known Pitfalls

- **Native SIGABRT from sherpa-onnx:** `OfflineRecognizer.newFromFile()` and `getResult()` can crash with SIGABRT if model files are corrupt or Kotlin wrappers don't match the native lib. Cannot be caught by try/catch. The `initFailed` flag and canary pattern in SherpaAsrEngine and KokoroTtsEngine prevent repeated crashes.
- **espeak-ng data files:** Kokoro TTS requires espeak-ng language definition files (`espeak-ng-data/lang/gmw/en`) for phonemization. Without them, `generateWithCallback()` crashes with SIGABRT in native code. The `KokoroModelManager` must download ALL required espeak-ng files — not just dictionaries and phondata, but also the `lang/` directory. See "TTS Crash Investigation" below.
- **Model file corruption:** HuggingFace downloads can be truncated. `AsrModelManager.checkModelReady()` and `KokoroModelManager.checkModelReady()` validate minimum file sizes. Corrupt files are auto-deleted for re-download.
- **Kotlin compiler flags:** Using `-Xcontext-parameters` (not the deprecated `-Xcontext-receivers`)
- **hiltViewModel import:** Use `androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel` (the `navigation.compose` variant is deprecated)
- **AndroidManifest:** Don't add `package=` attribute — it's set via `namespace` in `build.gradle.kts`
- **Lint:** `abortOnError = false` in build.gradle.kts — lint reports warnings but doesn't fail the build
- **MODEL_TAR constant:** `KokoroModelManager.MODEL_TAR` references `sherpa-onnx-tts-kokoro-en-v1.0-int8.tar.bz2` which does NOT exist. This is dead code. The actual download uses HuggingFace `kokoro-en-v0_19` individual files. There is no English-only v1.0 model — v1.0 models are all multi-lingual.

### TTS Crash Investigation (April 2025) — RESOLVED

**Symptom:** App crashes with SIGABRT during `KokoroTtsEngine.generateWithCallback()` on EVERY text, even single word "Test". Init succeeds (sampleRate=24000, speakers=11), plenty of RAM (5GB free).

**Investigation steps taken:**
1. Added canary pattern to KokoroTtsEngine (like SherpaAsrEngine) — SharedPreferences flags set before/cleared after JNI calls → confirmed crash happens during `generateWithCallback()`, not init
2. Added TTS smoke test (Settings > "Test TTS") with 7 progressive test phrases → ALL crash with `generateWithCallback()`, even "Test"
3. Added TTS timeout watchdog to ConversationLoopController → prevents loop hang in SPEAKING phase
4. Verified native lib version (v1.12.35) and JNI callback signature match Kotlin wrapper
5. Verified model version (kokoro-en-v0.19) is correct for native lib (official release includes v0.19)
6. Checked HuggingFace repo file list: 355 espeak-ng files, but only 6 were downloaded → fixed by adding espeak-ng lang files to download list
7. After espeak-ng fix, TTS still crashed with `generateWithCallback()` → switched to `generate()` (no callback) → **8/8 smoke tests pass**

**Root cause: Bug in sherpa-onnx v1.12.35's JNI callback mechanism.**
`generateWithCallback()` calls from native C++ back into Kotlin/JVM via JNI during audio generation. This callback transition crashes with SIGABRT. The `generate()` function does the exact same TTS work (same model, same text, same parameters) but returns all samples at once without any JNI callback — and works perfectly.

**Contributing factor (fixed earlier):** Missing `espeak-ng-data/lang/gmw/en` language definition files also caused SIGABRT during phonemization. Fixed by adding all required espeak-ng files to `KokoroModelManager`'s download list. Both fixes were necessary.

**Final fix:** Replaced `generateWithCallback()` with `generate()` in `KokoroTtsEngine.speak()`. Instead of streaming audio chunks via JNI callback to AudioTrack during generation, we now generate all samples per sentence first, then write them to AudioTrack in one go.

**Trade-off:** ~1-3s latency before each sentence starts playing (vs immediate streaming with callback). Acceptable because text is split into sentences, so each chunk is short.

**Confirmed working:** 8/8 smoke tests pass (v1.0.13, April 2025):
- Engine ready (0ms), generate() no callback (1438ms), Single word (2231ms), Simple sentence (3359ms), Numbers (4159ms), Longer text (13555ms), Special chars (5684ms), Question (3073ms)

**What was NOT the cause:**
- Model version mismatch (v0.19 is correct for v1.12.35)
- JNI wrapper mismatch (callback signature `([F)Ljava/lang/Integer;` matches — but the callback mechanism itself is broken)
- Memory issues (5GB RAM free, `lowMemory=false`)
- Text-specific issues (crashes on all text)
- Threading issues (numThreads=1)
- Model file corruption (all files pass size validation)

**Diagnostic tools added (remain useful for future debugging):**
- `KokoroTtsEngine`: Canary pattern, crash counter, initFailed flag, memory logging, AudioTrack state logging
- `TtsSmokeTest`: 8 progressive tests accessible from Settings > "Test TTS"
- `ConversationLoopController`: 45s TTS timeout, consecutive TTS error tracking
- `TtsManager`: try-catch around speak(), crash-history check
- `DebugLogsDialog`: "TTS Trace" button to view tts_crash_trace.txt

**IMPORTANT for future sherpa-onnx upgrades:** Do NOT switch back to `generateWithCallback()` without testing. If a future sherpa-onnx version fixes the JNI callback bug, switching back would restore real-time streaming (lower latency). Test with the TTS smoke test first.
