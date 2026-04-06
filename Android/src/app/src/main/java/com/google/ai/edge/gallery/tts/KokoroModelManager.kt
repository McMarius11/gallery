package com.google.ai.edge.gallery.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "KokoroModelManager"

enum class KokoroModelStatus {
  NOT_DOWNLOADED,
  DOWNLOADING,
  READY,
  ERROR,
}

object KokoroModelManager {
  private val _status = MutableStateFlow(KokoroModelStatus.NOT_DOWNLOADED)
  val status: StateFlow<KokoroModelStatus> = _status.asStateFlow()

  private val _downloadProgress = MutableStateFlow(0f)
  val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

  private const val MODEL_DIR = "kokoro"
  private const val BASE_URL =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
  private const val MODEL_TAR = "sherpa-onnx-tts-kokoro-en-v1.0-int8.tar.bz2"

  private val REQUIRED_FILES = listOf(
    "model.onnx",
    "voices.bin",
    "tokens.txt",
    "lexicon.txt",
    "data_dir",
  )

  fun getModelDir(context: Context): File {
    return File(context.filesDir, MODEL_DIR)
  }

  fun checkModelReady(context: Context): Boolean {
    val modelDir = getModelDir(context)
    if (!modelDir.exists()) return false
    // Check that model.onnx exists (main file)
    val modelFile = File(modelDir, "model.onnx")
    val ready = modelFile.exists()
    if (ready) {
      _status.value = KokoroModelStatus.READY
    }
    return ready
  }

  suspend fun ensureModelReady(context: Context) {
    if (checkModelReady(context)) return

    _status.value = KokoroModelStatus.DOWNLOADING
    _downloadProgress.value = 0f

    try {
      downloadAndExtractModel(context)
      _status.value = KokoroModelStatus.READY
    } catch (e: Exception) {
      Log.e(TAG, "Failed to download Kokoro model", e)
      _status.value = KokoroModelStatus.ERROR
    }
  }

  private suspend fun downloadAndExtractModel(context: Context) = withContext(Dispatchers.IO) {
    val modelDir = getModelDir(context)
    modelDir.mkdirs()

    // Download individual files from sherpa-onnx releases
    // The sherpa-onnx Kokoro model comes as individual files
    val files = mapOf(
      "model.onnx" to "kokoro-en-v1.0-int8/model.int8.onnx",
      "voices.bin" to "kokoro-en-v1.0-int8/voices.bin",
      "tokens.txt" to "kokoro-en-v1.0-int8/tokens.txt",
    )

    val baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-kokoro-en-v1.0-int8/resolve/main/"

    var completedFiles = 0
    val totalFiles = files.size

    for ((localName, remotePath) in files) {
      val targetFile = File(modelDir, localName)
      if (targetFile.exists()) {
        completedFiles++
        _downloadProgress.value = completedFiles.toFloat() / totalFiles
        continue
      }

      val url = URL("$baseUrl$remotePath")
      Log.d(TAG, "Downloading: $url")

      val tmpFile = File(modelDir, "$localName.tmp")
      try {
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
          throw Exception("HTTP ${connection.responseCode} for $url")
        }

        val contentLength = connection.contentLengthLong
        var bytesRead = 0L

        connection.inputStream.use { input ->
          FileOutputStream(tmpFile).use { output ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
              output.write(buffer, 0, read)
              bytesRead += read
              if (contentLength > 0) {
                val fileProgress = bytesRead.toFloat() / contentLength
                _downloadProgress.value =
                  (completedFiles + fileProgress) / totalFiles
              }
            }
          }
        }

        tmpFile.renameTo(targetFile)
        completedFiles++
        _downloadProgress.value = completedFiles.toFloat() / totalFiles
        Log.d(TAG, "Downloaded: $localName")
      } catch (e: Exception) {
        tmpFile.delete()
        throw e
      }
    }

    // Create data_dir placeholder (espeak-ng data for phonemizer)
    val dataDir = File(modelDir, "data_dir")
    if (!dataDir.exists()) {
      // Download espeak-ng data directory
      downloadEspeakData(modelDir)
    }
  }

  private suspend fun downloadEspeakData(modelDir: File) = withContext(Dispatchers.IO) {
    // sherpa-onnx Kokoro models need espeak-ng-data for phonemization
    // Download from sherpa-onnx release assets
    val dataDir = File(modelDir, "espeak-ng-data")
    dataDir.mkdirs()

    val baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-kokoro-en-v1.0-int8/resolve/main/"

    // Download the phontab, intonation, and phondata files
    val espeakFiles = listOf(
      "espeak-ng-data/phontab",
      "espeak-ng-data/phondata",
      "espeak-ng-data/phondata-manifest",
      "espeak-ng-data/intonations",
      "espeak-ng-data/phonindex",
    )

    for (filePath in espeakFiles) {
      val targetFile = File(modelDir, filePath)
      if (targetFile.exists()) continue

      targetFile.parentFile?.mkdirs()
      val url = URL("$baseUrl$filePath")
      Log.d(TAG, "Downloading espeak data: $url")

      try {
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.connect()

        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
          connection.inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
              input.copyTo(output)
            }
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to download espeak file: $filePath", e)
      }
    }
  }
}
