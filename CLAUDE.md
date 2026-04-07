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

## Key Files

| File | Role |
|------|------|
| `ui/home/HomeScreen.kt` | Home screen with app title and task tiles |
| `ui/home/DebugLogsDialog.kt` | In-app debug log viewer |
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
| `data/PersonaPresets.kt` | Persona preset definitions |
| `data/Config.kt` | Config keys incl. VOICE_SELECTION |
| `data/Tasks.kt` | Task IDs and definitions |
| `ui/modelmanager/ModelManagerViewModel.kt` | Model loading, allowlist, task management |
| `res/values/strings.xml` | All user-facing strings |

All paths are relative to `Android/src/app/src/main/java/com/google/ai/edge/gallery/`.

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

## Building

APKs are built and **signed** automatically via GitHub Actions on every push to `echo` or `main`.

- **Workflow:** `.github/workflows/build_android.yaml`
- **Triggers:** Push to `echo`/`main` (when `Android/` files change), or manual via `workflow_dispatch`
- **Signing:** Release APKs signed via GitHub Secrets (`SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`)
- **Artifacts:** Debug and Release APKs uploaded as GitHub Actions artifacts (30 day retention)
- **Download:** Go to Actions tab > latest run > Artifacts section > `echo-debug-apk` or `echo-release-apk`

Manual local build:
```bash
cd Android/src
./gradlew assembleDebug
# APK output: app/build/outputs/apk/debug/app-debug.apk
```

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
- sherpa-onnx JNI library (`libsherpa-onnx-jni.so`) used by both Kokoro TTS and Whisper ASR
