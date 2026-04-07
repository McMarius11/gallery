package com.google.ai.edge.gallery.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.ai.edge.gallery.ui.crash.CrashActivity
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
        val crashText = sb.toString()
        File(app.filesDir, CRASH_FILE).writeText(crashText)

        // Launch CrashActivity in a separate process to show the log
        val intent = Intent(app, CrashActivity::class.java).apply {
          putExtra(CrashActivity.EXTRA_CRASH_LOG, crashText)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        app.startActivity(intent)
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
