package org.sm0ke.mbftools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class GitHubReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val sizeBytes: Long
)

data class GitHubRelease(
        val tagName: String,
        val name: String,
        val htmlUrl: String,
        val publishedAt: String,
        val body: String,
        val asset: GitHubReleaseAsset
) {
    val versionLabel: String
        get() = tagName.ifBlank { name.ifBlank { asset.name } }
}

data class AppUpdateCheck(
        val currentVersionName: String,
        val currentVersionCode: Long,
        val release: GitHubRelease,
        val comparison: Int?
) {
    val updateAvailable: Boolean
        get() = comparison != null && comparison > 0
}

object AppUpdater {
    private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/SmokeSlate/MBF-Tools/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val USER_AGENT = "MBF-Tools-Updater"

    fun checkForUpdate(context: Context): AppUpdateCheck {
        val release = fetchLatestRelease()
        val packageInfo = currentPackageInfo(context)
        val currentVersionName = packageInfo.versionName ?: ""
        val currentVersionCode = packageInfo.longVersionCodeCompat()
        val comparison = compareVersionLabels(release.versionLabel, currentVersionName)

        return AppUpdateCheck(
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode,
                release = release,
                comparison = comparison
        )
    }

    fun downloadApk(context: Context, release: GitHubRelease): File {
        val asset = release.asset
        require(asset.downloadUrl.isNotBlank()) { "The update APK download URL is missing." }

        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updateDir, safeFileName(asset.name))
        val partialFile = File(updateDir, "${apkFile.name}.download")
        if (apkFile.exists()) {
            apkFile.delete()
        }
        if (partialFile.exists()) {
            partialFile.delete()
        }

        val connection = openConnection(asset.downloadUrl, "application/octet-stream")
        val downloadedFile =
                try {
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException(readError(connection))
                    }
                    connection.inputStream.use { input ->
                        partialFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (!partialFile.renameTo(apkFile)) {
                        partialFile.copyTo(apkFile, overwrite = true)
                    }
                    apkFile
                } finally {
                    connection.disconnect()
                    if (partialFile.exists()) {
                        partialFile.delete()
                    }
                }

        if (downloadedFile.length() <= 0L) {
            downloadedFile.delete()
            throw IllegalStateException("The downloaded update APK was empty.")
        }

        return downloadedFile
    }

    fun createInstallIntent(context: Context, apkFile: File): Intent {
        val uri =
                FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile
                )
        return Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    internal fun compareVersionLabels(latestLabel: String, currentLabel: String): Int? {
        val latest = extractVersionParts(latestLabel)
        val current = extractVersionParts(currentLabel)
        if (latest.isEmpty() || current.isEmpty()) {
            return null
        }

        val maxLength = maxOf(latest.size, current.size)
        for (index in 0 until maxLength) {
            val latestPart = latest.getOrElse(index) { 0 }
            val currentPart = current.getOrElse(index) { 0 }
            if (latestPart != currentPart) {
                return latestPart.compareTo(currentPart)
            }
        }
        return 0
    }

    private fun fetchLatestRelease(): GitHubRelease {
        val connection = openConnection(LATEST_RELEASE_URL, "application/vnd.github+json")
        return try {
            val body =
                    BufferedReader(
                                    InputStreamReader(
                                            if (connection.responseCode in 200..299) {
                                                connection.inputStream
                                            } else {
                                                connection.errorStream
                                            },
                                            Charsets.UTF_8
                                    )
                            )
                            .use { it.readText() }

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(
                        body.ifBlank { "GitHub returned HTTP ${connection.responseCode}." }
                )
            }

            parseRelease(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject): GitHubRelease {
        val asset = selectApkAsset(json)
        return GitHubRelease(
                tagName = json.optString("tag_name"),
                name = json.optString("name"),
                htmlUrl = json.optString("html_url"),
                publishedAt = json.optString("published_at"),
                body = json.optString("body"),
                asset = asset
        )
    }

    private fun selectApkAsset(json: JSONObject): GitHubReleaseAsset {
        val assets = json.optJSONArray("assets")
                ?: throw IllegalStateException("The latest GitHub release does not include assets.")
        val apkAssets =
                (0 until assets.length())
                        .mapNotNull { index -> assets.optJSONObject(index) }
                        .mapNotNull { asset ->
                            val name = asset.optString("name")
                            val downloadUrl = asset.optString("browser_download_url")
                            if (name.endsWith(".apk", ignoreCase = true) &&
                                            downloadUrl.isNotBlank()
                            ) {
                                GitHubReleaseAsset(
                                        name = name,
                                        downloadUrl = downloadUrl,
                                        sizeBytes = asset.optLong("size", 0L)
                                )
                            } else {
                                null
                            }
                        }

        if (apkAssets.isEmpty()) {
            throw IllegalStateException("The latest GitHub release does not include an APK asset.")
        }

        val preferredAssets = apkAssets.filterNot { it.name.contains("debug", ignoreCase = true) }
                .ifEmpty { apkAssets }
        return preferredAssets.maxByOrNull { apkAssetScore(it.name) } ?: preferredAssets.first()
    }

    private fun apkAssetScore(name: String): Int {
        var score = 0
        if (name.contains("release", ignoreCase = true)) {
            score += 4
        }
        if (name.contains("mbf", ignoreCase = true)) {
            score += 2
        }
        if (!name.contains("debug", ignoreCase = true)) {
            score += 1
        }
        return score
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    private fun readError(connection: HttpURLConnection): String {
        return runCatching {
                    BufferedReader(
                                    InputStreamReader(
                                            connection.errorStream ?: connection.inputStream,
                                            Charsets.UTF_8
                                    )
                            )
                            .use { it.readText() }
                }
                .getOrDefault("")
                .ifBlank { "Download failed with HTTP ${connection.responseCode}." }
    }

    private fun safeFileName(name: String): String {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return safeName.ifBlank { "mbf-tools-update.apk" }
    }

    private fun extractVersionParts(label: String): List<Int> {
        val version =
                Regex("""(?i)(?:^|[^0-9])v?(\d+(?:\.\d+){0,3})(?:[^0-9]|$)""")
                        .find(label)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: return emptyList()
        return version.split('.').mapNotNull { it.toIntOrNull() }
    }

    private fun currentPackageInfo(context: Context): PackageInfo {
        return if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    }

    private fun PackageInfo.longVersionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= 28) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
    }
}
