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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
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

  /** Official int8 Kokoro model from sherpa-onnx releases (98MB compressed). */
  private const val MODEL_URL =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2"

  /** Prefix inside the tar.bz2 archive. */
  private const val TAR_PREFIX = "kokoro-int8-en-v0_19/"

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
    "model.int8.onnx",
    "voices.bin",
    "tokens.txt",
    "espeak-ng-data/phontab",
    "espeak-ng-data/phondata",
    "espeak-ng-data/phondata-manifest",
    "espeak-ng-data/intonations",
    "espeak-ng-data/phonindex",
    "espeak-ng-data/en_dict",
    "espeak-ng-data/lang/gmw/en",
  )

  /** Minimum file sizes to detect truncated/corrupt downloads. */
  private val MIN_FILE_SIZES = mapOf(
    "model.int8.onnx" to 50_000_000L,
    "voices.bin" to 1_000_000L,
    "tokens.txt" to 1_000L,
    "espeak-ng-data/en_dict" to 10_000L,
    "espeak-ng-data/phondata" to 1_000L,
  )

  fun checkModelReady(context: Context): Boolean {
    val modelDir = getModelDir(context)
    if (!modelDir.exists()) return false

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

  fun repairCorruptFiles(context: Context) {
    val modelDir = getModelDir(context)
    if (!modelDir.exists()) return

    modelDir.walkTopDown().filter { it.name.endsWith(".tmp") }.forEach {
      Log.w(TAG, "Deleting leftover tmp file: ${it.name}")
      it.delete()
    }

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

    repairCorruptFiles(context)

    _status.value = KokoroModelStatus.DOWNLOADING
    _downloadProgress.value = 0f
    _lastError.value = null
    Log.w(TAG, "Starting Kokoro int8 model download…")

    try {
      downloadAndExtractModel(context)
      val modelDir = getModelDir(context)
      REQUIRED_MODEL_FILES.forEach { filePath ->
        val file = File(modelDir, filePath)
        Log.w(TAG, "Post-download: $filePath = ${if (file.exists()) "${file.length()} bytes" else "MISSING"}")
      }
      if (!checkModelReady(context)) {
        throw Exception("Model files incomplete or corrupt after download")
      }
      Log.w(TAG, "Kokoro int8 model download complete, status=READY")
    } catch (e: Exception) {
      val errorMsg = "${e.javaClass.simpleName}: ${e.message}"
      Log.e(TAG, "Failed to download Kokoro model: $errorMsg", e)
      _lastError.value = errorMsg
      _status.value = KokoroModelStatus.ERROR
    }
  }

  /**
   * Download the official kokoro-int8-en-v0_19.tar.bz2 from sherpa-onnx
   * releases and extract it. This is the EXACT model package that sherpa-onnx
   * distributes and tests — includes model, voices, tokens, and ALL
   * espeak-ng data files (phondata, lang defs, dicts).
   */
  private suspend fun downloadAndExtractModel(context: Context) = withContext(Dispatchers.IO) {
    val modelDir = getModelDir(context)
    modelDir.mkdirs()

    val tarFile = File(modelDir, "model.tar.bz2")

    // Phase 1: Download tar.bz2 (98MB)
    if (!tarFile.exists() || tarFile.length() < 1_000_000) {
      Log.w(TAG, "Downloading: $MODEL_URL")
      val tmpFile = File(modelDir, "model.tar.bz2.tmp")

      try {
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
          throw Exception("HTTP ${connection.responseCode} for $MODEL_URL")
        }

        val contentLength = connection.contentLengthLong
        Log.w(TAG, "Content-Length: $contentLength bytes")
        var bytesRead = 0L

        connection.inputStream.use { input ->
          FileOutputStream(tmpFile).use { output ->
            val buffer = ByteArray(32768)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
              output.write(buffer, 0, read)
              bytesRead += read
              if (contentLength > 0) {
                _downloadProgress.value = (bytesRead.toFloat() / contentLength) * 0.8f
              }
            }
          }
        }

        if (contentLength > 0 && bytesRead != contentLength) {
          tmpFile.delete()
          throw Exception("Incomplete download: expected $contentLength bytes, got $bytesRead")
        }

        if (!tmpFile.renameTo(tarFile)) {
          tmpFile.delete()
          throw Exception("Failed to rename download temp file")
        }
        Log.w(TAG, "Downloaded tar.bz2: ${tarFile.length()} bytes")
      } catch (e: Exception) {
        tmpFile.delete()
        throw e
      }
    }

    // Phase 2: Extract tar.bz2
    Log.w(TAG, "Extracting tar.bz2…")
    _downloadProgress.value = 0.8f

    try {
      TarArchiveInputStream(
        BZip2CompressorInputStream(
          BufferedInputStream(FileInputStream(tarFile), 65536)
        )
      ).use { tar ->
        var entry = tar.nextEntry
        var extractedCount = 0
        while (entry != null) {
          if (!entry.isDirectory) {
            // Strip the archive prefix (e.g. "kokoro-int8-en-v0_19/")
            val relativePath = entry.name.removePrefix(TAR_PREFIX)
            val targetFile = File(modelDir, relativePath)
            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { output ->
              tar.copyTo(output)
            }
            extractedCount++
          }
          entry = tar.nextEntry
        }
        Log.w(TAG, "Extracted $extractedCount files from tar.bz2")
      }
    } catch (e: Exception) {
      throw Exception("Failed to extract tar.bz2: ${e.message}", e)
    }

    _downloadProgress.value = 0.95f

    // Phase 3: Clean up tar.bz2 to save space
    tarFile.delete()
    Log.w(TAG, "Deleted tar.bz2 to save space")

    _downloadProgress.value = 1.0f
  }
}
