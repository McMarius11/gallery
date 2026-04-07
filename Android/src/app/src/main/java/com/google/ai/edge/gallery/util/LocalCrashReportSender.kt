package com.google.ai.edge.gallery.util

import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.ai.edge.gallery.ui.crash.CrashActivity
import org.acra.ReportField
import org.acra.data.CrashReportData
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import java.io.File
import java.util.Date

private const val CRASH_FILE = "crash_log.txt"

/**
 * ACRA ReportSender that writes crash reports to a local file and launches
 * [CrashActivity] to display them. No data is sent to any server.
 */
class LocalCrashReportSender(private val context: Context) : ReportSender {

  override fun send(context: Context, report: CrashReportData) {
    val sb = StringBuilder()
    sb.appendLine("=== ECHO CRASH LOG ===")
    sb.appendLine("Time: ${Date()}")
    sb.appendLine("App: ${report.getString(ReportField.APP_VERSION_NAME)} (${report.getString(ReportField.APP_VERSION_CODE)})")
    sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
    sb.appendLine("RAM: ${report.getString(ReportField.TOTAL_MEM_SIZE)}")
    sb.appendLine("Available RAM: ${report.getString(ReportField.AVAILABLE_MEM_SIZE)}")
    sb.appendLine()
    sb.appendLine("=== EXCEPTION ===")
    sb.appendLine(report.getString(ReportField.STACK_TRACE))
    sb.appendLine()
    sb.appendLine("=== THREAD DETAILS ===")
    sb.appendLine(report.getString(ReportField.THREAD_DETAILS))
    sb.appendLine()
    sb.appendLine("=== LOGCAT ===")
    sb.appendLine(report.getString(ReportField.LOGCAT))

    val crashText = sb.toString()

    try {
      File(context.filesDir, CRASH_FILE).writeText(crashText)
    } catch (_: Exception) {}

    // Launch CrashActivity in a separate process
    try {
      val intent = Intent(context, CrashActivity::class.java).apply {
        putExtra(CrashActivity.EXTRA_CRASH_LOG, crashText)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
      }
      context.startActivity(intent)
    } catch (_: Exception) {}
  }
}

class LocalCrashReportSenderFactory : ReportSenderFactory {
  override fun create(context: Context, config: org.acra.config.CoreConfiguration): ReportSender {
    return LocalCrashReportSender(context)
  }

  override fun enabled(config: org.acra.config.CoreConfiguration): Boolean = true
}
