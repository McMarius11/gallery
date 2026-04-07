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

  private val _lastError = MutableStateFlow<String?>(null)
  val lastError: StateFlow<String?> = _lastError.asStateFlow()

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

  /** Reset error state so ensureModelReady can be called again. */
  fun resetForRetry() {
    if (_status.value == KokoroModelStatus.ERROR) {
      _status.value = KokoroModelStatus.NOT_DOWNLOADED
      _downloadProgress.value = 0f
      _lastError.value = null
    }
  }

  /** All files that must exist for TTS to work. */
  private val REQUIRED_MODEL_FILES = listOf(
    "model.onnx",
    "voices.bin",
    "tokens.txt",
    "espeak-ng-data/phontab",
    "espeak-ng-data/phondata",
    "espeak-ng-data/phondata-manifest",
    "espeak-ng-data/intonations",
    "espeak-ng-data/phonindex",
    "espeak-ng-data/en_dict",
  )

  fun checkModelReady(context: Context): Boolean {
    val modelDir = getModelDir(context)
    if (!modelDir.exists()) return false

    val missing = REQUIRED_MODEL_FILES.filter { !File(modelDir, it).exists() }
    if (missing.isNotEmpty()) {
      Log.w(TAG, "Kokoro model incomplete, missing: $missing")
      return false
    }

    _status.value = KokoroModelStatus.READY
    return true
  }

  suspend fun ensureModelReady(context: Context) {
    if (checkModelReady(context)) return

    _status.value = KokoroModelStatus.DOWNLOADING
    _downloadProgress.value = 0f
    _lastError.value = null
    Log.w(TAG, "Starting Kokoro model download…")

    try {
      downloadAndExtractModel(context)
      _status.value = KokoroModelStatus.READY
      Log.w(TAG, "Kokoro model download complete, status=READY")
    } catch (e: Exception) {
      val errorMsg = "${e.javaClass.simpleName}: ${e.message}"
      Log.e(TAG, "Failed to download Kokoro model: $errorMsg", e)
      _lastError.value = errorMsg
      _status.value = KokoroModelStatus.ERROR
    }
  }

  private suspend fun downloadAndExtractModel(context: Context) = withContext(Dispatchers.IO) {
    val modelDir = getModelDir(context)
    modelDir.mkdirs()

    // Download individual files from sherpa-onnx releases
    // The sherpa-onnx Kokoro model comes as individual files
    val files = mapOf(
      "model.onnx" to "model.onnx",
      "voices.bin" to "voices.bin",
      "tokens.txt" to "tokens.txt",
    )

    val baseUrl = "https://huggingface.co/csukuangfj/kokoro-en-v0_19/resolve/main/"

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
      Log.w(TAG, "Downloading: $url")

      val tmpFile = File(modelDir, "$localName.tmp")
      try {
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.connect()

        Log.w(TAG, "HTTP ${connection.responseCode} ${connection.responseMessage} for $localName (URL: $url)")

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
          throw Exception("HTTP ${connection.responseCode} for $url (${connection.responseMessage})")
        }

        val contentLength = connection.contentLengthLong
        Log.w(TAG, "Content-Length for $localName: $contentLength bytes")
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
        Log.w(TAG, "Downloaded: $localName (${targetFile.length()} bytes)")
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

    val baseUrl = "https://huggingface.co/csukuangfj/kokoro-en-v0_19/resolve/main/"

    // Download the phontab, intonation, phondata, and language dictionary files
    val espeakFiles = listOf(
      "espeak-ng-data/phontab",
      "espeak-ng-data/phondata",
      "espeak-ng-data/phondata-manifest",
      "espeak-ng-data/intonations",
      "espeak-ng-data/phonindex",
      // Language dictionary — required for phonemization. Without en_dict,
      // generateWithCallback crashes when processing English text.
      "espeak-ng-data/en_dict",
    )

    for (filePath in espeakFiles) {
      val targetFile = File(modelDir, filePath)
      if (targetFile.exists()) continue

      targetFile.parentFile?.mkdirs()
      val url = URL("$baseUrl$filePath")
      Log.w(TAG, "Downloading espeak data: $url")

      try {
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.connect()

        Log.w(TAG, "espeak HTTP ${connection.responseCode} ${connection.responseMessage} for $filePath")

        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
          connection.inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
              input.copyTo(output)
            }
          }
          Log.w(TAG, "espeak downloaded: $filePath (${targetFile.length()} bytes)")
        } else {
          Log.e(TAG, "espeak download failed: HTTP ${connection.responseCode} for $filePath")
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to download espeak file: $filePath - ${e.javaClass.simpleName}: ${e.message}", e)
      }
    }
  }
}
