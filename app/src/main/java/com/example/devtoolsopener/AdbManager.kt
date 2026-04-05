package com.example.devtoolsopener

import android.content.Context
import android.provider.Settings
import java.io.File

data class AdbCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    fun bestMessage(fallback: String): String {
        val preferred = stdout.trim().ifEmpty { stderr.trim() }
        return preferred.ifEmpty { fallback }
    }
}

data class AdbDevice(
    val name: String,
    val authorized: Boolean
)

object AdbManager {
    private const val ADB_PORT = 5037
    private const val ADB_WIFI_KEY = "adb_wifi_enabled"

    @Volatile
    private var serverProcess: Process? = null

    @Synchronized
    private fun ensureServer(context: Context) {
        if (serverProcess?.isAlive == true) {
            return
        }

        val builder = ProcessBuilder(
            adbPath(context),
            "-P",
            ADB_PORT.toString(),
            "server",
            "nodaemon"
        )
        builder.directory(context.filesDir)
        builder.environment()["HOME"] = context.filesDir.absolutePath
        builder.environment()["TMPDIR"] = context.cacheDir.absolutePath
        builder.environment()["ADB_MDNS"] = "0"
        builder.environment()["ADB_MDNS_AUTO_CONNECT"] = ""
        serverProcess = builder.start()
        Thread.sleep(500)
    }

    fun hasRequiredSettingsPermission(context: Context): Boolean {
        return try {
            val resolver = context.contentResolver
            val current = Settings.Global.getInt(resolver, ADB_WIFI_KEY, 0)
            Settings.Global.putInt(resolver, ADB_WIFI_KEY, current)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getAuthorizedDevices(context: Context): List<AdbDevice> {
        return getDevices(context).filter { it.authorized }
    }

    fun getDevices(context: Context): List<AdbDevice> {
        val result = runAdbCommand(context, listOf("devices"))
        if (result.exitCode != 0) {
            throw IllegalStateException(result.bestMessage("adb devices failed"))
        }

        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.contains('\t') }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2) {
                    null
                } else {
                    AdbDevice(parts[0], parts[1] == "device")
                }
            }
            .toList()
    }

    fun pair(context: Context, port: Int, code: String): AdbCommandResult {
        return runAdbCommand(context, listOf("pair", "127.0.0.1:$port", code))
    }

    fun connect(context: Context, port: Int): AdbCommandResult {
        return runAdbCommand(context, listOf("connect", "127.0.0.1:$port"))
    }

    fun detectDebugPort(context: Context): Int {
        val process = ProcessBuilder(adbFinderPath(context)).start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            return 0
        }

        return process.inputStream.bufferedReader()
            .readLines()
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.toIntOrNull()
            ?: 0
    }

    fun grantSelfPermissions(context: Context, deviceName: String?) {
        if (deviceName == null) {
            return
        }

        val packageName = context.packageName
        runShellCommand(
            context = context,
            deviceName = deviceName,
            command = listOf(
                "sh",
                "-c",
                "(pm grant $packageName android.permission.WRITE_SECURE_SETTINGS; " +
                    "pm grant $packageName android.permission.READ_LOGS) > /dev/null 2>&1 < /dev/null &"
            )
        )
    }

    private fun runShellCommand(
        context: Context,
        deviceName: String?,
        command: List<String>
    ): AdbCommandResult {
        val args = mutableListOf<String>()
        if (!deviceName.isNullOrBlank()) {
            args.add("-s")
            args.add(deviceName)
        }
        args.add("shell")
        args.addAll(command)
        return runAdbCommand(context, args)
    }

    private fun runAdbCommand(context: Context, args: List<String>): AdbCommandResult {
        ensureServer(context)
        val command = mutableListOf(adbPath(context), "-P", ADB_PORT.toString())
        command.addAll(args)
        val process = ProcessBuilder(command).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return AdbCommandResult(exitCode, stdout, stderr)
    }

    private fun adbPath(context: Context): String {
        return File(context.applicationInfo.nativeLibraryDir, "libadb.so").absolutePath
    }

    private fun adbFinderPath(context: Context): String {
        return File(context.applicationInfo.nativeLibraryDir, "libAdbFinder.so").absolutePath
    }
}
