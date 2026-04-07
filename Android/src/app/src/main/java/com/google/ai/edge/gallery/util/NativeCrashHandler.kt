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
 * Handles native crashes (SIGABRT, SIGSEGV, etc.) via xCrash.
 * Tombstone files are written to filesDir/tombstones.
 * On next app start, [checkPendingCrash] reads the latest tombstone
 * and copies it to crash_log.txt for display in DebugLogsDialog.
 */
object NativeCrashHandler {

  fun init(context: Context) {
    val tombstoneDir = File(context.filesDir, "tombstones").apply { mkdirs() }

    val nativeCallback = ICrashCallback { logPath, emergency ->
      // Called after the tombstone is written, before the process dies.
      // Copy tombstone content to crash_log.txt so it's available on restart.
      try {
        val tombstone = File(logPath).readText()
        val crashText = buildString {
          appendLine("=== ECHO NATIVE CRASH LOG ===")
          appendLine("Source: xCrash native signal handler")
          appendLine()
          appendLine(tombstone)
          if (!emergency.isNullOrEmpty()) {
            appendLine()
            appendLine("=== EMERGENCY ===")
            appendLine(emergency)
          }
        }
        File(context.filesDir, CRASH_FILE).writeText(crashText)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to copy tombstone", e)
      }
    }

    val params = XCrash.InitParameters()
      .setLogDir(tombstoneDir.absolutePath)
      .disableJavaCrashHandler()  // ACRA handles Java crashes
      .disableAnrCrashHandler()   // Not needed for now
      .enableNativeCrashHandler()
      .setNativeCallback(nativeCallback)
      .setNativeLogCountMax(5)
      .setNativeLogcatMainLines(200)
      .setNativeLogcatSystemLines(50)
      .setNativeDumpAllThreads(true)
      .setNativeDumpMap(true)
      .setNativeDumpFds(true)

    val result = XCrash.init(context, params)
    if (result == 0) {
      Log.w(TAG, "xCrash native crash handler initialized")
    } else {
      Log.e(TAG, "xCrash init failed with code: $result")
    }
  }

  /**
   * Called on app restart. If a crash_log.txt from a native crash exists,
   * launch CrashActivity to display it.
   */
  fun checkPendingCrash(context: Context) {
    val crashFile = File(context.filesDir, CRASH_FILE)
    if (crashFile.exists() && crashFile.readText().contains("NATIVE CRASH LOG")) {
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
}
