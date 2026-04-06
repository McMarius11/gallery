package com.google.ai.edge.gallery.tts

import android.content.Context

interface TtsEngine {
  var onSpeakingDone: (() -> Unit)?
  fun init(context: Context)
  fun speak(text: String, onDone: (() -> Unit)? = null)
  fun stop()
  fun shutdown()
  fun isReady(): Boolean
}
