package com.google.ai.edge.gallery.ui.home

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "AppUpdateChecker"
private const val GITHUB_API_URL =
    "https://api.github.com/repos/mcmarius11/gallery/releases/latest"
private const val PREFS_NAME = "app_update_prefs"
private const val PREF_DISMISSED_VERSION = "dismissed_version_code"

data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val releaseNotes: String,
)

object AppUpdateChecker {

    suspend fun checkForUpdate(context: Context): AppUpdateInfo? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val currentVersionCode = getLocalVersionCode(context)
            Log.d(TAG, "Current versionCode: $currentVersionCode")

            connection = URL(GITHUB_API_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "GitHub API returned HTTP $responseCode")
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = org.json.JSONObject(response)

            val tagName = json.optString("tag_name", "") // e.g. "v1.0.11-23"
            if (tagName.isBlank()) {
                Log.e(TAG, "No tag_name in release response")
                return@withContext null
            }
            val remoteVersionCode = parseVersionCode(tagName)
            val remoteVersionName = parseVersionName(tagName)
            val releaseNotes = json.optString("body", "")

            Log.d(TAG, "Remote versionCode: $remoteVersionCode, tag: $tagName")

            if (remoteVersionCode == null || remoteVersionCode <= currentVersionCode) {
                Log.d(TAG, "No update available")
                return@withContext null
            }

            // Check if user already dismissed this version
            val dismissedVersion = getDismissedVersion(context)
            if (dismissedVersion == remoteVersionCode) {
                Log.d(TAG, "User dismissed version $remoteVersionCode, skipping")
                return@withContext null
            }

            // Find the APK asset
            val assets = json.optJSONArray("assets") ?: JSONArray()
            val apkUrl = findApkDownloadUrl(assets)
            if (apkUrl == null) {
                Log.e(TAG, "No APK asset found in release")
                return@withContext null
            }

            AppUpdateInfo(
                versionName = remoteVersionName ?: tagName,
                versionCode = remoteVersionCode,
                downloadUrl = apkUrl,
                releaseNotes = releaseNotes,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun dismissVersion(context: Context, versionCode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_DISMISSED_VERSION, versionCode)
            .apply()
    }

    private fun getDismissedVersion(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_DISMISSED_VERSION, -1)
    }

    private fun getLocalVersionCode(context: Context): Int {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read local versionCode", e)
            BuildConfig.VERSION_CODE
        }
    }

    // Tag format: "v1.0.11-23" where 23 is the versionCode
    private fun parseVersionCode(tag: String): Int? {
        val match = Regex("""v[\d.]+[-_](\d+)""").find(tag) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun parseVersionName(tag: String): String? {
        val match = Regex("""v([\d.]+)""").find(tag) ?: return null
        return match.groupValues[1]
    }

    private fun findApkDownloadUrl(assets: JSONArray): String? {
        // Prefer release APK, fall back to any APK
        var fallbackUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (name.endsWith("-release.apk")) {
                return asset.getString("browser_download_url")
            }
            if (fallbackUrl == null && name.endsWith(".apk")) {
                fallbackUrl = asset.getString("browser_download_url")
            }
        }
        return fallbackUrl
    }
}
