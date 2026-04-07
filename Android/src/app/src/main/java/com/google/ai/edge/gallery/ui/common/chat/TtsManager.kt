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
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "TtsManager"
private const val PREFS_TTS = "tts_prefs"
private const val PREF_VOICE_ID = "kokoro_voice_id"

object TtsManager {
  @Volatile private var engine: TtsEngine = AndroidTtsEngine()
  @Volatile private var kokoroInitialized = false

  /** Serializes all init/shutdown operations to prevent double native allocation. */
  private val initMutex = Mutex()

  /** Counts how many times ensureKokoroEngine created a native engine (should be 1). */
  private val initCount = AtomicInteger(0)

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

  /** Write debug trace that survives process death. */
  private fun crashLog(context: Context, msg: String) {
    try {
      val file = java.io.File(context.filesDir, "tts_crash_trace.txt")
      val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        .format(java.util.Date())
      file.appendText("$ts [TtsManager] $msg\n")
      if (file.length() > 50_000) {
        val lines = file.readLines().takeLast(100)
        file.writeText(lines.joinToString("\n") + "\n")
      }
    } catch (_: Exception) {}
  }

  /**
   * Ensure Kokoro TTS is initialized exactly once. Safe to call from multiple places.
   * Downloads the model if needed, creates the engine, and swaps it into TtsManager.
   * Uses a Mutex so concurrent callers are serialized (only one init at a time).
   *
   * @param caller Debug tag identifying which code path is calling (for crash traces)
   */
  suspend fun ensureKokoroEngine(context: Context, caller: String = "unknown") {
    val appCtx = context.applicationContext
    // Quick volatile check — skip mutex entirely if already ready
    if (isKokoroLive()) {
      Log.d(TAG, "ensureKokoroEngine($caller): already live, skipping")
      return
    }

    crashLog(appCtx, "ensureKokoroEngine($caller): isKokoroLive=false, waiting for mutex (locked=${initMutex.isLocked})")
    Log.w(TAG, "ensureKokoroEngine($caller): waiting for mutex (locked=${initMutex.isLocked})")

    initMutex.withLock {
      // Double-check inside mutex
      if (isKokoroLive()) {
        crashLog(appCtx, "ensureKokoroEngine($caller): live inside mutex, skipping")
        Log.d(TAG, "ensureKokoroEngine($caller): already live inside mutex, skipping")
        return
      }

      crashLog(appCtx, "ensureKokoroEngine($caller): mutex acquired, kokoroInit=$kokoroInitialized, engine=${engine.javaClass.simpleName}, isReady=${engine.isReady()}")
      Log.w(TAG, "ensureKokoroEngine($caller): Initializing Kokoro TTS engine...")
      KokoroModelManager.ensureModelReady(appCtx)

      if (KokoroModelManager.status.value != KokoroModelStatus.READY) {
        crashLog(appCtx, "ensureKokoroEngine($caller): model not ready: ${KokoroModelManager.status.value}")
        Log.w(TAG, "ensureKokoroEngine($caller): Kokoro model not ready: ${KokoroModelManager.status.value}")
        return
      }

      val count = initCount.incrementAndGet()
      crashLog(appCtx, "ensureKokoroEngine($caller): creating KokoroTtsEngine #$count")
      Log.w(TAG, "ensureKokoroEngine($caller): creating KokoroTtsEngine #$count")

      val kokoroEngine = KokoroTtsEngine()
      kokoroEngine.init(appCtx)

      if (kokoroEngine.isReady()) {
        crashLog(appCtx, "ensureKokoroEngine($caller): engine #$count ready, shutting down old engine (${engine.javaClass.simpleName})")
        engine.shutdown()
        engine = kokoroEngine
        kokoroInitialized = true
        val savedVoice = getSavedVoiceId(appCtx)
        engine.setVoice(savedVoice)
        crashLog(appCtx, "ensureKokoroEngine($caller): engine #$count set as active, voice=$savedVoice")
        Log.w(TAG, "ensureKokoroEngine($caller): Kokoro TTS engine #$count set successfully, voice=$savedVoice")
      } else {
        crashLog(appCtx, "ensureKokoroEngine($caller): engine #$count FAILED to initialize, shutting down")
        Log.e(TAG, "ensureKokoroEngine($caller): Kokoro TTS engine #$count failed to initialize")
        kokoroEngine.shutdown()
      }
    }
  }

  fun setEngine(newEngine: TtsEngine) {
    Log.w(TAG, "setEngine: ${engine.javaClass.simpleName} → ${newEngine.javaClass.simpleName}")
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
    Log.w(TAG, "shutdown() called, engine=${engine.javaClass.simpleName}, kokoroInit=$kokoroInitialized, caller=${Throwable().stackTrace.drop(1).take(3).joinToString(" <- ") { "${it.fileName}:${it.lineNumber}" }}")
    engine.shutdown()
    engine = AndroidTtsEngine()  // Reset to safe default so init() won't re-init a dead KokoroTtsEngine
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
