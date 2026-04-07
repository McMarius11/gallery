package com.google.ai.edge.gallery.util

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.util.Date

private const val TAG = "CrashLogWriter"
private const val CRASH_FILE = "crash_log.txt"

/**
 * Installs a global UncaughtExceptionHandler that persists crash info to a file.
 * After app restart the crash log can be viewed in Debug Logs → "Last Crash".
 */
object CrashLogWriter {

  fun install(app: Application) {
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      try {
        val sb = StringBuilder()
        sb.appendLine("=== ECHO CRASH LOG ===")
        sb.appendLine("Time: ${Date()}")
        sb.appendLine("Thread: ${thread.name}")
        sb.appendLine()
        sb.appendLine("=== EXCEPTION ===")
        sb.appendLine(Log.getStackTraceString(throwable))
        sb.appendLine()
        sb.appendLine("=== RECENT LOGCAT (W/E/F) ===")
        sb.appendLine(AppLogReader.readRecentLogs())
        File(app.filesDir, CRASH_FILE).writeText(sb.toString())
      } catch (_: Exception) {
        // Best effort - don't crash the crash handler
      }
      defaultHandler?.uncaughtException(thread, throwable)
    }
  }

  fun readCrashLog(context: Context): String? {
    val file = File(context.filesDir, CRASH_FILE)
    return if (file.exists()) file.readText() else null
  }

  fun clearCrashLog(context: Context) {
    File(context.filesDir, CRASH_FILE).delete()
  }
}
