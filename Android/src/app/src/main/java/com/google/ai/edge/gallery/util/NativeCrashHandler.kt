package com.google.ai.edge.gallery.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.ai.edge.gallery.ui.crash.CrashActivity
import xcrash.ICrashCallback
import xcrash.XCrash
import java.io.File

private const val TAG = "NativeCrashHandler"
private const val CRASH_FILE = "crash_log.txt"

/**
 * Unified crash handler using xCrash. Catches both:
 * - Java/Kotlin exceptions (like ACRA but simpler)
 * - Native crashes: SIGABRT, SIGSEGV, SIGBUS, etc.
 *
 * Tombstone files are written to filesDir/tombstones.
 * The crash callback copies the tombstone to crash_log.txt.
 * On next app start, [checkPendingCrash] shows it in CrashActivity.
 *
 * No data leaves the device.
 */
object NativeCrashHandler {

  private val crashCallback = ICrashCallback { logPath, emergency ->
    // Called after the tombstone is written, before the process dies.
    try {
      val tombstone = if (logPath != null) File(logPath).readText() else ""
      val crashText = buildString {
        appendLine("=== ECHO CRASH LOG ===")
        appendLine("Source: xCrash")
        appendLine()
        appendLine(tombstone)
        if (!emergency.isNullOrEmpty()) {
          appendLine()
          appendLine("=== EMERGENCY ===")
          appendLine(emergency)
        }
      }
      // Write to a known location so we can find it on restart
      File(appFilesDir, CRASH_FILE).writeText(crashText)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to copy tombstone", e)
    }
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
   * Called on app restart in onCreate(). If a crash_log.txt exists from a
   * previous crash, launch CrashActivity to display it.
   */
  fun checkPendingCrash(context: Context) {
    val crashFile = File(context.filesDir, CRASH_FILE)
    if (!crashFile.exists()) return

    try {
      val intent = Intent(context, CrashActivity::class.java).apply {
        putExtra(CrashActivity.EXTRA_CRASH_LOG, crashFile.readText())
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to launch CrashActivity", e)
    }
  }
}
