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

private const val TAG = "AsrModelManager"

enum class AsrModelStatus {
  NOT_DOWNLOADED,
  DOWNLOADING,
  READY,
  ERROR,
}

object AsrModelManager {
  private val _status = MutableStateFlow(AsrModelStatus.NOT_DOWNLOADED)
  val status: StateFlow<AsrModelStatus> = _status.asStateFlow()

  private val _downloadProgress = MutableStateFlow(0f)
  val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

  private val _lastError = MutableStateFlow<String?>(null)
  val lastError: StateFlow<String?> = _lastError.asStateFlow()

  private const val MODEL_DIR = "whisper-asr"
  private const val BASE_URL =
    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/"

  fun getModelDir(context: Context): File {
    return File(context.filesDir, MODEL_DIR)
  }

  fun resetForRetry() {
    if (_status.value == AsrModelStatus.ERROR) {
      _status.value = AsrModelStatus.NOT_DOWNLOADED
      _downloadProgress.value = 0f
      _lastError.value = null
    }
  }

  fun checkModelReady(context: Context): Boolean {
    val modelDir = getModelDir(context)
    if (!modelDir.exists()) return false
    val encoder = File(modelDir, "small-encoder.int8.onnx")
    val decoder = File(modelDir, "small-decoder.int8.onnx")
    val tokens = File(modelDir, "small-tokens.txt")

    // Check existence AND minimum file sizes to detect corrupt/truncated downloads.
    // Encoder ~30MB, decoder ~17MB, tokens ~10KB
    val ready = encoder.exists() && encoder.length() > 1_000_000 &&
      decoder.exists() && decoder.length() > 1_000_000 &&
      tokens.exists() && tokens.length() > 1_000

    if (ready) {
      _status.value = AsrModelStatus.READY
    } else if (encoder.exists() || decoder.exists() || tokens.exists()) {
      // Files exist but are too small - likely corrupt. Delete and re-download.
      Log.w(TAG, "Model files appear corrupt (encoder=${encoder.length()}, decoder=${decoder.length()}, tokens=${tokens.length()}). Deleting for re-download.")
      deleteModelFiles(context)
    }
    return ready
  }

  fun deleteModelFiles(context: Context) {
    val modelDir = getModelDir(context)
    modelDir.listFiles()?.forEach { it.delete() }
    _status.value = AsrModelStatus.NOT_DOWNLOADED
  }

  suspend fun ensureModelReady(context: Context) {
    if (checkModelReady(context)) return

    _status.value = AsrModelStatus.DOWNLOADING
    _downloadProgress.value = 0f
    _lastError.value = null
    Log.w(TAG, "Starting Whisper ASR model download…")

    try {
      downloadModel(context)
      _status.value = AsrModelStatus.READY
      Log.w(TAG, "Whisper ASR model download complete, status=READY")
    } catch (e: Exception) {
      val errorMsg = "${e.javaClass.simpleName}: ${e.message}"
      Log.e(TAG, "Failed to download Whisper ASR model: $errorMsg", e)
      _lastError.value = errorMsg
      _status.value = AsrModelStatus.ERROR
    }
  }

  private suspend fun downloadModel(context: Context) = withContext(Dispatchers.IO) {
    val modelDir = getModelDir(context)
    modelDir.mkdirs()

    val files = mapOf(
      "small-encoder.int8.onnx" to "small-encoder.int8.onnx",
      "small-decoder.int8.onnx" to "small-decoder.int8.onnx",
      "small-tokens.txt" to "small-tokens.txt",
    )

    var completedFiles = 0
    val totalFiles = files.size

    for ((localName, remotePath) in files) {
      val targetFile = File(modelDir, localName)
      if (targetFile.exists()) {
        completedFiles++
        _downloadProgress.value = completedFiles.toFloat() / totalFiles
        continue
      }

      val url = URL("$BASE_URL$remotePath")
      Log.w(TAG, "Downloading: $url")

      val tmpFile = File(modelDir, "$localName.tmp")
      try {
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.connect()

        Log.w(TAG, "HTTP ${connection.responseCode} ${connection.responseMessage} for $localName")

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
  }
}
