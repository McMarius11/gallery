package com.google.ai.edge.gallery.util

import android.util.Log

private const val TAG = "AppLogReader"

/**
 * Reads this app's own logcat output (Warning + Error + Fatal levels).
 * No code changes needed elsewhere — all existing Log.w() / Log.e() calls are captured.
 */
object AppLogReader {

  /**
   * Dump recent W/E/F log lines from this process.
   * Runs `logcat -d --pid=<self> *:W` which is allowed for the app's own PID.
   */
  fun readRecentLogs(): String {
    return try {
      val pid = android.os.Process.myPid()
      val process = Runtime.getRuntime().exec(
        arrayOf("logcat", "-d", "--pid=$pid", "*:W")
      )
      val output = process.inputStream.bufferedReader().readText()
      process.waitFor()
      output.ifEmpty { "(no warnings or errors logged yet)" }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read logcat", e)
      "Failed to read logcat: ${e.message}"
    }
  }

  /**
   * Read ALL log levels (V/D/I/W/E/F) for this process — more verbose, useful for deep debugging.
   */
  fun readAllLogs(): String {
    return try {
      val pid = android.os.Process.myPid()
      val process = Runtime.getRuntime().exec(
        arrayOf("logcat", "-d", "--pid=$pid")
      )
      val output = process.inputStream.bufferedReader().readText()
      process.waitFor()
      output.ifEmpty { "(no logs yet)" }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read logcat", e)
      "Failed to read logcat: ${e.message}"
    }
  }
}
