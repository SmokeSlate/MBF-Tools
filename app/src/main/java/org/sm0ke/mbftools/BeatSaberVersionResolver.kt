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
        AppLog.info(
                "VersionResolver",
                "Resolving Beat Saber version for package=$packageName device=${deviceName ?: "<none>"}"
        )
        val localInfo = resolveLocalVersionInfo(context, packageName)
        if (localInfo != null) {
            AppLog.info(
                    "VersionResolver",
                    "Resolved locally: versionName=${localInfo.versionName} versionCode=${localInfo.versionCode} versionTag=${localInfo.versionTag}"
            )
            return localInfo.versionTag
        }

        AppLog.warn(
                "VersionResolver",
                "Local package lookup did not resolve a usable version for $packageName. Falling back to ADB."
        )

        val adbInfo = resolveAdbVersionInfo(context, packageName, deviceName)
        if (adbInfo != null) {
            AppLog.info(
                    "VersionResolver",
                    "Resolved over ADB: versionName=${adbInfo.versionName} versionCode=${adbInfo.versionCode} versionTag=${adbInfo.versionTag}"
            )
        } else {
            AppLog.warn(
                    "VersionResolver",
                    "ADB version lookup did not resolve a usable version for $packageName."
            )
        }
        return adbInfo?.versionTag
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
                        .onFailure {
                            AppLog.warn(
                                    "VersionResolver",
                                    "Local package lookup failed for $packageName: ${it.message ?: "unknown error"}"
                            )
                        }
                        .getOrNull()
                        ?: return null

        val versionName = packageInfo.versionName.orEmpty().trim()
        val versionCode = packageInfo.longVersionCodeCompat()
        AppLog.info(
                "VersionResolver",
                "Local package lookup returned versionName=${versionName.ifBlank { "<blank>" }} versionCode=$versionCode"
        )
        return VersionInfo.from(versionName, versionCode)
    }

    private fun resolveAdbVersionInfo(
            context: Context,
            packageName: String,
            deviceName: String?
    ): VersionInfo? {
        if (deviceName.isNullOrBlank()) {
            AppLog.warn(
                    "VersionResolver",
                    "ADB version lookup skipped because no connected device name was available."
            )
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
                        .onFailure {
                            AppLog.warn(
                                    "VersionResolver",
                                    "ADB pm path lookup failed for $packageName on $deviceName: ${it.message ?: "unknown error"}"
                            )
                        }
                        .getOrNull()
                        ?: return null

        val installed = pmPathResult.stdout.contains("package:", ignoreCase = true)
        if (!installed) {
            AppLog.warn(
                    "VersionResolver",
                    "ADB reports $packageName is not installed on $deviceName."
            )
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
                        .onFailure {
                            AppLog.warn(
                                    "VersionResolver",
                                    "ADB dumpsys lookup failed for $packageName on $deviceName: ${it.message ?: "unknown error"}"
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

    private data class VersionInfo(
            val versionTag: String,
            val versionName: String,
            val versionCode: Long
    ) {
        companion object {
            fun from(versionName: String, versionCode: Long): VersionInfo? {
                val normalizedName = versionName.trim()
                if (normalizedName.isBlank() || versionCode <= 0L) {
                    AppLog.warn(
                            "VersionResolver",
                            "Version info was incomplete: versionName=${normalizedName.ifBlank { "<blank>" }} versionCode=$versionCode"
                    )
                    return null
                }
                return VersionInfo(
                        versionTag = "${normalizedName}_${versionCode}",
                        versionName = normalizedName,
                        versionCode = versionCode
                )
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
