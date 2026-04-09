package com.google.ai.edge.gallery.tts

import android.app.ActivityManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "KokoroTtsEngine"
private val instanceCounter = java.util.concurrent.atomic.AtomicInteger(0)

private const val PREFS_NAME = "kokoro_tts_prefs"
private const val KEY_TTS_INIT_CANARY = "tts_init_in_progress"
private const val KEY_TTS_SPEAK_CANARY = "tts_speak_in_progress"
private const val KEY_TTS_CANARY_TIMESTAMP = "tts_canary_set_at"
private const val KEY_TTS_CRASH_COUNT = "tts_native_crash_count"
private const val KEY_TTS_LAST_SENTENCE = "tts_last_sentence"
private const val MAX_TTS_CRASH_COUNT = 2
// If the canary was set more than 30s ago, it was likely an OOM kill, not a SIGABRT
private const val CANARY_TIMEOUT_MS = 30_000L

class KokoroTtsEngine : TtsEngine {
  private val instanceId = instanceCounter.incrementAndGet()
  @Volatile private var offlineTts: OfflineTts? = null
  private var speakerId: Int = 0
  @Volatile private var audioTrack: AudioTrack? = null
  private var playbackJob: Job? = null
  private var scope: CoroutineScope? = null
  @Volatile private var initialized = false
  private var sampleRate = 22050
  @Volatile private var stopped = false
  private var appContext: Context? = null
  @Volatile private var initFailed = false

  override var onSpeakingDone: (() -> Unit)? = null

  companion object {
    /**
     * Quick check if TTS is blocked by previous native crashes, without
     * creating a full engine instance. Used by TtsManager and ConversationLoopController.
     */
    fun isBlockedByCrashHistory(context: Context): Boolean {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      return prefs.getInt(KEY_TTS_CRASH_COUNT, 0) >= MAX_TTS_CRASH_COUNT
    }

    /**
     * Reset the crash counter and canary flags, e.g. after model files are re-downloaded.
     */
    fun resetCrashState(context: Context) {
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_TTS_INIT_CANARY, false)
        .putBoolean(KEY_TTS_SPEAK_CANARY, false)
        .putLong(KEY_TTS_CANARY_TIMESTAMP, 0)
        .putInt(KEY_TTS_CRASH_COUNT, 0)
        .putString(KEY_TTS_LAST_SENTENCE, "")
        .apply()
      Log.w(TAG, "Crash state reset")
    }

