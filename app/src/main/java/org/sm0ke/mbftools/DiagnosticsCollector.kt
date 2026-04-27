package org.sm0ke.mbftools

import android.content.Context
import android.content.pm.PackageInfo
import android.net.ConnectivityManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsCollector {
    private const val TAG = "Diagnostics"
    private val exportTimeFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
    private const val DIAGNOSTIC_SHELL_TIMEOUT_MS = 5_000L
    private const val DIAGNOSTIC_ADB_TIMEOUT_MS = 8_000L
    private const val MAX_BEAT_SABER_LOG_FILES = 8
    private const val MAX_BEAT_SABER_LOG_LINES_PER_FILE = 60
    private const val MAX_BEAT_SABER_LOG_LINES_TOTAL = 400
    private const val DEFAULT_BEAT_SABER_PACKAGE = "com.beatgames.beatsaber"

    fun collect(context: Context): JSONObject {
        AppLog.init(context)
        debug("Starting diagnostics collection.")

        val deviceSnapshot = ensureConnectedDevice(context)
        val requestedGameId = normalizeGameId(AppPrefs.getGameId(context))
        debug("Requested Beat Saber package id: $requestedGameId")
        val resolvedGameId =
                resolveBeatSaberPackageId(
                        context,
                        deviceSnapshot.connectedDevice?.name,
                        requestedGameId
                )
        val beatSaber =
                collectBeatSaberPackageInfo(
                        context,
                        deviceSnapshot.connectedDevice?.name,
                        resolvedGameId,
                        requestedGameId
                )
        val modInfo =
                collectMods(
                        context,
                        deviceSnapshot.connectedDevice?.name,
                        resolvedGameId,
                        beatSaber
                )
        val beatSaberLogs =
                collectBeatSaberLogs(context, deviceSnapshot.connectedDevice?.name, resolvedGameId)
        val logLines = AppLog.lines()

        AppLog.info(
                TAG,
                "Collected diagnostics with device=${deviceSnapshot.connectedDevice?.name ?: "none"}, " +
                        "gameId=$resolvedGameId, beatSaberInstalled=${beatSaber.optBoolean("installed")}, " +
                        "mods=${modInfo.optInt("count")}, beatSaberLogs=${beatSaberLogs.optInt("lineCount")}."
        )

        return JSONObject()
                .put("exportedAt", exportTimeFormatter.format(Date()))
                .put("app", collectAppInfo(context))
                .put("device", collectDeviceInfo())
                .put("runtime", collectRuntimeInfo())
                .put("network", collectNetworkInfo(context))
                .put(
                        "setup",
                        collectSetupInfo(
                                context,
                                deviceSnapshot.connectedDevice?.name,
                                deviceSnapshot.allDevices
                        )
                )
                .put("beatSaber", beatSaber)
                .put("mods", modInfo)
                .put("beatSaberLogs", beatSaberLogs)
                .put("logStats", collectLogStats(logLines))
                .put("logs", JSONArray(logLines))
                .put("logsText", logLines.joinToString("\n"))
    }

    private fun collectAppInfo(context: Context): JSONObject {
        val info = collectPackageInfo(context, context.packageName)
        return JSONObject()
                .put("packageName", context.packageName)
                .put("versionName", info.optString("versionName"))
                .put("versionCode", info.optLong("versionCode"))
                .put("setupComplete", AppPrefs.isSetupComplete(context))
    }

    private fun collectDeviceInfo(): JSONObject {
        return JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("brand", Build.BRAND)
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("product", Build.PRODUCT)
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("release", Build.VERSION.RELEASE ?: "")
                .put("fingerprint", Build.FINGERPRINT ?: "")
    }

    private fun collectNetworkInfo(context: Context): JSONObject {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) manager?.activeNetwork
                else null
        val capabilities = network?.let { manager?.getNetworkCapabilities(it) }
        return JSONObject()
                .put("hasActiveNetwork", network != null)
                .put("hasWifiTransport", capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true)
                .put("hasInternetCapability", capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
    }

    private fun collectRuntimeInfo(): JSONObject {
        val runtime = Runtime.getRuntime()
        return JSONObject()
                .put("availableProcessors", runtime.availableProcessors())
                .put("maxMemoryBytes", runtime.maxMemory())
                .put("totalMemoryBytes", runtime.totalMemory())
                .put("freeMemoryBytes", runtime.freeMemory())
    }

    private fun collectSetupInfo(
            context: Context,
            connectedDeviceName: String?,
            allDevices: List<AdbDevice>
    ): JSONObject {
        return JSONObject()
                .put("currentGuideStep", AppPrefs.getCurrentGuideStep(context))
                .put("pairingPort", AppPrefs.getPairingPort(context))
                .put("debugPort", AppPrefs.getDebugPort(context))
                .put("setupComplete", AppPrefs.isSetupComplete(context))
                .put("developerModeEnabled", SetupState.isDeveloperModeEnabled(context))
                .put("wirelessDebuggingEnabled", SetupState.isWirelessDebuggingEnabled(context))
                .put("hasBridgePermissions", AdbManager.hasRequiredSettingsPermission(context))
                .put("connectedDevice", connectedDeviceName ?: "")
                .put(
                        "authorizedDevices",
                        JSONArray(
                                allDevices.filter { it.authorized }.map { device ->
                                    JSONObject()
                                            .put("name", device.name)
                                            .put("authorized", device.authorized)
                                }
                        )
                )
                .put(
                        "detectedDevices",
                        JSONArray(
                                allDevices.map { device ->
                                    JSONObject()
                                            .put("name", device.name)
                                            .put("authorized", device.authorized)
                                }
                        )
                )
    }

    private fun collectPackageInfo(context: Context, packageName: String): JSONObject {
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

        return JSONObject()
                .put("packageName", packageName)
                .put("installed", packageInfo != null)
                .put("versionName", packageInfo?.versionName ?: "")
                .put("versionCode", packageInfo?.longVersionCodeCompat() ?: 0L)
    }

    private fun collectBeatSaberPackageInfo(
            context: Context,
            deviceName: String?,
            packageName: String,
            requestedGameId: String
    ): JSONObject {
        val localInfo = collectPackageInfo(context, packageName)
        val adbInfo = collectPackageInfoFromAdb(context, deviceName, packageName)
        debug(
                "Beat Saber package probe for $packageName: localInstalled=${localInfo.optBoolean("installed")} " +
                        "localVersion=${localInfo.optString("versionName")}/${localInfo.optLong("versionCode")} " +
                        "adbInstalled=${adbInfo.optBoolean("installed")} adbVersion=${adbInfo.optString("versionName")}/${adbInfo.optLong("versionCode")}"
        )
        val installed = localInfo.optBoolean("installed") || adbInfo.optBoolean("installed")
        val versionName = localInfo.optString("versionName").ifBlank { adbInfo.optString("versionName") }
        val versionCode =
                localInfo.optLong("versionCode").takeIf { it > 0L }
                        ?: adbInfo.optLong("versionCode").takeIf { it > 0L }
                        ?: 0L

        if (installed) {
            return JSONObject()
                    .put("packageName", packageName)
                    .put("requestedPackageName", requestedGameId)
                    .put("installed", true)
                    .put("versionName", versionName)
                    .put("versionCode", versionCode)
                    .put(
                            "detectionSource",
                            when {
                                localInfo.optBoolean("installed") && adbInfo.optBoolean("installed") -> "package-manager+adb-shell"
                                localInfo.optBoolean("installed") -> "package-manager"
                                else -> "adb-shell-pm-path"
                            }
                    )
        }

        return JSONObject()
                .put("packageName", packageName)
                .put("requestedPackageName", requestedGameId)
                .put("installed", false)
                .put("versionName", "")
                .put("versionCode", 0L)
                .put("detectionSource", "not-found")
    }

    private fun collectMods(
            context: Context,
            deviceName: String?,
            gameId: String,
            beatSaberInfo: JSONObject
    ): JSONObject {
        val storageRoots = listOf("/sdcard", "/storage/emulated/0")
        val currentPackageDir =
                resolveCurrentPackagesDir(
                        context,
                        deviceName,
                        gameId,
                        beatSaberInfo.optString("versionName"),
                        beatSaberInfo.optLong("versionCode")
                )
        debug(
                "Collecting Beat Saber mods for $gameId with version=${beatSaberInfo.optString("versionName")}/${beatSaberInfo.optLong("versionCode")} " +
                        "resolvedPackageDir=${currentPackageDir ?: "none"} device=${deviceName ?: "none"}"
        )
        val directories = JSONArray()
        val modNames = linkedSetOf<String>()
        var source = "none"

        if (!deviceName.isNullOrBlank()) {
            if (!currentPackageDir.isNullOrBlank()) {
                val result =
                        runCatching {
                                    AdbManager.shellArgs(
                                            context,
                                            deviceName,
                                            listOf(
                                                    "find",
                                                    currentPackageDir,
                                                    "-mindepth",
                                                    "1",
                                                    "-maxdepth",
                                                    "1",
                                                    "-type",
                                                    "d"
                                            ),
                                            DIAGNOSTIC_SHELL_TIMEOUT_MS
                                    )
                                }
                                .getOrNull()
                debugCommandResult("mods-packages-find", "find $currentPackageDir -mindepth 1 -maxdepth 1 -type d", result)
                val lines =
                        result?.stdout
                                ?.lineSequence()
                                ?.map { it.trim() }
                                ?.filter { it.isNotEmpty() }
                                ?.toList()
                                .orEmpty()
                if (lines.isNotEmpty()) {
                    directories.put(currentPackageDir)
                    source = "packages-current-version"
                    debug("Found ${lines.size} mod package directories in $currentPackageDir.")
                    lines.forEach { line ->
                        modNames.add(File(line).name)
                    }
                } else {
                    debug("No mod package directories found in $currentPackageDir.")
                }
            }

            if (modNames.isEmpty()) {
                storageRoots
                        .map { "$it/ModData/$gameId/Mods" }
                        .forEach { dir ->
                            val result =
                                    runCatching {
                                                AdbManager.shellArgs(
                                                        context,
                                                        deviceName,
                                                        listOf(
                                                                "find",
                                                                dir,
                                                                "-mindepth",
                                                                "1",
                                                                "-maxdepth",
                                                                "1",
                                                                "-type",
                                                                "d"
                                                        ),
                                                        DIAGNOSTIC_SHELL_TIMEOUT_MS
                                                )
                                            }
                                            .getOrNull()
                            debugCommandResult("mods-fallback-find", "find $dir -mindepth 1 -maxdepth 1 -type d", result)
                            val lines =
                                    result?.stdout
                                            ?.lineSequence()
                                            ?.map { it.trim() }
                                            ?.filter { it.isNotEmpty() }
                                            ?.toList()
                                            .orEmpty()
                            if (lines.isNotEmpty()) {
                                directories.put(dir)
                                source = "mods-directory-fallback"
                                debug("Found ${lines.size} fallback mod directories in $dir.")
                                lines.forEach { line -> modNames.add(File(line).name) }
                            } else {
                                debug("No fallback mod directories found in $dir.")
                            }
                        }
            }
        }

        debug("Mod collection finished: source=$source count=${modNames.size} sample=${modNames.take(10)}")

        return JSONObject()
                .put("source", source)
                .put("directories", directories)
                .put("count", modNames.size)
                .put("items", JSONArray(modNames.toList()))
    }

    private fun collectLogStats(lines: List<String>): JSONObject {
        val warnCount = lines.count { it.contains("[WARN/") }
        val errorCount = lines.count { it.contains("[ERROR/") }
        val recentErrors =
                lines.filter { it.contains("[WARN/") || it.contains("[ERROR/") }
                        .takeLast(10)
        return JSONObject()
                .put("lineCount", lines.size)
                .put("warnCount", warnCount)
                .put("errorCount", errorCount)
                .put("recentProblems", JSONArray(recentErrors))
    }

    private fun collectBeatSaberLogs(
            context: Context,
            deviceName: String?,
            gameId: String
    ): JSONObject {
        if (deviceName.isNullOrBlank()) {
            return JSONObject()
                    .put("source", "")
                    .put("lineCount", 0)
                    .put("interestingCount", 0)
                    .put("interesting", JSONArray())
                    .put("lines", JSONArray())
        }

        val fileLogs = collectBeatSaberFileLogs(context, deviceName, gameId)
        if (fileLogs.optInt("lineCount") > 0) {
            return fileLogs
        }

        return collectBeatSaberLogcat(context, deviceName, gameId)
    }

    private fun collectBeatSaberFileLogs(
            context: Context,
            deviceName: String,
            gameId: String
    ): JSONObject {
        val candidateDirs =
                listOf(
                        "/sdcard/ModData/$gameId/logs",
                        "/sdcard/ModData/$gameId/logs2",
                        "/storage/emulated/0/ModData/$gameId/logs",
                        "/storage/emulated/0/ModData/$gameId/logs2"
                )
        debug("Collecting Beat Saber logs for $gameId from directories=$candidateDirs")
        val logFiles =
                candidateDirs
                        .flatMap { dir ->
                            val result =
                                    runCatching {
                                                AdbManager.shellArgs(
                                                        context,
                                                        deviceName,
                                                        listOf("ls", "-1", dir),
                                                        DIAGNOSTIC_SHELL_TIMEOUT_MS
                                                )
                                            }
                                            .getOrNull()
                            debugCommandResult("logs-ls", "ls -1 $dir", result)
                            result?.stdout
                                    ?.lineSequence()
                                    ?.map { it.trim() }
                                    ?.filter { it.endsWith(".log", ignoreCase = true) }
                                    ?.map { "$dir/$it" }
                                    ?.toList()
                                    .orEmpty()
                        }
                        .distinct()
                        .take(MAX_BEAT_SABER_LOG_FILES)
        debug("Selected Beat Saber log files: $logFiles")

        val filesJson = JSONArray()
        val lines = mutableListOf<String>()

        logFiles.forEach { path ->
            val result =
                    runCatching {
                                AdbManager.shellArgs(
                                        context,
                                        deviceName,
                                        listOf(
                                                "tail",
                                                "-n",
                                                MAX_BEAT_SABER_LOG_LINES_PER_FILE.toString(),
                                                path
                                        ),
                                        DIAGNOSTIC_SHELL_TIMEOUT_MS
                                )
                            }
                            .getOrNull()
            debugCommandResult("logs-tail", "tail -n $MAX_BEAT_SABER_LOG_LINES_PER_FILE $path", result)
            val fileLines =
                    result?.stdout
                            ?.lineSequence()
                            ?.map { it.trimEnd() }
                            ?.filter { it.isNotBlank() }
                            ?.toList()
                            .orEmpty()
            filesJson.put(
                    JSONObject()
                            .put("path", path)
                            .put("lineCount", fileLines.size)
            )
            val fileName = File(path).name
            fileLines.forEach { line -> lines.add("[$fileName] $line") }
        }

        val cappedLines = lines.takeLast(MAX_BEAT_SABER_LOG_LINES_TOTAL)
        val interesting = findInterestingBeatSaberLines(cappedLines)
        debug("Beat Saber file log collection finished: files=${logFiles.size} lineCount=${cappedLines.size} interesting=${interesting.size}")

        return JSONObject()
                .put("source", "moddata-file-logs")
                .put("directories", JSONArray(candidateDirs))
                .put("files", filesJson)
                .put("lineCount", cappedLines.size)
                .put("interestingCount", interesting.size)
                .put("interesting", JSONArray(interesting))
                .put("lines", JSONArray(cappedLines))
    }

    private fun collectPackageInfoFromAdb(
            context: Context,
            deviceName: String?,
            packageName: String
    ): JSONObject {
        if (deviceName.isNullOrBlank()) {
            return JSONObject()
                    .put("packageName", packageName)
                    .put("installed", false)
                    .put("versionName", "")
                    .put("versionCode", 0L)
        }

        val pmPathResult =
                runCatching {
                            AdbManager.shellArgs(
                                    context,
                                    deviceName,
                                    listOf("pm", "path", packageName),
                                    DIAGNOSTIC_SHELL_TIMEOUT_MS
                            )
                        }
                        .getOrNull()
        debugCommandResult("package-pm-path", "pm path $packageName", pmPathResult)
        val installed = pmPathResult?.stdout?.contains("package:", ignoreCase = true) == true
        var versionName = ""
        var versionCode = 0L

        if (installed) {
            val dumpsys =
                    runCatching {
                                AdbManager.shellArgs(
                                        context,
                                        deviceName,
                                        listOf("dumpsys", "package", packageName),
                                        DIAGNOSTIC_ADB_TIMEOUT_MS
                                )
                            }
                            .getOrNull()
                            ?.stdout
                            .orEmpty()
            debug("dumpsys package $packageName length=${dumpsys.length}")
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
        }

        return JSONObject()
                .put("packageName", packageName)
                .put("installed", installed)
                .put("versionName", versionName)
                .put("versionCode", versionCode)
    }

    private fun resolveCurrentPackagesDir(
            context: Context,
            deviceName: String?,
            gameId: String,
            versionName: String,
            versionCode: Long
    ): String? {
        if (deviceName.isNullOrBlank()) {
            return null
        }

        val storageRoots = listOf("/sdcard", "/storage/emulated/0")
        val exactName =
                if (versionName.isNotBlank() && versionCode > 0L) {
                    "${versionName}_${versionCode}"
                } else {
                    ""
                }
        debug("Resolving current packages dir for $gameId expectedExact=$exactName versionName=$versionName versionCode=$versionCode")

        storageRoots.forEach { root ->
            val packagesRoot = "$root/ModData/$gameId/Packages"
            val result =
                    runCatching {
                                AdbManager.shellArgs(
                                        context,
                                        deviceName,
                                        listOf("ls", "-1", packagesRoot),
                                        DIAGNOSTIC_SHELL_TIMEOUT_MS
                                )
                            }
                            .getOrNull()
            debugCommandResult("packages-ls", "ls -1 $packagesRoot", result)
            val entries = result?.stdout
                            ?.lineSequence()
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?.toList()
                            .orEmpty()
            if (entries.isEmpty()) {
                debug("No package entries found in $packagesRoot.")
                return@forEach
            }
            debug("Package entries in $packagesRoot: ${entries.take(20)}")

            val exactMatch = entries.firstOrNull { it == exactName }
            if (exactMatch != null) {
                debug("Using exact package folder match $exactMatch in $packagesRoot.")
                return "$packagesRoot/$exactMatch"
            }

            if (versionName.isNotBlank()) {
                val prefixMatch = entries.firstOrNull { it.startsWith("${versionName}_") }
                if (prefixMatch != null) {
                    debug("Using prefix package folder match $prefixMatch in $packagesRoot.")
                    return "$packagesRoot/$prefixMatch"
                }
            }
        }

        debug("Could not resolve current package folder for $gameId.")
        return null
    }

    private fun collectBeatSaberLogcat(
            context: Context,
            deviceName: String,
            gameId: String
    ): JSONObject {
        val result =
                runCatching {
                            AdbManager.logcat(
                                    context,
                                    deviceName,
                                    1200,
                                    DIAGNOSTIC_ADB_TIMEOUT_MS
                            )
                        }
                        .getOrNull()
        debugCommandResult("logcat", "logcat -d -t 1200", result)
        val keywords =
                listOf(
                        gameId,
                        "beat saber",
                        "beatsaber",
                        "songcore",
                        "questui",
                        "modsbeforefriday",
                        "unity",
                        "crash",
                        "exception",
                        "nullreference"
                )
        val lines =
                result?.stdout
                        ?.lineSequence()
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.filter { line ->
                            keywords.any { keyword -> line.contains(keyword, ignoreCase = true) }
                        }
                        ?.toList()
                        ?.takeLast(MAX_BEAT_SABER_LOG_LINES_TOTAL)
                        .orEmpty()
        val interesting = findInterestingBeatSaberLines(lines)
        debug("Beat Saber logcat fallback finished: lineCount=${lines.size} interesting=${interesting.size}")

        return JSONObject()
                .put("source", "adb-logcat")
                .put("lineCount", lines.size)
                .put("interestingCount", interesting.size)
                .put("interesting", JSONArray(interesting))
                .put("lines", JSONArray(ArrayList(lines)))
    }

    private fun findInterestingBeatSaberLines(lines: List<String>): List<String> {
        val indicators =
                listOf(
                        "fatal",
                        "exception",
                        "crash",
                        "error",
                        "failed",
                        "nullreference",
                        "abort",
                        "segmentation"
                )
        return lines.filter { line ->
                    indicators.any { indicator -> line.contains(indicator, ignoreCase = true) }
                }
                .takeLast(20)
    }

    private fun normalizeGameId(gameId: String): String {
        return gameId.trim().ifBlank { DEFAULT_BEAT_SABER_PACKAGE }
    }

    private fun resolveBeatSaberPackageId(
            context: Context,
            deviceName: String?,
            requestedGameId: String
    ): String {
        val candidates =
                linkedSetOf(
                        normalizeGameId(requestedGameId),
                        DEFAULT_BEAT_SABER_PACKAGE
                )
        debug("Resolving Beat Saber package id from candidates=$candidates")

        candidates.forEach { candidate ->
            if (collectPackageInfo(context, candidate).optBoolean("installed")) {
                debug("Resolved Beat Saber package id from package manager: $candidate")
                return candidate
            }
        }

        if (!deviceName.isNullOrBlank()) {
            candidates.forEach { candidate ->
                val installed =
                        runCatching {
                                    AdbManager.shellArgs(
                                            context,
                                            deviceName,
                                            listOf("pm", "path", candidate),
                                            DIAGNOSTIC_SHELL_TIMEOUT_MS
                                    )
                                }
                                .getOrNull()
                                ?.stdout
                                ?.contains("package:", ignoreCase = true) == true
                if (installed) {
                    debug("Resolved Beat Saber package id from adb shell: $candidate")
                    return candidate
                }
            }
        }

        debug("Falling back to default Beat Saber package id $DEFAULT_BEAT_SABER_PACKAGE")
        return DEFAULT_BEAT_SABER_PACKAGE
    }

    private fun ensureConnectedDevice(context: Context): DiagnosticsDeviceSnapshot {
        var allDevices = runCatching { AdbManager.getDevices(context) }.getOrDefault(emptyList())
        var connectedDevice = allDevices.firstOrNull { it.authorized }
        debug("Initial ADB devices: ${allDevices.map { "${it.name}:${if (it.authorized) "authorized" else "unauthorized"}" }}")
        if (connectedDevice != null) {
            return DiagnosticsDeviceSnapshot(connectedDevice = connectedDevice, allDevices = allDevices)
        }

        val savedDebugPort = AppPrefs.getDebugPort(context).trim().toIntOrNull()
        val initialPort = savedDebugPort ?: runCatching { AdbManager.detectDebugPort(context) }.getOrDefault(0)
        if (initialPort <= 0) {
            AppLog.warn("Diagnostics", "No authorized ADB device and no wireless debug port available for reconnect.")
            return DiagnosticsDeviceSnapshot(allDevices = allDevices)
        }

        AppLog.info("Diagnostics", "Attempting reconnect for diagnostics export on wireless debug port $initialPort.")
        val reconnectAttempt = connectWithRecoveredDebugPort(context, initialPort)
        allDevices = runCatching { AdbManager.getDevices(context) }.getOrDefault(allDevices)
        connectedDevice = allDevices.firstOrNull { it.authorized }
        if (connectedDevice != null) {
            AppLog.info("Diagnostics", "Diagnostics reconnect succeeded for ${connectedDevice.name} on port ${reconnectAttempt.finalPort}.")
        } else {
            AppLog.warn(
                    "Diagnostics",
                    "Diagnostics reconnect did not authorize a device. finalPort=${reconnectAttempt.finalPort}, " +
                            "result=${reconnectAttempt.result.getOrNull()?.bestMessage("connect failed") ?: reconnectAttempt.result.exceptionOrNull()?.message ?: "unknown"}"
            )
        }
        return DiagnosticsDeviceSnapshot(connectedDevice = connectedDevice, allDevices = allDevices)
    }

    private fun connectWithRecoveredDebugPort(
            context: Context,
            initialPort: Int
    ): DiagnosticsReconnectAttempt {
        AppPrefs.setDebugPort(context, initialPort.toString())
        val initialResult = runCatching { AdbManager.connect(context, initialPort) }
        debug("Initial diagnostics reconnect attempt on port $initialPort: ${initialResult.getOrNull()?.bestMessage("connect failed") ?: initialResult.exceptionOrNull()?.message ?: "unknown"}")
        if (initialResult.isSuccess && isSuccessfulConnect(initialResult.getOrThrow())) {
            return DiagnosticsReconnectAttempt(finalPort = initialPort, result = initialResult)
        }

        val redetectedPort = runCatching { AdbManager.detectDebugPort(context) }.getOrDefault(0)
        debug("Redetected wireless debug port during diagnostics reconnect: $redetectedPort")
        if (redetectedPort <= 0 || redetectedPort == initialPort) {
            return DiagnosticsReconnectAttempt(finalPort = initialPort, result = initialResult)
        }

        AppPrefs.setDebugPort(context, redetectedPort.toString())
        val retryResult = runCatching { AdbManager.connect(context, redetectedPort) }
        debug("Retry diagnostics reconnect attempt on port $redetectedPort: ${retryResult.getOrNull()?.bestMessage("connect failed") ?: retryResult.exceptionOrNull()?.message ?: "unknown"}")
        return DiagnosticsReconnectAttempt(
                finalPort = redetectedPort,
                result = retryResult,
                redetectedPort = redetectedPort
        )
    }

    private fun isSuccessfulConnect(command: AdbCommandResult): Boolean {
        return command.exitCode == 0 && command.stdout.contains("connected", ignoreCase = true)
    }

    private data class DiagnosticsDeviceSnapshot(
            val connectedDevice: AdbDevice? = null,
            val allDevices: List<AdbDevice> = emptyList()
    )

    private data class DiagnosticsReconnectAttempt(
            val finalPort: Int,
            val result: Result<AdbCommandResult>,
            val redetectedPort: Int? = null
    )

    private fun debug(message: String) {
        if (AppLog.isVerboseLoggingEnabled()) {
            AppLog.info(TAG, message)
        }
    }

    private fun debugCommandResult(label: String, command: String, result: AdbCommandResult?) {
        if (!AppLog.isVerboseLoggingEnabled()) {
            return
        }
        if (result == null) {
            AppLog.warn(TAG, "$label returned no result for command: $command")
            return
        }
        val stdoutPreview = preview(result.stdout)
        val stderrPreview = preview(result.stderr)
        AppLog.info(
                TAG,
                "$label exit=${result.exitCode} command=$command stdout=${stdoutPreview.ifBlank { "<empty>" }} stderr=${stderrPreview.ifBlank { "<empty>" }}"
        )
    }

    private fun preview(value: String): String {
        val normalized = value.replace('\n', ' ').replace('\r', ' ').trim()
        return if (normalized.length <= 220) normalized else normalized.take(220) + "..."
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
