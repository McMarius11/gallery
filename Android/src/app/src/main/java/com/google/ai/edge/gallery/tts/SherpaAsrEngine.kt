package com.google.ai.edge.gallery.tts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

private const val TAG = "SherpaAsrEngine"
private const val SAMPLE_RATE = 16000
private const val SILENCE_THRESHOLD_RMS = 400f
private const val SILENCE_DURATION_MS = 1500L
private const val MIN_SPEECH_DURATION_MS = 300L

private const val PREFS_NAME = "sherpa_asr_prefs"
private const val KEY_INIT_CANARY = "init_in_progress"
private const val KEY_RECOGNIZE_CANARY = "recognize_in_progress"
private const val KEY_CANARY_TIMESTAMP = "canary_set_at"
private const val KEY_CRASH_COUNT = "native_crash_count"
private const val MAX_CRASH_COUNT = 1
// If the canary was set more than 30s ago, it was likely an OOM kill, not a SIGABRT
private const val CANARY_TIMEOUT_MS = 30_000L

class SherpaAsrEngine(private val context: Context) {

  companion object {
    /**
     * Quick check if ASR is blocked by previous native crashes, without
     * creating a full engine instance. Used by ConversationLoopController.
     */
    fun isBlockedByCrashHistory(context: Context): Boolean {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      return prefs.getInt(KEY_CRASH_COUNT, 0) >= MAX_CRASH_COUNT
    }
  }
  private var recognizer: OfflineRecognizer? = null
  private var audioRecord: AudioRecord? = null
  private var recordingJob: Job? = null
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var isListening = false
  private var initFailed = false

  private val prefs by lazy {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }

  /**
   * Whether ASR can be used. Checks crash history, model readiness, and permission.
   */
  fun isAvailable(): Boolean {
    return !initFailed &&
      !isBlockedByPreviousCrash() &&
      AsrModelManager.checkModelReady(context) &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
  }

  /**
   * Check if a previous JNI call (init or recognize) crashed the process.
   * Uses canary flags that are set before dangerous calls and cleared after.
   * If the process died (SIGABRT), the canary remains set on next start.
   */
  private fun isBlockedByPreviousCrash(): Boolean {
    val initCanary = prefs.getBoolean(KEY_INIT_CANARY, false)
    val recognizeCanary = prefs.getBoolean(KEY_RECOGNIZE_CANARY, false)
    val crashCount = prefs.getInt(KEY_CRASH_COUNT, 0)

    if (initCanary || recognizeCanary) {
      val canaryAge = System.currentTimeMillis() - prefs.getLong(KEY_CANARY_TIMESTAMP, 0)

      // Clear canary flags regardless
      prefs.edit()
        .putBoolean(KEY_INIT_CANARY, false)
        .putBoolean(KEY_RECOGNIZE_CANARY, false)
        .apply()

      if (canaryAge > CANARY_TIMEOUT_MS) {
        // Canary was set too long ago — likely an OOM kill or normal app restart,
        // not a SIGABRT (which kills the process within milliseconds).
        val source = if (initCanary) "init" else "recognize"
        Log.w(TAG, "Canary from $source was ${canaryAge}ms old, likely OOM kill — not counting as crash")
      } else {
        // Genuine native crash (SIGABRT) — process died within seconds of the JNI call
        val newCount = crashCount + 1
        prefs.edit().putInt(KEY_CRASH_COUNT, newCount).apply()
        val source = if (initCanary) "init" else "recognize"
        Log.e(TAG, "Detected native crash during $source (${canaryAge}ms ago, crash #$newCount)")

        if (newCount >= MAX_CRASH_COUNT) {
          Log.e(TAG, "Too many native crashes ($newCount), disabling ASR. Model files deleted for re-download.")
          AsrModelManager.deleteModelFiles(context)
          return true
        }
      }
    }

    return crashCount >= MAX_CRASH_COUNT
  }

  /**
   * Reset the crash counter, e.g. after model files are re-downloaded.
   */
  fun resetCrashState() {
    prefs.edit()
      .putBoolean(KEY_INIT_CANARY, false)
      .putBoolean(KEY_RECOGNIZE_CANARY, false)
      .putLong(KEY_CANARY_TIMESTAMP, 0)
      .putInt(KEY_CRASH_COUNT, 0)
      .apply()
    initFailed = false
  }

