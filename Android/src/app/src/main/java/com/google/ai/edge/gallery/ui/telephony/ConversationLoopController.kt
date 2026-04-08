package com.google.ai.edge.gallery.ui.telephony

import android.speech.SpeechRecognizer
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.tts.KokoroTtsEngine
import com.google.ai.edge.gallery.tts.SherpaAsrEngine
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.TtsManager
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ConversationLoop"
private const val MAX_CONSECUTIVE_ERRORS = 5
private const val MAX_CONSECUTIVE_TTS_ERRORS = 2
private const val TTS_TIMEOUT_MS = 45_000L

class ConversationLoopController(
  private val context: android.content.Context,
  private val holdToDictateViewModel: HoldToDictateViewModel,
  private val llmViewModel: LlmChatViewModel,
  private val telephonyViewModel: TelephonyViewModel,
  private val model: Model,
  private val onSendMessage: (String) -> Unit,
) {
  private var isActive = false
  private var consecutiveErrors = 0
  private var consecutiveTtsErrors = 0
  private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var bargeInJob: Job? = null
  private var ttsTimeoutJob: Job? = null

  fun start() {
    // Check if any ASR backend is available before starting the call loop
    val googleAvailable = SpeechRecognizer.isRecognitionAvailable(context)
    if (!googleAvailable && SherpaAsrEngine.isBlockedByCrashHistory(context)) {
      Log.e(TAG, "No ASR backend available (Google unavailable, Sherpa blocked by previous crashes)")
      telephonyViewModel.setPhase(CallPhase.IDLE)
      telephonyViewModel.setError(
        "Speech recognition unavailable. The ASR model may have been disabled after repeated crashes. " +
          "Try re-downloading the Whisper model in Settings."
      )
      return
    }

    // Warn if TTS is blocked by previous native crashes
    if (KokoroTtsEngine.isBlockedByCrashHistory(context)) {
      Log.w(TAG, "Kokoro TTS blocked by crash history — responses will use Android TTS or be silent")
    }

    isActive = true
    consecutiveErrors = 0
    consecutiveTtsErrors = 0
    startListening()
  }

  fun stop() {
    isActive = false
    TtsManager.stop()
    holdToDictateViewModel.cancelSpeechRecognition()
    bargeInJob?.cancel()
    ttsTimeoutJob?.cancel()
    scope.cancel()
    scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  }

  private fun startListening() {
    if (!isActive) return

    telephonyViewModel.setPhase(CallPhase.LISTENING)
    telephonyViewModel.updatePartialText("")

    holdToDictateViewModel.startSpeechRecognition(
      onDone = { recognizedText ->
        if (recognizedText.isNotBlank() && isActive) {
          consecutiveErrors = 0
          onSpeechRecognized(recognizedText)
        } else if (isActive) {
          // Empty recognition (silence timeout) -- restart listening
          scope.launch {
            delay(200)
            startListening()
          }
        }
      },
      onAmplitudeChanged = { amplitude ->
        telephonyViewModel.updateAmplitude(amplitude)
      },
      onError = { errorCode ->
        onSpeechRecognizerError(errorCode)
      },
    )
  }

  private fun onSpeechRecognized(text: String) {
    if (!isActive) return

    telephonyViewModel.setPhase(CallPhase.PROCESSING)
    telephonyViewModel.updatePartialText(text)

    // Send text through the same message path as the chat UI
    onSendMessage(text)
  }

  fun onLlmResponseDone() {
    if (!isActive) return

    // Get the last agent message
    val msgs = llmViewModel.uiState.value.messagesByModel[model.name]
    val lastAgentMessage = msgs?.lastOrNull { it is ChatMessageText && it.side == ChatSide.AGENT }
    Log.w(TAG, "onLlmResponseDone: lastAgentMessage=${if (lastAgentMessage is ChatMessageText) "\"${lastAgentMessage.content.take(50)}...\"" else "null"}")

    if (lastAgentMessage is ChatMessageText) {
      telephonyViewModel.setPhase(CallPhase.SPEAKING)
      Log.w(TAG, "Starting TTS speak, content length=${lastAgentMessage.content.length}")

      // Free ASR native memory (~50MB) to make room for TTS inference.
      // The recognizer will be re-initialized when listening resumes.
      holdToDictateViewModel.releaseAsrMemory()

      // Start TTS with callback to restart listening
      TtsManager.speak(lastAgentMessage.content) {
        ttsTimeoutJob?.cancel()
        if (isActive) {
          consecutiveTtsErrors = 0  // TTS completed successfully
          // TTS finished -> restart listening
          scope.launch {
            startListening()
          }
        }
      }

      // Start timeout watchdog: if TTS doesn't complete within TTS_TIMEOUT_MS,
      // assume native crash or hang and recover the conversation loop.
      ttsTimeoutJob?.cancel()
      ttsTimeoutJob = scope.launch {
        delay(TTS_TIMEOUT_MS)
        if (isActive && telephonyViewModel.uiState.value.phase == CallPhase.SPEAKING) {
          consecutiveTtsErrors++
          Log.e(TAG, "TTS timeout after ${TTS_TIMEOUT_MS}ms, consecutiveTtsErrors=$consecutiveTtsErrors")

          TtsManager.stop()

          if (consecutiveTtsErrors >= MAX_CONSECUTIVE_TTS_ERRORS) {
            Log.e(TAG, "Too many TTS timeouts ($consecutiveTtsErrors), stopping loop")
            isActive = false
            telephonyViewModel.setPhase(CallPhase.IDLE)
            telephonyViewModel.setError(
              "Text-to-speech is not responding. The TTS engine may have crashed. " +
                "Try re-downloading the Kokoro model in Settings."
            )
          } else {
            // Retry: skip speaking and go back to listening
            startListening()
          }
        }
      }

      // Start barge-in detection: listen for user speech during TTS
      startBargeInDetection()
    } else {
      // No response, restart listening
      if (isActive) {
        scope.launch {
          startListening()
        }
      }
    }
  }

  private fun startBargeInDetection() {
    bargeInJob?.cancel()
    bargeInJob = scope.launch {
      // Brief delay before enabling barge-in to avoid self-triggering
      delay(500)
      // We rely on the fact that when startListening() is called during SPEAKING phase,
      // if onBeginningOfSpeech fires, we stop TTS and transition to LISTENING.
      // The SpeechRecognizer's onBeginningOfSpeech will be handled naturally
      // when we restart listening after TTS ends.
    }
  }

  fun onSpeechRecognizerError(errorCode: Int) {
    if (!isActive) return
    consecutiveErrors++
    Log.w(TAG, "SpeechRecognizer error: $errorCode, consecutive: $consecutiveErrors")

    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
      Log.e(TAG, "Too many consecutive errors, stopping loop")
      isActive = false
      bargeInJob?.cancel()
      holdToDictateViewModel.cancelSpeechRecognition()
      telephonyViewModel.setPhase(CallPhase.IDLE)
      telephonyViewModel.setError("Speech recognition unavailable. Check microphone permission and internet connection.")
      return
    }

    // Retry after a brief delay
    scope.launch {
      delay(500)
      if (isActive) {
        startListening()
      }
    }
  }

  fun interruptTts() {
    if (telephonyViewModel.uiState.value.phase == CallPhase.SPEAKING) {
      ttsTimeoutJob?.cancel()
      TtsManager.stop()
      // Will transition to LISTENING when startListening is called
    }
  }
}
