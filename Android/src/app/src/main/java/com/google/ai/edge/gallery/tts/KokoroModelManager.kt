package com.google.ai.edge.gallery.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
  /** App-scoped scope — downloads survive Activity/Dialog lifecycle. */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    // Language definition — espeak-ng needs this to phonemize English
    "espeak-ng-data/lang/gmw/en",
  )

  /** Minimum file sizes to detect truncated/corrupt downloads. */
  private val MIN_FILE_SIZES = mapOf(
    "model.onnx" to 1_000_000L,
    "voices.bin" to 1_000_000L,
    "tokens.txt" to 1_000L,
    "espeak-ng-data/en_dict" to 10_000L,
    "espeak-ng-data/phondata" to 1_000L,
  )

  /**
   * Pure check: are all required files present and valid?
   * Does NOT modify files or status — see [repairCorruptFiles] for cleanup.
   */
  fun checkModelReady(context: Context): Boolean {
    val modelDir = getModelDir(context)
    if (!modelDir.exists()) return false

    // Check for truncated files
    for ((filePath, minSize) in MIN_FILE_SIZES) {
      val file = File(modelDir, filePath)
      if (file.exists() && file.length() < minSize) {
        Log.e(TAG, "Corrupt file: $filePath (${file.length()} bytes, min=$minSize)")
        return false
      }
    }

    val missing = REQUIRED_MODEL_FILES.filter { !File(modelDir, it).exists() }
    if (missing.isNotEmpty()) {
      Log.w(TAG, "Kokoro model incomplete, missing: $missing")
      return false
    }

    _status.value = KokoroModelStatus.READY
    return true
  }

  /**
   * Delete corrupt/truncated files and leftover .tmp files so the next
   * download attempt can re-fetch them. Call before [ensureModelReady].
   */
  fun repairCorruptFiles(context: Context) {
    val modelDir = getModelDir(context)
    if (!modelDir.exists()) return

    // Remove leftover .tmp files from interrupted downloads
    modelDir.walkTopDown().filter { it.name.endsWith(".tmp") }.forEach {
      Log.w(TAG, "Deleting leftover tmp file: ${it.name}")
      it.delete()
    }

    // Remove truncated files so download loop re-fetches them
    for ((filePath, minSize) in MIN_FILE_SIZES) {
      val file = File(modelDir, filePath)
      if (file.exists() && file.length() < minSize) {
        Log.e(TAG, "Deleting corrupt file: $filePath (${file.length()} bytes, min=$minSize)")
        file.delete()
      }
    }
  }

  /** Delete all model files so they can be re-downloaded. */
  fun deleteModelFiles(context: Context) {
    val modelDir = getModelDir(context)
    if (modelDir.exists()) {
      modelDir.deleteRecursively()
      Log.w(TAG, "Deleted Kokoro model directory: ${modelDir.absolutePath}")
    }
    _status.value = KokoroModelStatus.NOT_DOWNLOADED
    _downloadProgress.value = 0f
    _lastError.value = null
  }

  /**
   * Launch model download in an app-scoped coroutine that survives
   * Activity/Dialog lifecycle (e.g. user closes Settings while downloading).
   * Calls [onReady] on completion if provided (e.g. to init the TTS engine).
   */
  fun launchDownload(context: Context, onReady: (suspend () -> Unit)? = null) {
    scope.launch {
      ensureModelReady(context)
      if (_status.value == KokoroModelStatus.READY) {
        onReady?.invoke()
      }
    }
  }

  suspend fun ensureModelReady(context: Context) {
    if (checkModelReady(context)) return

    // Clean up corrupt/truncated files before attempting download
    repairCorruptFiles(context)

    _status.value = KokoroModelStatus.DOWNLOADING
    _downloadProgress.value = 0f
    _lastError.value = null
    Log.w(TAG, "Starting Kokoro model download…")

    try {
      downloadAndExtractModel(context)
      // Log all file sizes for debugging
      val modelDir = getModelDir(context)
      REQUIRED_MODEL_FILES.forEach { filePath ->
        val file = File(modelDir, filePath)
        Log.w(TAG, "Post-download: $filePath = ${if (file.exists()) "${file.length()} bytes" else "MISSING"}")
      }
      if (!checkModelReady(context)) {
        throw Exception("Model files incomplete or corrupt after download")
      }
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
      val minSize = MIN_FILE_SIZES[localName]
      if (targetFile.exists() && (minSize == null || targetFile.length() >= minSize)) {
        completedFiles++
        _downloadProgress.value = completedFiles.toFloat() / totalFiles
        continue
      }
      // Delete truncated file before re-downloading
      if (targetFile.exists()) {
        Log.w(TAG, "Deleting truncated $localName (${targetFile.length()} bytes, min=$minSize)")
        targetFile.delete()
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

        if (contentLength > 0 && bytesRead != contentLength) {
          tmpFile.delete()
          throw Exception("Incomplete download for $localName: expected $contentLength bytes, got $bytesRead")
        }
        if (!tmpFile.renameTo(targetFile)) {
          tmpFile.delete()
          throw Exception("Failed to rename $localName.tmp to $localName")
        }
        completedFiles++
        _downloadProgress.value = completedFiles.toFloat() / totalFiles
        Log.w(TAG, "Downloaded: $localName (${targetFile.length()} bytes)")
      } catch (e: Exception) {
        tmpFile.delete()
        throw e
      }
    }

    // Download espeak-ng data for phonemizer (skips files that already exist)
    downloadEspeakData(modelDir)
  }

  private suspend fun downloadEspeakData(modelDir: File) = withContext(Dispatchers.IO) {
    // sherpa-onnx Kokoro models need espeak-ng-data for phonemization
    // Download from sherpa-onnx release assets
    val dataDir = File(modelDir, "espeak-ng-data")
    dataDir.mkdirs()

    val baseUrl = "https://huggingface.co/csukuangfj/kokoro-en-v0_19/resolve/main/"

    // Download the phontab, intonation, phondata, language dictionary,
    // and language definition files required by espeak-ng phonemizer.
    val espeakFiles = listOf(
      "espeak-ng-data/phontab",
      "espeak-ng-data/phondata",
      "espeak-ng-data/phondata-manifest",
      "espeak-ng-data/intonations",
      "espeak-ng-data/phonindex",
      // Language dictionary — required for phonemization.
      "espeak-ng-data/en_dict",
      // Language definition files — WITHOUT these, espeak-ng cannot
      // identify or phonemize English, causing SIGABRT at generateWithCallback().
      "espeak-ng-data/lang/gmw/en",
      "espeak-ng-data/lang/gmw/en-US",
      "espeak-ng-data/lang/gmw/en-029",
      "espeak-ng-data/lang/gmw/en-GB-scotland",
      "espeak-ng-data/lang/gmw/en-GB-x-gbclan",
      "espeak-ng-data/lang/gmw/en-GB-x-gbcwmd",
      "espeak-ng-data/lang/gmw/en-GB-x-rp",
      "espeak-ng-data/lang/gmw/en-US-nyc",
    )

    for (filePath in espeakFiles) {
      val targetFile = File(modelDir, filePath)
      if (targetFile.exists() && targetFile.length() > 0) continue

      targetFile.parentFile?.mkdirs()
      val url = URL("$baseUrl$filePath")
      Log.w(TAG, "Downloading espeak data: $url")

      val tmpFile = File(modelDir, "$filePath.tmp")
      try {
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.connect()

        Log.w(TAG, "espeak HTTP ${connection.responseCode} ${connection.responseMessage} for $filePath")

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
          throw Exception("HTTP ${connection.responseCode} downloading espeak file: $filePath")
        }

        connection.inputStream.use { input ->
          FileOutputStream(tmpFile).use { output ->
            input.copyTo(output)
          }
        }

        if (tmpFile.length() == 0L) {
          tmpFile.delete()
          throw Exception("Empty download for espeak file: $filePath")
        }

        if (!tmpFile.renameTo(targetFile)) {
          tmpFile.delete()
          throw Exception("Failed to rename $filePath.tmp to $filePath")
        }
        Log.w(TAG, "espeak downloaded: $filePath (${targetFile.length()} bytes)")
      } catch (e: Exception) {
        tmpFile.delete()
        throw Exception("Failed to download espeak file: $filePath", e)
      }
    }
  }
}
