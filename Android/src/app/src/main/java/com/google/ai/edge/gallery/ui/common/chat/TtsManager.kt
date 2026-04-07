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
import android.util.Log
import com.google.ai.edge.gallery.tts.AndroidTtsEngine
import com.google.ai.edge.gallery.tts.KokoroModelManager
import com.google.ai.edge.gallery.tts.KokoroModelStatus
import com.google.ai.edge.gallery.tts.KokoroTtsEngine
import com.google.ai.edge.gallery.tts.TtsEngine
import com.google.ai.edge.gallery.ui.home.getSavedVoiceId

private const val TAG = "TtsManager"

object TtsManager {
  private var engine: TtsEngine = AndroidTtsEngine()
  private var kokoroInitialized = false

  /** Called when TTS finishes speaking an utterance. Set this to auto-restart listening. */
  var onSpeakingDone: (() -> Unit)?
    get() = engine.onSpeakingDone
    set(value) { engine.onSpeakingDone = value }

  fun init(context: Context) {
    engine.init(context)
  }

  /**
   * Ensure Kokoro TTS is initialized exactly once. Safe to call from multiple places.
   * Downloads the model if needed, creates the engine, and swaps it into TtsManager.
   */
  suspend fun ensureKokoroEngine(context: Context) {
    if (kokoroInitialized && getAvailableVoices().isNotEmpty()) {
      return
    }

    synchronized(this) {
      if (kokoroInitialized && getAvailableVoices().isNotEmpty()) return
    }

    Log.w(TAG, "Initializing Kokoro TTS engine...")
    KokoroModelManager.ensureModelReady(context)

    if (KokoroModelManager.status.value != KokoroModelStatus.READY) {
      Log.w(TAG, "Kokoro model not ready: ${KokoroModelManager.status.value}")
      return
    }

    synchronized(this) {
      // Double-check after model download
      if (kokoroInitialized && getAvailableVoices().isNotEmpty()) return

      val kokoroEngine = KokoroTtsEngine()
      kokoroEngine.init(context)
      if (kokoroEngine.isReady()) {
        setEngine(kokoroEngine)
        kokoroInitialized = true
        // Restore persisted voice selection
        val savedVoice = getSavedVoiceId(context)
        kokoroEngine.setVoice(savedVoice)
        Log.w(TAG, "Kokoro TTS engine set successfully, voice=$savedVoice")
      } else {
        Log.e(TAG, "Kokoro TTS engine failed to initialize")
      }
    }
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

  fun getAvailableVoices(): List<Pair<Int, String>> = engine.getAvailableVoices()

  fun setVoice(id: Int) = engine.setVoice(id)
}
