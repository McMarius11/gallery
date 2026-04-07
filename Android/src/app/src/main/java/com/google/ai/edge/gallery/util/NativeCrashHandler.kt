package com.google.ai.edge.gallery.util

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.ai.edge.gallery.ui.crash.CrashActivity
import xcrash.ICrashCallback
import xcrash.XCrash
import java.io.File

private const val TAG = "NativeCrashHandler"
private const val CRASH_FILE = "crash_log.txt"
private const val DOWNLOADS_FILENAME = "echo_crash_log.txt"

/**
 * Unified crash handler using xCrash + logcat crash buffer.
 *
 * - xCrash: catches Java exceptions reliably
 * - logcat -b crash: captures native crash tombstones (SIGABRT, SIGSEGV)
 *   even when xCrash's native dumper fails (e.g. on newer Android versions)
 *
 * On app restart, [checkPendingCrash] checks both xCrash tombstones and
 * the system crash buffer for recent crashes.
 *
 * No data leaves the device.
 */
object NativeCrashHandler {

  private val crashCallback = ICrashCallback { logPath, _ ->
    try {
      if (logPath != null) {
        val tombstone = File(logPath).readText()
        File(appFilesDir, CRASH_FILE).writeText(tombstone)
      }
    } catch (_: Exception) {}
  }

  private lateinit var appFilesDir: File

  fun init(context: Context) {
    appFilesDir = context.filesDir
    val tombstoneDir = File(context.filesDir, "tombstones").apply { mkdirs() }

    val params = XCrash.InitParameters()
      .setLogDir(tombstoneDir.absolutePath)
      .enableJavaCrashHandler()
      .setJavaRethrow(true)
      .setJavaLogCountMax(5)
      .setJavaLogcatMainLines(200)
      .setJavaDumpAllThreads(true)
      .setJavaCallback(crashCallback)
      .enableNativeCrashHandler()
      .setNativeRethrow(true)
      .setNativeLogCountMax(5)
      .setNativeLogcatMainLines(200)
      .setNativeDumpAllThreads(true)
      .setNativeDumpMap(true)
      .setNativeDumpFds(true)
      .setNativeCallback(crashCallback)
      .disableAnrCrashHandler()

    XCrash.init(context, params)
    Log.w(TAG, "xCrash initialized")
  }

  /**
   * Checks for crash data from a previous run. Sources (in priority order):
   * 1. xCrash tombstone files (Java + native if dumper works)
   * 2. System crash buffer via logcat -b crash (native crashes, always works)
   * 3. crash_log.txt from xCrash callback (best-effort)
   */
  fun checkPendingCrash(context: Context) {
    val crashText = findCrashLog(context) ?: return

    // Save for DebugLogsDialog "Last Crash"
    try { File(context.filesDir, CRASH_FILE).writeText(crashText) } catch (_: Exception) {}

    // Copy to Downloads
    copyToDownloads(crashText)

    // Show CrashActivity after a delay
    Handler(Looper.getMainLooper()).postDelayed({
      try {
        val intent = Intent(context, CrashActivity::class.java).apply {
          putExtra(CrashActivity.EXTRA_CRASH_LOG, crashText)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to launch CrashActivity", e)
      }
    }, 1500)
  }

  private fun findCrashLog(context: Context): String? {
    // 1. Check xCrash tombstone files
    val tombstoneDir = File(context.filesDir, "tombstones")
    if (tombstoneDir.exists()) {
      val latest = tombstoneDir.listFiles()
        ?.filter { it.isFile && it.length() > 0 }
        ?.maxByOrNull { it.lastModified() }

      if (latest != null) {
        val text = try { latest.readText() } catch (_: Exception) { null }
        // Clean up all tombstones
        tombstoneDir.listFiles()?.forEach { it.delete() }
        if (!text.isNullOrBlank()) return text
      }
    }

    // 2. Check crash_log.txt from callback
    val crashFile = File(context.filesDir, CRASH_FILE)
    if (crashFile.exists()) {
      val text = try { crashFile.readText() } catch (_: Exception) { null }
      if (!text.isNullOrBlank()) return text
    }

    // 3. Check system crash buffer (logcat -b crash)
    val crashBuffer = AppLogReader.readCrashBuffer()
    if (crashBuffer.isNotBlank()) return "=== NATIVE CRASH (from system log) ===\n\n$crashBuffer"

    return null
  }

  private fun copyToDownloads(crashText: String) {
    try {
      val downloadsDir = Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_DOWNLOADS
      )
      File(downloadsDir, DOWNLOADS_FILENAME).writeText(crashText)
      Log.w(TAG, "Crash log saved to Downloads/$DOWNLOADS_FILENAME")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to copy to Downloads (expected on Android 10+)", e)
    }
  }
}