    val VOICE_NAMES = mapOf(
      0 to "Alloy (Female)",
      1 to "Bella (Female)",
      2 to "Nicole (Female)",
      3 to "Sarah (Female)",
      4 to "Sky (Female)",
      5 to "Adam (Male)",
      6 to "Michael (Male)",
      7 to "Emma (British F)",
      8 to "Isabella (British F)",
      9 to "George (British M)",
      10 to "Lewis (British M)",
    )
  }

  /** Write to a file that survives process death, for crash debugging. */
  private fun crashLog(msg: String) {
    try {
      val ctx = appContext ?: return
      val file = java.io.File(ctx.filesDir, "tts_crash_trace.txt")
      file.appendText("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg\n")
      // Keep file small — truncate if > 50KB
      if (file.length() > 50_000) {
        val lines = file.readLines().takeLast(100)
        file.writeText(lines.joinToString("\n") + "\n")
      }
    } catch (_: Exception) {}
  }

  /**
   * Check if a previous JNI call (init or speak) crashed the process.
   * Uses canary flags that are set before dangerous calls and cleared after.
   * If the process died (SIGABRT), the canary remains set on next start.
   */
  private fun isBlockedByPreviousCrash(): Boolean {
    val ctx = appContext ?: return false
    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val initCanary = prefs.getBoolean(KEY_TTS_INIT_CANARY, false)
    val speakCanary = prefs.getBoolean(KEY_TTS_SPEAK_CANARY, false)
    val crashCount = prefs.getInt(KEY_TTS_CRASH_COUNT, 0)

    if (initCanary || speakCanary) {
      val canaryAge = System.currentTimeMillis() - prefs.getLong(KEY_TTS_CANARY_TIMESTAMP, 0)

      // Clear canary flags regardless
      prefs.edit()
        .putBoolean(KEY_TTS_INIT_CANARY, false)
        .putBoolean(KEY_TTS_SPEAK_CANARY, false)
        .apply()

      if (canaryAge > CANARY_TIMEOUT_MS) {
        // Canary was set too long ago — likely an OOM kill or normal app restart,
        // not a SIGABRT (which kills the process within milliseconds).
        val source = if (initCanary) "init" else "speak"
        Log.w(TAG, "Canary from $source was ${canaryAge}ms old, likely OOM kill — not counting as crash")
        crashLog("Canary from $source aged ${canaryAge}ms — likely OOM, not SIGABRT")
      } else {
        // Genuine native crash (SIGABRT) — process died within seconds of the JNI call
        val newCount = crashCount + 1
        val source = if (initCanary) "init" else "speak"
        val lastSentence = prefs.getString(KEY_TTS_LAST_SENTENCE, "") ?: ""
        prefs.edit().putInt(KEY_TTS_CRASH_COUNT, newCount).apply()
        Log.e(TAG, "Detected native crash during $source (${canaryAge}ms ago, crash #$newCount, lastSentence=\"${lastSentence.take(80)}\")")
        crashLog("NATIVE CRASH detected during $source (${canaryAge}ms ago, crash #$newCount, lastSentence=\"${lastSentence.take(80)}\")")

        if (newCount >= MAX_TTS_CRASH_COUNT) {
          Log.e(TAG, "Too many native TTS crashes ($newCount), disabling Kokoro TTS. Model files deleted for re-download.")
          crashLog("TTS DISABLED after $newCount crashes. Deleting model files.")
          KokoroModelManager.deleteModelFiles(ctx)
          return true
        }
      }
    }

    return crashCount >= MAX_TTS_CRASH_COUNT
  }

  /** Log device memory info for OOM vs SIGABRT diagnosis. */
  private fun logMemoryInfo() {
    try {
      val ctx = appContext ?: return
      val runtime = Runtime.getRuntime()
      val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
      val memInfo = ActivityManager.MemoryInfo()
      am?.getMemoryInfo(memInfo)
      val msg = "Memory: JVM free=${runtime.freeMemory() / 1024}KB, " +
        "total=${runtime.totalMemory() / 1024}KB, max=${runtime.maxMemory() / 1024}KB | " +
        "System avail=${memInfo.availMem / (1024 * 1024)}MB, low=${memInfo.lowMemory}"
      crashLog(msg)
      Log.d(TAG, msg)
    } catch (_: Exception) {}
  }

  override fun init(context: Context) {
    appContext = context.applicationContext
    if (initialized) {
      Log.w(TAG, "Already initialized, skipping")
      return
    }
    if (initFailed) {
      Log.e(TAG, "Init blocked: initFailed=true (previous failure in this session)")
      crashLog("init(#$instanceId) BLOCKED: initFailed=true")
      return
    }
    if (isBlockedByPreviousCrash()) {
      Log.e(TAG, "Init blocked: too many previous native crashes")
      crashLog("init(#$instanceId) BLOCKED: crash history")
      initFailed = true
      return
    }
    if (!KokoroModelManager.checkModelReady(context)) {
      Log.e(TAG, "Kokoro model not ready, refusing to initialize native engine")
      crashLog("init(#$instanceId) ABORTED: model not ready")
      return
    }
    val modelDir = KokoroModelManager.getModelDir(context)

    // Validate all model files are readable before calling JNI.
    // A native SIGABRT from OfflineTts() cannot be caught by try/catch.
    val criticalFiles = listOf("model.onnx", "voices.bin", "tokens.txt",
      "espeak-ng-data/en_dict", "espeak-ng-data/phondata")
    for (f in criticalFiles) {
      val file = File(modelDir, f)
      val size = if (file.exists()) file.length() else -1
      val readable = file.canRead()
      Log.d(TAG, "File: $f = ${if (file.exists()) "$size bytes, readable=$readable" else "MISSING"}")
      if (!readable) {
        Log.e(TAG, "Model file not readable: $f — aborting init")
        crashLog("init(#$instanceId) ABORTED: $f not readable (exists=${file.exists()}, size=$size)")
        initFailed = true
        return
      }
    }
    crashLog("init(#$instanceId): all files validated OK")
    logMemoryInfo()

    // Set canary BEFORE the dangerous JNI call. If the process dies from SIGABRT,
    // this flag will still be set on next start and we'll know init crashed.
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
      .putBoolean(KEY_TTS_INIT_CANARY, true)
      .putLong(KEY_TTS_CANARY_TIMESTAMP, System.currentTimeMillis())
      .commit()  // commit() not apply() — must be on disk before JNI call

    try {
      val config = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
          kokoro = OfflineTtsKokoroModelConfig(
            model = File(modelDir, "model.onnx").absolutePath,
            voices = File(modelDir, "voices.bin").absolutePath,
            tokens = File(modelDir, "tokens.txt").absolutePath,
            dataDir = File(modelDir, "espeak-ng-data").absolutePath,
            lengthScale = 1.0f,
          ),
          numThreads = 1,
          debug = false,
          provider = "cpu",
        ),
      )

      crashLog("init(#$instanceId): calling OfflineTts(config)")
      offlineTts = OfflineTts(config = config)
      sampleRate = offlineTts!!.sampleRate()
      val numSpeakers = offlineTts!!.numSpeakers()

      // Init succeeded — clear the canary and reset crash count
      prefs.edit()
        .putBoolean(KEY_TTS_INIT_CANARY, false)
        .putInt(KEY_TTS_CRASH_COUNT, 0)
        .apply()

      scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
      initialized = true
      crashLog("init(#$instanceId): SUCCESS, sampleRate=$sampleRate, speakers=$numSpeakers")
      Log.d(TAG, "Kokoro TTS #$instanceId initialized, sampleRate=$sampleRate, speakers=$numSpeakers")
    } catch (e: Exception) {
      prefs.edit().putBoolean(KEY_TTS_INIT_CANARY, false).apply()
      crashLog("init(#$instanceId): FAILED ${e.javaClass.simpleName}: ${e.message}")
      Log.e(TAG, "Failed to initialize Kokoro TTS #$instanceId", e)
      initialized = false
      initFailed = true
      // Model files may be corrupt - delete them so they'll be re-downloaded
      KokoroModelManager.deleteModelFiles(context)
    }
  }

  override fun speak(text: String, onDone: (() -> Unit)?) {
    if (!initialized || offlineTts == null || text.isBlank()) {
      Log.w(TAG, "speak() skipped: initialized=$initialized, offlineTts=${offlineTts != null}, blank=${text.isBlank()}")
      onDone?.invoke()
      return
    }

    Log.w(TAG, "speak() called, text length=${text.length}")
    crashLog("speak(#$instanceId) text=${text.take(100)}")
    stop()
    stopped = false
    onSpeakingDone = onDone

    playbackJob = scope?.launch {
      val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      try {
        val cleanText = cleanMarkdown(text)
        val sentences = splitIntoSentences(cleanText)
        crashLog("sentences=${sentences.size}")

        val track = createAudioTrack()
        audioTrack = track
        track.play()
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        crashLog("AudioTrack created: state=${track.state}, playState=${track.playState}, bufferSize=$bufferSize")

        for ((i, sentence) in sentences.withIndex()) {
          if (!isActive || stopped) {
            crashLog("stopped before sentence $i")
            break
          }
          if (sentence.isBlank()) continue

          crashLog("generating sentence $i/${sentences.size}: ${sentence.take(80)}")

          // Set speak canary BEFORE the dangerous JNI call
          prefs?.edit()
            ?.putBoolean(KEY_TTS_SPEAK_CANARY, true)
            ?.putLong(KEY_TTS_CANARY_TIMESTAMP, System.currentTimeMillis())
            ?.putString(KEY_TTS_LAST_SENTENCE, "sentence $i/${sentences.size}: ${sentence.take(200)}")
            ?.commit()  // commit() — must be on disk before JNI

          // Use generate() — generateWithCallback() crashes with SIGABRT in
          // sherpa-onnx v1.12.35 even after fixing JNI threading and callback
          // signature. The crash is in the native C++ Generate() code path
          // when a non-null callback is provided. See gallery#1.
          val audio = offlineTts!!.generate(
            text = sentence,
            sid = speakerId,
            speed = 1.0f,
          )

          // Speak succeeded for this sentence — clear canary
          prefs?.edit()?.putBoolean(KEY_TTS_SPEAK_CANARY, false)?.apply()

          // Write all samples to AudioTrack
          if (isActive && !stopped && audio.samples.isNotEmpty()) {
            crashLog("playing ${audio.samples.size} samples for sentence $i")
            track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
          }
          crashLog("sentence $i done")
        }

        if (isActive) {
          track.stop()
        }
        track.release()
        audioTrack = null
        Log.w(TAG, "All sentences spoken, invoking onDone")

        if (isActive) {
          onSpeakingDone?.invoke()
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error during Kokoro TTS playback", e)
        crashLog("speak(#$instanceId) EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
        prefs?.edit()?.putBoolean(KEY_TTS_SPEAK_CANARY, false)?.apply()
        try {
          audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        // Invoke onDone even on error so ConversationLoop doesn't hang
        onSpeakingDone?.invoke()
      }
    }
  }

  override fun stop() {
    // Set stopped flag FIRST so the native callback sees it before we release the track
    stopped = true
    playbackJob?.cancel()
    playbackJob = null
    try {
      audioTrack?.pause()
      audioTrack?.flush()
      audioTrack?.release()
    } catch (_: Exception) {}
    audioTrack = null
    onSpeakingDone = null
  }

  override fun shutdown() {
    crashLog("shutdown(#$instanceId) called, initialized=$initialized, offlineTts=${offlineTts != null}")
    Log.w(TAG, "shutdown(#$instanceId) called")
    stop()
    scope?.cancel()
    scope = null
    offlineTts?.free()
    offlineTts = null
    initialized = false
  }

  override fun isReady(): Boolean = initialized

  /**
   * Test generate() WITHOUT callback — for diagnostics only.
   * If this works but generateWithCallback() crashes, the issue is the callback mechanism.
   * If this also crashes, the issue is in phonemization or ONNX inference.
   * Returns the number of audio samples generated, or -1 on error.
   */
  fun testGenerateWithoutCallback(text: String): Int {
    if (!initialized || offlineTts == null) return -1
    crashLog("testGenerateWithoutCallback: text=\"${text.take(50)}\"")

    val prefs = appContext?.getSharedPreferences("kokoro_tts_prefs", android.content.Context.MODE_PRIVATE)
    prefs?.edit()
      ?.putBoolean(KEY_TTS_SPEAK_CANARY, true)
      ?.putLong(KEY_TTS_CANARY_TIMESTAMP, System.currentTimeMillis())
      ?.putString(KEY_TTS_LAST_SENTENCE, "TEST_NO_CALLBACK: ${text.take(200)}")
      ?.commit()

    return try {
      val audio = offlineTts!!.generate(text = text, sid = speakerId, speed = 1.0f)
      prefs?.edit()?.putBoolean(KEY_TTS_SPEAK_CANARY, false)?.apply()
      crashLog("testGenerateWithoutCallback: SUCCESS, ${audio.samples.size} samples, sampleRate=${audio.sampleRate}")
      audio.samples.size
    } catch (e: Exception) {
      prefs?.edit()?.putBoolean(KEY_TTS_SPEAK_CANARY, false)?.apply()
      crashLog("testGenerateWithoutCallback: EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
      -1
    }
  }

  override fun getAvailableVoices(): List<Pair<Int, String>> {
    val numSpeakers = offlineTts?.numSpeakers() ?: 0
    return (0 until numSpeakers).map { id ->
      id to (VOICE_NAMES[id] ?: "Voice $id")
    }
  }

  override fun setVoice(id: Int) {
    speakerId = id
    Log.d(TAG, "Voice set to speaker $id (${VOICE_NAMES[id] ?: "unknown"})")
  }

  private fun createAudioTrack(): AudioTrack {
    val bufferSize = AudioTrack.getMinBufferSize(
      sampleRate,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_FLOAT,
    )
    return AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setSampleRate(sampleRate)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
          .build()
      )
      .setBufferSizeInBytes(bufferSize * 2)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
  }

  private fun splitIntoSentences(text: String): List<String> {
    return text.split(Regex("(?<=[.!?])\\s+"))
      .filter { it.isNotBlank() }
  }

}
