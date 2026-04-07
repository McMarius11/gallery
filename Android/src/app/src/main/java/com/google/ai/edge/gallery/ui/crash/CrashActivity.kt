package com.google.ai.edge.gallery.ui.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.util.CrashLogReader

/**
 * Activity shown after a crash. Runs in a separate process (:crash)
 * so it survives the death of the main app process.
 *
 * Shows the crash stacktrace and lets the user copy it to clipboard.
 */
class CrashActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Clear the crash log immediately so we don't show it again
    // if CrashActivity itself crashes (prevents infinite loop).
    CrashLogReader.clearCrashLog(this)

    val crashLog = intent.getStringExtra(EXTRA_CRASH_LOG)
      ?: "No crash log available."

    setContent {
      MaterialTheme {
        CrashScreen(
          crashLog = crashLog,
          onCopy = {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Echo Crash Log", crashLog))
            Toast.makeText(this, "Crash log copied to clipboard", Toast.LENGTH_SHORT).show()
          },
          onRestart = {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
              intent.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                  android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
              )
              startActivity(intent)
            }
            finish()
          },
          onClose = { finish() },
        )
      }
    }
  }

  companion object {
    const val EXTRA_CRASH_LOG = "crash_log"
  }
}

@Composable
private fun CrashScreen(
  crashLog: String,
  onCopy: () -> Unit,
  onRestart: () -> Unit,
  onClose: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "Echo crashed",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
      )

      Text(
        text = "Copy the log below and share it with the developer.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      // Button row
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCopy) {
          Text("Copy Log")
        }
        OutlinedButton(onClick = onRestart) {
          Text("Restart App")
        }
        OutlinedButton(onClick = onClose) {
          Text("Close")
        }
      }

      // Scrollable crash log
      val verticalScroll = rememberScrollState()
      val horizontalScroll = rememberScrollState()

      SelectionContainer(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .verticalScroll(verticalScroll)
          .horizontalScroll(horizontalScroll),
      ) {
        Text(
          text = crashLog,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          lineHeight = 14.sp,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}
