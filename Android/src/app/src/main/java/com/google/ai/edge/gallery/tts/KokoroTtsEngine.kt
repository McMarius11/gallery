package com.google.ai.edge.gallery.tts

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

class KokoroTtsEngine : TtsEngine {
  private var offlineTts: OfflineTts? = null
  private var speakerId: Int = 0
  private var audioTrack: AudioTrack? = null
  private var playbackJob: Job? = null
  private var scope: CoroutineScope? = null
  private var initialized = false
  private var sampleRate = 22050
  @Volatile private var stopped = false

  override var onSpeakingDone: (() -> Unit)? = null

  override fun init(context: Context) {
    val modelDir = KokoroModelManager.getModelDir(context)
    if (!File(modelDir, "model.onnx").exists()) {
      Log.w(TAG, "Kokoro model not found at ${modelDir.absolutePath}")
      return
    }

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
          numThreads = 2,
          debug = false,
        ),
      )

      offlineTts = OfflineTts(config = config)
      sampleRate = offlineTts!!.sampleRate()
      scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
      initialized = true
      Log.d(TAG, "Kokoro TTS initialized, sampleRate=$sampleRate")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize Kokoro TTS", e)
      initialized = false
    }
  }

  override fun speak(text: String, onDone: (() -> Unit)?) {
    if (!initialized || text.isBlank()) {
      // Engine not ready or empty text — invoke callback immediately so voice flow continues.
      onDone?.invoke()
      return
    }

    stop()
    stopped = false
    onSpeakingDone = onDone

    playbackJob = scope?.launch {
      try {
        val cleanText = cleanMarkdown(text)
        val sentences = splitIntoSentences(cleanText)

        val track = createAudioTrack()
        audioTrack = track
        track.play()

        for (sentence in sentences) {
          if (!isActive || stopped) break
          if (sentence.isBlank()) continue

          offlineTts?.generateWithCallback(
            text = sentence,
            sid = speakerId,
            speed = 1.0f,
            callback = { samples ->
              // Check stopped flag BEFORE writing to AudioTrack.
              // stop() may have released the track on another thread —
              // writing to a released track throws, and the exception
              // propagates into native code causing SIGABRT.
              if (!isActive || stopped) return@generateWithCallback 0
              try {
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
              } catch (e: Exception) {
                Log.w(TAG, "AudioTrack write failed (likely released): ${e.message}")
                return@generateWithCallback 0
              }
              return@generateWithCallback 1
            },
          )
        }

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
        try {
          audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
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
    stop()
    scope?.cancel()
    scope = null
    offlineTts?.free()
    offlineTts = null
    initialized = false
  }

  override fun isReady(): Boolean = initialized

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

  companion object {
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
}
