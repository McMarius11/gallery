package com.google.ai.edge.gallery.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.util.AppLogReader

@Composable
fun DebugLogsDialog(
  onDismissed: () -> Unit,
) {
  val context = LocalContext.current
  var logText by remember { mutableStateOf("Loading…") }
  var showAll by remember { mutableStateOf(false) }

  // Load logs on open and when filter changes.
  LaunchedEffect(showAll) {
    logText = if (showAll) AppLogReader.readAllLogs() else AppLogReader.readRecentLogs()
  }

  Dialog(
    onDismissRequest = onDismissed,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Card(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text("Debug Logs", style = MaterialTheme.typography.titleLarge)

        // Button row
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Echo Debug Logs", logText))
            Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
          }) {
            Text("Copy All")
          }

          OutlinedButton(onClick = {
            logText = if (showAll) AppLogReader.readAllLogs() else AppLogReader.readRecentLogs()
          }) {
            Text("Refresh")
          }

          OutlinedButton(onClick = { showAll = !showAll }) {
            Text(if (showAll) "Errors only" else "Show all")
          }
        }

        // Scrollable log text
        val verticalScrollState = rememberScrollState(Int.MAX_VALUE) // start at bottom
        val horizontalScrollState = rememberScrollState()

        SelectionContainer(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(verticalScrollState)
            .horizontalScroll(horizontalScrollState),
        ) {
          Text(
            text = logText,
            style = MaterialTheme.typography.bodySmall.copy(
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
              lineHeight = 14.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
          )
        }

        // Close button
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          Button(onClick = onDismissed) { Text("Close") }
        }
      }
    }
  }
}
