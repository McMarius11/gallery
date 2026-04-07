/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.ui.common.textandvoiceinput

import android.content.Context
import android.util.Log
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.tts.SherpaAsrEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGHTD"

private const val AUDIO_METER_MIN_DB = -2.0f
private const val AUDIO_METER_MAX_DB = 100.0f

/** The UI state of the HoldToDictateViewModel. */
data class HoldToDictateUiState(
  val recognizing: Boolean = false,
  val recognizedText: String = "",
  val autoListenRequested: Boolean = false,
)

@HiltViewModel
class HoldToDictateViewModel @Inject constructor(@ApplicationContext private val context: Context) :
  ViewModel(), RecognitionListener {
  protected val _uiState = MutableStateFlow(HoldToDictateUiState())
  val uiState = _uiState.asStateFlow()

  private val speechRecognizer: SpeechRecognizer?
  private val recognizerIntent: Intent
  private var onRecognitionDone: ((String) -> Unit)? = null
  private var onAmplitudeChanged: ((Int) -> Unit)? = null
  private var onErrorCallback: ((Int) -> Unit)? = null

  private val useSherpaAsr: Boolean
  private var sherpaAsrEngine: SherpaAsrEngine? = null

  init {
    val googleAvailable = SpeechRecognizer.isRecognitionAvailable(context)
    useSherpaAsr = !googleAvailable
    Log.w(TAG, "Google SpeechRecognizer available: $googleAvailable, useSherpaAsr: $useSherpaAsr")

    if (!useSherpaAsr) {
      // Initialize Google SpeechRecognizer
      speechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context).apply {
          setRecognitionListener(this@HoldToDictateViewModel)
        }
    } else {
      speechRecognizer = null
      sherpaAsrEngine = SherpaAsrEngine(context)
      // Try early init so any native crash happens now (at app start)
      // rather than unexpectedly during first voice input
      if (sherpaAsrEngine?.isAvailable() == true) {
        Log.w(TAG, "Attempting early Sherpa ASR init…")
        sherpaAsrEngine?.init()
      }
    }

    // Initialize Intent (used for language/model settings)
    recognizerIntent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      }
  }

  override fun onCleared() {
    super.onCleared()
    speechRecognizer?.destroy()
    sherpaAsrEngine?.free()
  }

  fun startSpeechRecognition(
    onDone: (String) -> Unit,
    onAmplitudeChanged: (Int) -> Unit,
    onError: ((Int) -> Unit)? = null,
  ) {
    onRecognitionDone = onDone
    this.onAmplitudeChanged = onAmplitudeChanged
    this.onErrorCallback = onError

    if (useSherpaAsr) {
      startSherpaRecognition(onDone, onAmplitudeChanged, onError)
    } else {
      speechRecognizer?.startListening(recognizerIntent)
      setRecognizedText(text = "")
      setRecognizing(recognizing = true)
    }
  }

  private fun startSherpaRecognition(
    onDone: (String) -> Unit,
    onAmplitudeChanged: (Int) -> Unit,
    onError: ((Int) -> Unit)? = null,
  ) {
    val engine = sherpaAsrEngine
    if (engine == null || !engine.isAvailable()) {
      Log.e(TAG, "Sherpa ASR not available (engine=${engine != null}, available=${engine?.isAvailable()})")
      onError?.invoke(5) ?: onDone("")
      return
    }
    setRecognizedText(text = "")
    setRecognizing(recognizing = true)

    engine.startListening(
      onAmplitudeChanged = { amplitude ->
        onAmplitudeChanged(amplitude)
      },
      onResult = { text ->
        setRecognizedText(text)
        setRecognizing(false)
        onDone(text)
      },
      onError = { errorCode ->
        Log.w(TAG, "Sherpa ASR error: $errorCode")
        setRecognizing(false)
        onError?.invoke(errorCode)
        if (onError == null) {
          onDone("")
        }
      },
    )
  }

  fun stopSpeechRecognition() {
    viewModelScope.launch {
      delay(500)
      if (useSherpaAsr) {
        sherpaAsrEngine?.stopListening()
      } else {
        speechRecognizer?.stopListening()
      }
      setRecognizing(recognizing = false)
    }
  }

  fun cancelSpeechRecognition() {
    setRecognizing(recognizing = false)
    if (useSherpaAsr) {
      sherpaAsrEngine?.stopListening()
    }
  }

  /** Signal that listening should auto-restart (e.g. after TTS finishes). */
  fun requestAutoListen() {
    _uiState.update { it.copy(autoListenRequested = true) }
  }

  /** Consume the auto-listen request so it doesn't fire again. */
  fun consumeAutoListen() {
    _uiState.update { it.copy(autoListenRequested = false) }
  }

  fun setRecognizing(recognizing: Boolean) {
    _uiState.update { uiState.value.copy(recognizing = recognizing) }
  }

  fun setRecognizedText(text: String) {
    _uiState.update { uiState.value.copy(recognizedText = text) }
  }

  override fun onReadyForSpeech(params: Bundle?) {}

  override fun onBeginningOfSpeech() {}

  override fun onRmsChanged(rmsdB: Float) {
    onAmplitudeChanged?.invoke(convertRmsDbToAmplitude(rmsdB = rmsdB))
  }

  override fun onBufferReceived(buffer: ByteArray?) {}

  override fun onEndOfSpeech() {}

  override fun onError(error: Int) {
    Log.w(TAG, "SpeechRecognizer error: $error")
    setRecognizing(false)
    if (onErrorCallback != null) {
      onErrorCallback?.invoke(error)
      // Don't call onRecognitionDone — the error handler decides what to do.
      // Calling both causes a restart loop in ConversationLoopController.
    } else {
      onRecognitionDone?.invoke("")
    }
  }

  override fun onResults(results: Bundle?) {
    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    if (matches != null && matches.size > 0) {
      setRecognizedText(matches.get(0) ?: "")
    } else {
      setRecognizedText("")
    }

    val curOnRecognitionDone = onRecognitionDone
    if (curOnRecognitionDone != null) {
      curOnRecognitionDone(uiState.value.recognizedText)
    }

    setRecognizing(recognizing = false)
  }

  override fun onPartialResults(partialResults: Bundle?) {
    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    if (matches != null && matches.size > 0) {
      setRecognizedText(matches.get(0) ?: "")
    } else {
      setRecognizedText("")
    }
  }

  override fun onEvent(eventType: Int, params: Bundle?) {}
}

private fun convertRmsDbToAmplitude(rmsdB: Float): Int {
  // Clamp the input value to the defined range
  var clampedRmsdB = Math.max(rmsdB, AUDIO_METER_MIN_DB)
  clampedRmsdB = Math.min(clampedRmsdB, AUDIO_METER_MAX_DB)

  // Linear scaling to a 0-65535 range
  return ((clampedRmsdB - AUDIO_METER_MIN_DB) * 65535f / (AUDIO_METER_MAX_DB - AUDIO_METER_MIN_DB))
    .toInt()
}
