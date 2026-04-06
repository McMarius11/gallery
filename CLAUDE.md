# Echo App - Project Context

This is a fork of Google's **AI Edge Gallery** Android app, rebranded as **Echo** - a voice-first on-device AI assistant.

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

All changes are in a single commit on `echo` branch:

1. **Rebranded to "Echo"** - All user-facing "Edge Gallery"/"AI Edge Gallery" strings replaced
   - `strings.xml`, `AndroidManifest.xml`, `HomeScreen.kt`, `settings.gradle.kts`
   - `DownloadRepository.kt`, `GemmaTermsOfUseDialog.kt`
   - Package name `com.google.ai.edge.gallery` left unchanged (too invasive to rename)

2. **Fixed "0 Models" bug** - Added `task.updateTrigger` after loading model allowlist
   - `ModelManagerViewModel.kt`: after allowlist models are added to tasks

3. **Removed unused task tiles** - Dagger providers commented out (code still exists):
   - Prompt Lab (`LlmSingleTurnTaskModule.kt`)
   - Tiny Garden (`TinyGardenTaskModule.kt`)
   - Mobile Actions (`MobileActionsModule.kt`)
   - Agent Skills (`AgentChatTaskModule.kt`)
   - Ask Image (`LlmAskImageModule` in `LlmChatTaskModule.kt`)
   - Audio Scribe (`LlmAskAudioModule` in `LlmChatTaskModule.kt`)

4. **Added Voice tile** - New `LlmVoiceTask` in `LlmChatTaskModule.kt`
   - Registered as `BuiltInTaskId.LLM_VOICE = "llm_voice"`
   - Uses same chat screen as AI Chat but with `voiceMode = true`
   - Voice models populated from same allowlist as LLM_CHAT models

5. **Added TTS (Text-to-Speech)** - Only active in Voice mode
   - `TtsManager.kt` (singleton, initialized in `MainActivity.onCreate`)
   - Strips markdown before speaking
   - Triggered in `ChatViewWrapper.onDone` callback when `voiceMode = true`

6. **Voice mode input** - Hold-to-dictate as default input in Voice tile
   - `voiceMode` flag flows: `LlmChatScreen` -> `ChatViewWrapper` -> `ChatView` -> `ChatPanel` -> `MessageInputText`
   - Keyboard/voice toggle via icon button when in voice mode
   - Uses existing `HoldToDictateViewModel` (Hilt-injected)

## Key Files

| File | Role |
|------|------|
| `ui/home/HomeScreen.kt` | Home screen with app title and task tiles |
| `ui/llmchat/LlmChatTaskModule.kt` | Chat + Voice task definitions and Dagger modules |
| `ui/llmchat/LlmChatScreen.kt` | Chat screen with voiceMode and TTS integration |
| `ui/common/chat/ChatPanel.kt` | Message list and input area |
| `ui/common/chat/MessageInputText.kt` | Text/voice input with hold-to-dictate |
| `ui/common/chat/TtsManager.kt` | Text-to-speech singleton |
| `ui/modelmanager/ModelManagerViewModel.kt` | Model loading, allowlist, task management |
| `data/Tasks.kt` | Task IDs and definitions |
| `res/values/strings.xml` | All user-facing strings |

## Personas / System Prompts

Personas are defined as `defaultSystemPrompt` in task definitions in `LlmChatTaskModule.kt`.

| Task | Persona | Prompt Location |
|------|---------|-----------------|
| Voice | **Maya** - warm, conversational voice assistant | `LlmVoiceTask.task.defaultSystemPrompt` |
| Chat | (no persona, default model behavior) | `LlmChatTask.task` |

To customize Maya's personality, edit the `defaultSystemPrompt` string in `LlmVoiceTask`.
The system prompt is passed to the LLM via `LlmChatModelHelper.initialize(systemInstruction = ...)`.

## Building

APKs are built automatically via GitHub Actions on every push to `echo` or `main`.

- **Workflow:** `.github/workflows/build_android.yaml`
- **Triggers:** Push to `echo`/`main` (when `Android/` files change), or manual via `workflow_dispatch`
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
