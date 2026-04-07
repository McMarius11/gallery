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
 * Unified crash handler using xCrash. Catches both:
 * - Java/Kotlin exceptions
 * - Native crashes: SIGABRT, SIGSEGV, SIGBUS, etc.
 *
 * Tombstone files are written to filesDir/tombstones by xCrash's native
 * signal handler. On next app start, [checkPendingCrash] scans the
 * tombstones directory directly (does NOT rely on the Java callback,
 * which may not run for fast native kills).
 *
 * No data leaves the device.
 */
object NativeCrashHandler {

  // Best-effort callback for Java crashes (native crashes may kill before this runs)
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
      // Java crash handler
      .enableJavaCrashHandler()
      .setJavaRethrow(true)
      .setJavaLogCountMax(5)
      .setJavaLogcatMainLines(200)
      .setJavaDumpAllThreads(true)
      .setJavaCallback(crashCallback)
      // Native crash handler (SIGABRT, SIGSEGV, etc.)
      .enableNativeCrashHandler()
      .setNativeRethrow(true)
      .setNativeLogCountMax(5)
      .setNativeLogcatMainLines(200)
      .setNativeDumpAllThreads(true)
      .setNativeDumpMap(true)
      .setNativeDumpFds(true)
      .setNativeCallback(crashCallback)
      // ANR detection
      .disableAnrCrashHandler()

    val result = XCrash.init(context, params)
    if (result == 0) {
      Log.w(TAG, "xCrash initialized (Java + native crash handler)")
    } else {
      Log.e(TAG, "xCrash init failed with code: $result")
    }
  }

  /**
   * Scans the tombstones directory for crash files from a previous run.
   * Does NOT rely on the Java callback (which may not run for native kills).
   * If a tombstone is found, copies it to Downloads and shows CrashActivity.
   */
  fun checkPendingCrash(context: Context) {
    val tombstoneDir = File(context.filesDir, "tombstones")
    if (!tombstoneDir.exists()) return

    // Find the most recent tombstone file
    val latestTombstone = tombstoneDir.listFiles()
      ?.filter { it.isFile && it.length() > 0 }
      ?.maxByOrNull { it.lastModified() }
      ?: return

    // Read and clean up
    val crashText = try {
      latestTombstone.readText()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read tombstone", e)
      return
    }

    if (crashText.isBlank()) return

    // Copy to Downloads so user can access it via file manager
    copyToDownloads(crashText)

    // Save to crash_log.txt for DebugLogsDialog "Last Crash" button
    try {
      File(context.filesDir, CRASH_FILE).writeText(crashText)
    } catch (_: Exception) {}

    // Delete tombstones so we don't show them again
    tombstoneDir.listFiles()?.forEach { it.delete() }

    // Show CrashActivity after a short delay to let MainActivity init
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

  private fun copyToDownloads(crashText: String) {
    try {
      val downloadsDir = Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_DOWNLOADS
      )
      val crashFile = File(downloadsDir, DOWNLOADS_FILENAME)
      crashFile.writeText(crashText)
      Log.w(TAG, "Crash log saved to Downloads/$DOWNLOADS_FILENAME")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to copy crash log to Downloads", e)
    }
  }
}
