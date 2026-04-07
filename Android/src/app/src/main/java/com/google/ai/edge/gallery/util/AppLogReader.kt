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

  /**
   * Read the system crash buffer. Contains native crash tombstones (SIGABRT, SIGSEGV)
   * from previous app runs. Unlike the main logcat buffer, this survives process death.
   * Filters for our package name since the crash buffer is shared across all apps.
   */
  fun readCrashBuffer(): String {
    return try {
      val process = Runtime.getRuntime().exec(
        arrayOf("logcat", "-b", "crash", "-d")
      )
      val output = process.inputStream.bufferedReader().readText()
      process.waitFor()
      // Filter for our package/process
      val lines = output.lines()
      val relevant = lines.filter {
        it.contains("aiedge.gallery", ignoreCase = true) ||
          it.contains("sherpa", ignoreCase = true) ||
          it.contains("SIGABRT", ignoreCase = true) ||
          it.contains("SIGSEGV", ignoreCase = true) ||
          it.contains("signal", ignoreCase = true) ||
          it.contains("backtrace", ignoreCase = true) ||
          it.contains("fault addr", ignoreCase = true) ||
          it.contains("#0", ignoreCase = false) ||
          it.contains("pid:", ignoreCase = true)
      }
      relevant.joinToString("\n").ifEmpty { "" }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read crash buffer", e)
      ""
    }
  }
}
