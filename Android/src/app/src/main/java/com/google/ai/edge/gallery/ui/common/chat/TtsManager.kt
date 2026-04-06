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

package com.google.ai.edge.gallery.ui.common.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

object TtsManager {
  private var tts: TextToSpeech? = null
  private var isInitialized = false

  fun init(context: Context) {
    if (tts == null) {
      tts = TextToSpeech(context.applicationContext) { status ->
        isInitialized = (status == TextToSpeech.SUCCESS)
        if (isInitialized) {
          tts?.language = Locale.US
        }
      }
    }
  }

  fun speak(text: String) {
    if (isInitialized && text.isNotBlank()) {
      // Strip basic markdown formatting before speaking.
      val cleanText = text
        .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
        .replace(Regex("\\*(.*?)\\*"), "$1")
        .replace(Regex("#{1,6}\\s"), "")
        .replace(Regex("```[\\s\\S]*?```"), "")
        .replace(Regex("`(.*?)`"), "$1")
        .trim()
      tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "echo_tts")
    }
  }

  fun stop() {
    tts?.stop()
  }

  fun shutdown() {
    tts?.stop()
    tts?.shutdown()
    tts = null
    isInitialized = false
  }
}
