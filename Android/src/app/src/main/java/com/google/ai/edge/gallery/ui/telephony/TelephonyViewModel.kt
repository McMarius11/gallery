package com.google.ai.edge.gallery.ui.telephony

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CallPhase {
  IDLE,
  LISTENING,
  PROCESSING,
  SPEAKING,
}

data class TelephonyUiState(
  val callActive: Boolean = false,
  val callDurationSeconds: Int = 0,
  val phase: CallPhase = CallPhase.IDLE,
  val currentAmplitude: Int = 0,
  val partialRecognizedText: String = "",
)

@HiltViewModel
class TelephonyViewModel @Inject constructor() : ViewModel() {

  private val _uiState = MutableStateFlow(TelephonyUiState())
  val uiState: StateFlow<TelephonyUiState> = _uiState.asStateFlow()

  private var timerJob: Job? = null

  fun startCall() {
    _uiState.update {
      it.copy(
        callActive = true,
        callDurationSeconds = 0,
        phase = CallPhase.LISTENING,
      )
    }
    timerJob = viewModelScope.launch {
      while (isActive) {
        delay(1000)
        _uiState.update { it.copy(callDurationSeconds = it.callDurationSeconds + 1) }
      }
    }
  }

  fun endCall() {
    timerJob?.cancel()
    timerJob = null
    _uiState.update {
      TelephonyUiState() // Reset to default
    }
  }

  fun setPhase(phase: CallPhase) {
    _uiState.update { it.copy(phase = phase) }
  }

  fun updateAmplitude(amplitude: Int) {
    _uiState.update { it.copy(currentAmplitude = amplitude) }
  }

  fun updatePartialText(text: String) {
    _uiState.update { it.copy(partialRecognizedText = text) }
  }

  fun formatDuration(): String {
    val seconds = _uiState.value.callDurationSeconds
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
  }
}
