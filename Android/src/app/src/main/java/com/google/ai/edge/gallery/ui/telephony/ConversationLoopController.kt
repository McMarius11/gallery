package com.google.ai.edge.gallery.ui.telephony

import android.util.Log
import com.google.ai.edge.gallery.data.Model
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

class ConversationLoopController(
  private val holdToDictateViewModel: HoldToDictateViewModel,
  private val llmViewModel: LlmChatViewModel,
  private val telephonyViewModel: TelephonyViewModel,
  private val model: Model,
  private val onSendMessage: (String) -> Unit,
) {
  private var isActive = false
  private var consecutiveErrors = 0
  private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var bargeInJob: Job? = null

  fun start() {
    isActive = true
    consecutiveErrors = 0
    startListening()
  }

  fun stop() {
    isActive = false
    TtsManager.stop()
    holdToDictateViewModel.cancelSpeechRecognition()
    bargeInJob?.cancel()
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

    if (lastAgentMessage is ChatMessageText) {
      telephonyViewModel.setPhase(CallPhase.SPEAKING)

      // Start TTS with callback to restart listening
      TtsManager.speak(lastAgentMessage.content) {
        if (isActive) {
          // TTS finished -> restart listening
          scope.launch {
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
      telephonyViewModel.setPhase(CallPhase.IDLE)
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
      TtsManager.stop()
      // Will transition to LISTENING when startListening is called
    }
  }
}
