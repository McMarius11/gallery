package com.google.ai.edge.gallery.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "KokoroTtsEngine"
private const val SAMPLE_RATE = 22050
private const val SPEAKER_ID = 0 // Default voice

class KokoroTtsEngine : TtsEngine {
  private var offlineTts: com.k2fsa.sherpa.onnx.OfflineTts? = null
  private var audioTrack: AudioTrack? = null
  private var playbackJob: Job? = null
  private var scope: CoroutineScope? = null
  private var initialized = false

  override var onSpeakingDone: (() -> Unit)? = null

  override fun init(context: Context) {
    val modelDir = KokoroModelManager.getModelDir(context)
    if (!File(modelDir, "model.onnx").exists()) {
      Log.w(TAG, "Kokoro model not found")
      return
    }

    try {
      val config = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
        model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
          kokoro = com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig(
            model = File(modelDir, "model.onnx").absolutePath,
            voices = File(modelDir, "voices.bin").absolutePath,
            tokens = File(modelDir, "tokens.txt").absolutePath,
            dataDir = File(modelDir, "espeak-ng-data").absolutePath,
            lengthScale = 1.0f,
          ),
          numThreads = 2,
          debug = false,
        ),
      )
      offlineTts = com.k2fsa.sherpa.onnx.OfflineTts(config = config)
      scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
      initialized = true
      Log.d(TAG, "Kokoro TTS initialized successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize Kokoro TTS", e)
      initialized = false
    }
  }

  override fun speak(text: String, onDone: (() -> Unit)?) {
    if (!initialized || text.isBlank()) return

    onSpeakingDone = onDone
    stop()

    playbackJob = scope?.launch {
      try {
        val cleanText = cleanMarkdown(text)
        // Split into sentences for lower latency
        val sentences = splitIntoSentences(cleanText)

        val track = createAudioTrack()
        audioTrack = track
        track.play()

        for (sentence in sentences) {
          if (!isActive) break
          if (sentence.isBlank()) continue

          val audio = offlineTts?.generateWithCallback(
            text = sentence,
            sid = SPEAKER_ID,
            speed = 1.0f,
            callback = { samples ->
              if (!isActive) return@generateWithCallback 0
              track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
              return@generateWithCallback 1
            },
          )
        }

        // Wait for playback to finish
        if (isActive) {
          track.stop()
        }
        track.release()
        audioTrack = null

        if (isActive) {
          onSpeakingDone?.invoke()
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error during Kokoro TTS playback", e)
        audioTrack?.release()
        audioTrack = null
      }
    }
  }

  override fun stop() {
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
    stop()
    scope?.cancel()
    scope = null
    offlineTts?.release()
    offlineTts = null
    initialized = false
  }

  override fun isReady(): Boolean = initialized

  private fun createAudioTrack(): AudioTrack {
    val bufferSize = AudioTrack.getMinBufferSize(
      SAMPLE_RATE,
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
          .setSampleRate(SAMPLE_RATE)
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
