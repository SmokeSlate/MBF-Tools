package org.sm0ke.mbftools

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build

object BeatSaberVersionResolver {
    fun resolveVersionTag(
            context: Context,
            packageName: String,
            deviceName: String? = null
    ): String? {
        val localInfo = resolveLocalVersionInfo(context, packageName)
        if (localInfo != null) {
            return localInfo.versionTag
        }

        return resolveAdbVersionInfo(context, packageName, deviceName)?.versionTag
    }

    private fun resolveLocalVersionInfo(context: Context, packageName: String): VersionInfo? {
        val packageInfo =
                runCatching {
                            if (Build.VERSION.SDK_INT >= 33) {
                                context.packageManager.getPackageInfo(
                                        packageName,
                                        android.content.pm.PackageManager.PackageInfoFlags.of(0)
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                context.packageManager.getPackageInfo(packageName, 0)
                            }
                        }
                        .getOrNull()
                        ?: return null

        val versionName = packageInfo.versionName.orEmpty().trim()
        val versionCode = packageInfo.longVersionCodeCompat()
        return VersionInfo.from(versionName, versionCode)
    }

    private fun resolveAdbVersionInfo(
            context: Context,
            packageName: String,
            deviceName: String?
    ): VersionInfo? {
        if (deviceName.isNullOrBlank()) {
            return null
        }

        val pmPathResult =
                runCatching {
                            AdbManager.shellArgs(
                                    context,
                                    deviceName,
                                    listOf("pm", "path", packageName),
                                    5_000L
                            )
                        }
                        .getOrNull()
                        ?: return null

        val installed = pmPathResult.stdout.contains("package:", ignoreCase = true)
        if (!installed) {
            return null
        }

        val dumpsys =
                runCatching {
                            AdbManager.shellArgs(
                                    context,
                                    deviceName,
                                    listOf("dumpsys", "package", packageName),
                                    8_000L
                            )
                        }
                        .getOrNull()
                        ?.stdout
                        .orEmpty()

        var versionName = ""
        var versionCode = 0L
        dumpsys.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (versionName.isBlank() && trimmed.startsWith("versionName=")) {
                versionName = trimmed.substringAfter("versionName=").trim()
            }
            if (versionCode <= 0L && trimmed.contains("versionCode=")) {
                versionCode =
                        trimmed.substringAfter("versionCode=")
                                .substringBefore(" ")
                                .trim()
                                .toLongOrNull()
                                ?: versionCode
            }
        }

        return VersionInfo.from(versionName, versionCode)
    }

    private data class VersionInfo(val versionTag: String) {
        companion object {
            fun from(versionName: String, versionCode: Long): VersionInfo? {
                val normalizedName = versionName.trim()
                if (normalizedName.isBlank() || versionCode <= 0L) {
                    return null
                }
                return VersionInfo("${normalizedName}_${versionCode}")
            }
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
