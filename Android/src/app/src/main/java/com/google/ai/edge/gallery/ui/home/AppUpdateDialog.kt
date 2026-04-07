package com.google.ai.edge.gallery.ui.home

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "AppUpdateDialog"

@Composable
fun AppUpdateDialog(
    updateInfo: AppUpdateInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var isDownloading by remember { mutableStateOf(false) }

    AlertDialog(
        icon = {
            Icon(
                Icons.Rounded.SystemUpdate,
                contentDescription = "Update available",
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text("Update verfügbar")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Version ${updateInfo.versionName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (updateInfo.releaseNotes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        updateInfo.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (isDownloading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Text(
                        "Downloading...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onDismissRequest = {
            if (!isDownloading) onDismiss()
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isDownloading = true
                    downloadAndInstallApk(context, updateInfo.downloadUrl, updateInfo.versionName)
                },
                enabled = !isDownloading,
            ) {
                Text(if (isDownloading) "Downloading..." else "Update")
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = {
                    AppUpdateChecker.dismissVersion(context, updateInfo.versionCode)
                    onDismiss()
                }) {
                    Text("Später")
                }
            }
        },
    )
}

private fun downloadAndInstallApk(context: Context, url: String, versionName: String) {
    try {
        val fileName = "echo-v${versionName}-release.apk"

        // Clean up old downloaded APKs
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir?.listFiles()?.forEach { file ->
            if (file.name.startsWith("echo-") && file.name.endsWith(".apk")) {
                file.delete()
            }
        }

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Echo Update v$versionName")
            .setDescription("Downloading update...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        // Register receiver to install APK once download completes
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: return
                if (id != downloadId) return

                context.unregisterReceiver(this)

                val file = File(downloadsDir, fileName)
                if (file.exists()) {
                    installApk(context, file)
                } else {
                    Log.e(TAG, "Downloaded APK file not found: ${file.absolutePath}")
                    Toast.makeText(context, "Download failed", Toast.LENGTH_LONG).show()
                }
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED,
        )

        Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Log.e(TAG, "Error downloading APK", e)
        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun installApk(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e(TAG, "Error installing APK", e)
        Toast.makeText(context, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