  fun init(): Boolean {
    if (recognizer != null) return true
    if (initFailed || isBlockedByPreviousCrash()) {
      Log.e(TAG, "Init blocked: initFailed=$initFailed, crash count=${prefs.getInt(KEY_CRASH_COUNT, 0)}")
      return false
    }
    if (!AsrModelManager.checkModelReady(context)) {
      Log.e(TAG, "ASR model not downloaded or corrupt")
      return false
    }

    val modelDir = AsrModelManager.getModelDir(context).absolutePath

    // Verify all model files exist and are readable before calling JNI.
    // A native SIGABRT from newFromFile() cannot be caught by try/catch and kills the process.
    val encoderFile = File("$modelDir/small-encoder.int8.onnx")
    val decoderFile = File("$modelDir/small-decoder.int8.onnx")
    val tokensFile = File("$modelDir/small-tokens.txt")

    if (!encoderFile.canRead() || !decoderFile.canRead() || !tokensFile.canRead()) {
      Log.e(TAG, "Model files not readable: encoder=${encoderFile.canRead()}, decoder=${decoderFile.canRead()}, tokens=${tokensFile.canRead()}")
      initFailed = true
      return false
    }

    Log.w(TAG, "Model files validated: encoder=${encoderFile.length()}B, decoder=${decoderFile.length()}B, tokens=${tokensFile.length()}B")

    // Set canary BEFORE the dangerous JNI call. If the process dies from SIGABRT,
    // this flag will still be set on next start and we'll know init crashed.
    prefs.edit().putBoolean(KEY_INIT_CANARY, true).putLong(KEY_CANARY_TIMESTAMP, System.currentTimeMillis()).commit()

    return try {
      val config = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0.0f),
        modelConfig = OfflineModelConfig(
          whisper = OfflineWhisperModelConfig(
            encoder = encoderFile.absolutePath,
            decoder = decoderFile.absolutePath,
            task = "transcribe",
          ),
          tokens = tokensFile.absolutePath,
          numThreads = 4,
          provider = "cpu",
        ),
        decodingMethod = "greedy_search",
      )

      recognizer = OfflineRecognizer(config = config)

      // Init succeeded — clear the canary and reset crash count
      prefs.edit()
        .putBoolean(KEY_INIT_CANARY, false)
        .putInt(KEY_CRASH_COUNT, 0)
        .apply()

