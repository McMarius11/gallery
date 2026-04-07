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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "TtsManager"
private const val PREFS_TTS = "tts_prefs"
private const val PREF_VOICE_ID = "kokoro_voice_id"

object TtsManager {
  @Volatile private var engine: TtsEngine = AndroidTtsEngine()
  @Volatile private var kokoroInitialized = false

  /** Serializes all init/shutdown operations to prevent double native allocation. */
  private val initMutex = Mutex()

  /** Called when TTS finishes speaking an utterance. Set this to auto-restart listening. */
  var onSpeakingDone: (() -> Unit)?
    get() = engine.onSpeakingDone
    set(value) { engine.onSpeakingDone = value }

  fun init(context: Context) {
    engine.init(context)
  }

  /** Check if Kokoro engine is live and usable. */
  private fun isKokoroLive(): Boolean {
    return kokoroInitialized && engine is KokoroTtsEngine && engine.isReady()
  }

  /**
   * Ensure Kokoro TTS is initialized exactly once. Safe to call from multiple places.
   * Downloads the model if needed, creates the engine, and swaps it into TtsManager.
   * Uses a Mutex so concurrent callers are serialized (only one init at a time).
   */
  suspend fun ensureKokoroEngine(context: Context) {
    // Quick volatile check — skip mutex entirely if already ready
    if (isKokoroLive()) return

    initMutex.withLock {
      // Double-check inside mutex
      if (isKokoroLive()) return

      Log.w(TAG, "Initializing Kokoro TTS engine...")
      KokoroModelManager.ensureModelReady(context)

      if (KokoroModelManager.status.value != KokoroModelStatus.READY) {
        Log.w(TAG, "Kokoro model not ready: ${KokoroModelManager.status.value}")
        return
      }

      val kokoroEngine = KokoroTtsEngine()
      kokoroEngine.init(context)
      if (kokoroEngine.isReady()) {
        engine.shutdown()
        engine = kokoroEngine
        kokoroInitialized = true
        // Restore persisted voice selection
        val savedVoice = getSavedVoiceId(context)
        engine.setVoice(savedVoice)
        Log.w(TAG, "Kokoro TTS engine set successfully, voice=$savedVoice")
      } else {
        Log.e(TAG, "Kokoro TTS engine failed to initialize")
        kokoroEngine.shutdown()
      }
    }
  }

  fun setEngine(newEngine: TtsEngine) {
    engine.shutdown()
    engine = newEngine
    kokoroInitialized = newEngine is KokoroTtsEngine && newEngine.isReady()
  }

  fun speak(text: String, onDone: (() -> Unit)? = null) {
    engine.speak(text, onDone)
  }

  fun stop() {
    engine.stop()
  }

  fun shutdown() {
    engine.shutdown()
    kokoroInitialized = false
  }

  fun isReady(): Boolean = engine.isReady()

  fun getAvailableVoices(): List<Pair<Int, String>> = engine.getAvailableVoices()

  fun setVoice(id: Int) = engine.setVoice(id)

  /** Set voice and persist the choice. */
  fun setVoiceAndSave(context: Context, id: Int) {
    engine.setVoice(id)
    saveVoiceId(context, id)
  }

  fun getSavedVoiceId(context: Context): Int {
    return context.getSharedPreferences(PREFS_TTS, Context.MODE_PRIVATE)
      .getInt(PREF_VOICE_ID, 0)
  }

  private fun saveVoiceId(context: Context, id: Int) {
    context.getSharedPreferences(PREFS_TTS, Context.MODE_PRIVATE)
      .edit().putInt(PREF_VOICE_ID, id).apply()
  }
}
