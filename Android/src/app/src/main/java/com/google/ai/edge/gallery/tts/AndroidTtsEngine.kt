package com.google.ai.edge.gallery.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class AndroidTtsEngine : TtsEngine {
  private var tts: TextToSpeech? = null
  private var isInitialized = false

  override var onSpeakingDone: (() -> Unit)? = null

  override fun init(context: Context) {
    if (tts == null) {
      tts = TextToSpeech(context.applicationContext) { status ->
        isInitialized = (status == TextToSpeech.SUCCESS)
        if (isInitialized) {
          tts?.language = Locale.US
          tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
              onSpeakingDone?.invoke()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
          })
        }
      }
    }
  }

  override fun speak(text: String, onDone: (() -> Unit)?) {
    if (isInitialized && text.isNotBlank()) {
      onSpeakingDone = onDone
      val cleanText = cleanMarkdown(text)
      tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "echo_tts")
    } else {
      // Engine not ready or empty text — invoke callback immediately so voice flow continues.
      onDone?.invoke()
    }
  }

  override fun stop() {
    tts?.stop()
    onSpeakingDone = null
  }

  override fun shutdown() {
    tts?.stop()
    tts?.shutdown()
    tts = null
    isInitialized = false
    onSpeakingDone = null
  }

  override fun isReady(): Boolean = isInitialized
}

fun cleanMarkdown(text: String): String {
  return text
    .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
    .replace(Regex("\\*(.*?)\\*"), "$1")
    .replace(Regex("#{1,6}\\s"), "")
    .replace(Regex("```[\\s\\S]*?```"), "")
    .replace(Regex("`(.*?)`"), "$1")
    .trim()
}
