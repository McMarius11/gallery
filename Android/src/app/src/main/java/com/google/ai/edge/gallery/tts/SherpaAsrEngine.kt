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

class SherpaAsrEngine(private val context: Context) {
  private var recognizer: OfflineRecognizer? = null
  private var audioRecord: AudioRecord? = null
  private var recordingJob: Job? = null
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var isListening = false

  fun isAvailable(): Boolean {
    return AsrModelManager.checkModelReady(context) &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
  }

  fun init(): Boolean {
    if (recognizer != null) return true
    if (!AsrModelManager.checkModelReady(context)) {
      Log.e(TAG, "ASR model not downloaded yet")
      return false
    }

    val modelDir = AsrModelManager.getModelDir(context).absolutePath

    return try {
      val config = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
          whisper = OfflineWhisperModelConfig(
            encoder = "$modelDir/small-encoder.int8.onnx",
            decoder = "$modelDir/small-decoder.int8.onnx",
            task = "transcribe",
          ),
          tokens = "$modelDir/small-tokens.txt",
          numThreads = 4,
          provider = "cpu",
        ),
        decodingMethod = "greedy_search",
      )

      recognizer = OfflineRecognizer(config = config)
      Log.w(TAG, "Whisper ASR recognizer initialized successfully")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize Whisper ASR: ${e.message}", e)
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

    val samples = audioBuffer.copyOf(totalSamples)
    val stream = rec.createStream()
    stream.acceptWaveform(samples, SAMPLE_RATE)
    rec.decode(stream)
    val result = rec.getResult(stream)
    stream.free()

    val text = result.text.trim()
    Log.w(TAG, "Recognized: \"$text\" (lang=${result.lang})")

    withContext(Dispatchers.Main) {
      onResult(text)
    }
  }
}
