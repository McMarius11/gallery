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
import com.google.ai.edge.gallery.tts.AndroidTtsEngine
import com.google.ai.edge.gallery.tts.TtsEngine

object TtsManager {
  private var engine: TtsEngine = AndroidTtsEngine()

  /** Called when TTS finishes speaking an utterance. Set this to auto-restart listening. */
  var onSpeakingDone: (() -> Unit)?
    get() = engine.onSpeakingDone
    set(value) { engine.onSpeakingDone = value }

  fun init(context: Context) {
    engine.init(context)
  }

  fun setEngine(newEngine: TtsEngine) {
    engine.shutdown()
    engine = newEngine
  }

  fun speak(text: String, onDone: (() -> Unit)? = null) {
    engine.speak(text, onDone)
  }

  fun stop() {
    engine.stop()
  }

  fun shutdown() {
    engine.shutdown()
  }

  fun isReady(): Boolean = engine.isReady()
}