      Log.w(TAG, "Whisper ASR recognizer initialized successfully")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize Whisper ASR: ${e.message}", e)
      prefs.edit().putBoolean(KEY_INIT_CANARY, false).apply()
      initFailed = true
      // Model files may be corrupt - delete them so they'll be re-downloaded
      AsrModelManager.deleteModelFiles(context)
      false
    }
  }

  fun startListening(
    onAmplitudeChanged: (Int) -> Unit,
    onResult: (String) -> Unit,
    onError: (Int) -> Unit,
  ) {
    if (isListening) {
      Log.w(TAG, "Already listening, ignoring startListening()")
      return
    }

    if (recognizer == null && !init()) {
      Log.e(TAG, "Cannot start: recognizer not initialized")
      onError(5) // ERROR_CLIENT
      return
    }

    isListening = true

    recordingJob = scope.launch {
      try {
        recordAndRecognize(onAmplitudeChanged, onResult, onError)
      } catch (e: Exception) {
        Log.e(TAG, "Recording error: ${e.message}", e)
        withContext(Dispatchers.Main) {
          onError(5)
        }
      } finally {
        isListening = false
      }
    }
  }

  fun stopListening() {
    isListening = false
    recordingJob?.cancel()
    recordingJob = null
    try {
      audioRecord?.stop()
    } catch (_: Exception) {}
    try {
      audioRecord?.release()
    } catch (_: Exception) {}
    audioRecord = null
  }

  fun free() {
    stopListening()
    recognizer?.free()
    recognizer = null
  }

  @android.annotation.SuppressLint("MissingPermission") // Permission checked by caller (HoldToDictateViewModel)
  private suspend fun recordAndRecognize(
    onAmplitudeChanged: (Int) -> Unit,
    onResult: (String) -> Unit,
    onError: (Int) -> Unit,
  ) {
    val bufferSize = AudioRecord.getMinBufferSize(
      SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(SAMPLE_RATE) // at least 1 second buffer

    val record = AudioRecord(
      MediaRecorder.AudioSource.MIC,
      SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      bufferSize * 2,
    )

    if (record.state != AudioRecord.STATE_INITIALIZED) {
      Log.e(TAG, "AudioRecord failed to initialize")
      withContext(Dispatchers.Main) { onError(5) }
      return
    }

    audioRecord = record
    record.startRecording()
    Log.w(TAG, "Recording started")

    val shortBuffer = ShortArray(SAMPLE_RATE / 10) // 100ms chunks
    // Pre-allocate for up to 30 seconds of audio. Grows if needed.
    val maxSamples = SAMPLE_RATE * 30
    var audioBuffer = FloatArray(maxSamples)
    var totalSamples = 0
    var silenceDurationMs = 0L
    var hasSpeech = false
    var speechDurationMs = 0L
    val chunkDurationMs = (shortBuffer.size * 1000L) / SAMPLE_RATE

    while (isListening && coroutineContext[Job]?.isActive != false) {
      val readCount = record.read(shortBuffer, 0, shortBuffer.size)
      if (readCount <= 0) continue

      // Grow buffer if needed
      if (totalSamples + readCount > audioBuffer.size) {
        audioBuffer = audioBuffer.copyOf(audioBuffer.size * 2)
      }

      // Convert short samples to float [-1, 1] directly into buffer
      var sumSquares = 0.0
      for (i in 0 until readCount) {
        val s = shortBuffer[i]
        audioBuffer[totalSamples + i] = s / 32768.0f
        sumSquares += s.toDouble() * s.toDouble()
      }
      totalSamples += readCount

      // RMS amplitude
      val rms = sqrt(sumSquares / readCount).toFloat()
      val amplitude = ((rms / 8000f) * 65535f).toInt().coerceIn(0, 65535)
      withContext(Dispatchers.Main) {
        onAmplitudeChanged(amplitude)
      }

      // Silence detection
      if (rms < SILENCE_THRESHOLD_RMS) {
        silenceDurationMs += chunkDurationMs
      } else {
        silenceDurationMs = 0
        if (!hasSpeech) {
          hasSpeech = true
          Log.w(TAG, "Speech detected")
        }
      }

      if (hasSpeech) {
        speechDurationMs += chunkDurationMs
      }

      // End of speech: silence after speech
      if (hasSpeech && speechDurationMs > MIN_SPEECH_DURATION_MS &&
        silenceDurationMs >= SILENCE_DURATION_MS) {
        Log.w(TAG, "Silence detected after ${speechDurationMs}ms speech, processing…")
        break
      }
    }

    // Stop recording
    try {
      record.stop()
      record.release()
    } catch (_: Exception) {}
    audioRecord = null

    if (!hasSpeech || totalSamples < SAMPLE_RATE / 2) {
      Log.w(TAG, "No speech detected or too short ($totalSamples samples)")
      withContext(Dispatchers.Main) {
        onResult("")
      }
      return
    }

    // Run Whisper recognition on collected audio
    Log.w(TAG, "Running Whisper on $totalSamples samples (${totalSamples / SAMPLE_RATE}s)")

    val rec = recognizer ?: run {
      withContext(Dispatchers.Main) { onError(5) }
      return
    }

    // Set canary before dangerous JNI calls (decode/getResult can also SIGABRT)
    prefs.edit().putBoolean(KEY_RECOGNIZE_CANARY, true).putLong(KEY_CANARY_TIMESTAMP, System.currentTimeMillis()).commit()

    try {
      val samples = audioBuffer.copyOf(totalSamples)
      val stream = rec.createStream()
      stream.acceptWaveform(samples, SAMPLE_RATE)
      rec.decode(stream)
      val result = rec.getResult(stream)
      stream.free()

      // Recognition succeeded — clear canary
      prefs.edit().putBoolean(KEY_RECOGNIZE_CANARY, false).apply()

      val text = result.text.trim()
      Log.w(TAG, "Recognized: \"$text\" (lang=${result.lang})")

      withContext(Dispatchers.Main) {
        onResult(text)
      }
    } catch (e: Exception) {
      prefs.edit().putBoolean(KEY_RECOGNIZE_CANARY, false).apply()
      Log.e(TAG, "Whisper recognition failed: ${e.message}", e)
      // Reset recognizer on failure - it may be in a bad state
      recognizer = null
      withContext(Dispatchers.Main) { onError(5) }
    }
  }
}
