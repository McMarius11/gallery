package com.google.ai.edge.gallery.tts

import android.content.Context

interface TtsEngine {
  var onSpeakingDone: (() -> Unit)?
  fun init(context: Context)
  fun speak(text: String, onDone: (() -> Unit)? = null)
  fun stop()
  fun shutdown()
  fun isReady(): Boolean
  /** Returns list of (speakerId, displayName) pairs. Empty if engine doesn't support voice selection. */
  fun getAvailableVoices(): List<Pair<Int, String>> = emptyList()
  /** Set the active voice by speaker ID. */
  fun setVoice(id: Int) {}
}
