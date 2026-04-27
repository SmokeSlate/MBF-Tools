package org.sm0ke.mbftools

import android.content.Context
import android.content.Context.CONNECTIVITY_SERVICE
import android.content.Context.NSD_SERVICE
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class AdbCommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
    fun bestMessage(fallback: String): String {
        val preferred = stdout.trim().ifEmpty { stderr.trim() }
        return preferred.ifEmpty { fallback }
    }
}

data class AdbDevice(val name: String, val authorized: Boolean)

object AdbManager {
    private const val ADB_PORT = 5037
    private const val ADB_WIFI_KEY = "adb_wifi_enabled"
    private const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp."
    private const val PAIRING_DISCOVERY_TIMEOUT_MS = 3_500L
    private const val DEBUG_PORT_ATTEMPTS = 8
    private const val DEBUG_PORT_DELAY_MS = 500L
    private const val ADB_COMMAND_TIMEOUT_MS = 15_000L
    private const val STREAM_READER_THREADS = 4

    @Volatile private var serverProcess: Process? = null
    private val streamReaderPool =
            Executors.newFixedThreadPool(STREAM_READER_THREADS) { runnable ->
                Thread(runnable, "adb-stream-reader").apply { isDaemon = true }
            }

    @Synchronized
    private fun ensureServer(context: Context) {
        if (isProcessAlive(serverProcess)) {
            return
        }

        val builder =
                ProcessBuilder(adbPath(context), "-P", ADB_PORT.toString(), "server", "nodaemon")
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

    fun shell(
            context: Context,
            deviceName: String?,
            command: String,
            timeoutMs: Long = ADB_COMMAND_TIMEOUT_MS
    ): AdbCommandResult {
        return runShellCommand(
                context = context,
                deviceName = deviceName,
                command = listOf("sh", "-c", command),
                timeoutMs = timeoutMs
        )
    }

    fun shellArgs(
            context: Context,
            deviceName: String?,
            command: List<String>,
            timeoutMs: Long = ADB_COMMAND_TIMEOUT_MS
    ): AdbCommandResult {
        return runShellCommand(
                context = context,
                deviceName = deviceName,
                command = command,
                timeoutMs = timeoutMs
        )
    }

    fun logcat(
            context: Context,
            deviceName: String?,
            lines: Int = 500,
            timeoutMs: Long = ADB_COMMAND_TIMEOUT_MS
    ): AdbCommandResult {
        val args = mutableListOf<String>()
        if (!deviceName.isNullOrBlank()) {
            args.add("-s")
            args.add(deviceName)
        }
        args.addAll(listOf("logcat", "-d", "-t", lines.toString()))
        return runAdbCommand(context, args, timeoutMs)
    }

    fun detectPairingPort(context: Context): Int {
        val localAddresses = getLocalIpAddresses(context)
        if (localAddresses.isEmpty()) {
            return 0
        }

        val nsdManager = context.getSystemService(NSD_SERVICE) as? NsdManager ?: return 0
        val detectedPort = AtomicInteger(0)
        val discoveryDone = CountDownLatch(1)

        val discoveryListener =
                object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        discoveryDone.countDown()
                    }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (!serviceInfo.serviceType.startsWith(
                                        PAIRING_SERVICE_TYPE.removeSuffix(".")
                                )
                        ) {
                            return
                        }

                        @Suppress("DEPRECATION")
                        nsdManager.resolveService(
                                serviceInfo,
                                object : NsdManager.ResolveListener {
                                    override fun onResolveFailed(
                                            serviceInfo: NsdServiceInfo,
                                            errorCode: Int
                                    ) = Unit

                                    override fun onServiceResolved(
                                            resolvedServiceInfo: NsdServiceInfo
                                    ) {
                                        val hostAddress =
                                                resolvedServiceInfo
                                                        .host
                                                        ?.hostAddress
                                                        ?.substringBefore('%')
                                                        ?.trim()
                                                        ?: return

                                        if (localAddresses.contains(hostAddress)) {
                                            detectedPort.compareAndSet(0, resolvedServiceInfo.port)
                                            discoveryDone.countDown()
                                        }
                                    }
                                }
                        )
                    }
                }

        return try {
            @Suppress("DEPRECATION")
            nsdManager.discoverServices(
                    PAIRING_SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
            )
            discoveryDone.await(PAIRING_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            detectedPort.get()
        } catch (_: Exception) {
            0
        } finally {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
    }

    fun detectDebugPort(context: Context): Int {
        repeat(DEBUG_PORT_ATTEMPTS) { attempt ->
            val port = detectDebugPortOnce(context)
            if (port > 0) {
                return port
            }
            if (attempt < DEBUG_PORT_ATTEMPTS - 1) {
                Thread.sleep(DEBUG_PORT_DELAY_MS)
            }
        }
        return 0
    }

    private fun detectDebugPortOnce(context: Context): Int {
        val process = ProcessBuilder(adbFinderPath(context)).start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            return 0
        }

        return process.inputStream
                .bufferedReader()
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
                command =
                        listOf(
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
            command: List<String>,
            timeoutMs: Long = ADB_COMMAND_TIMEOUT_MS
    ): AdbCommandResult {
        val args = mutableListOf<String>()
        if (!deviceName.isNullOrBlank()) {
            args.add("-s")
            args.add(deviceName)
        }
        args.add("shell")
        args.addAll(command)
        return runAdbCommand(context, args, timeoutMs)
    }

    private fun runAdbCommand(
            context: Context,
            args: List<String>,
            timeoutMs: Long = ADB_COMMAND_TIMEOUT_MS
    ): AdbCommandResult {
        ensureServer(context)
        val command = mutableListOf(adbPath(context), "-P", ADB_PORT.toString())
        command.addAll(args)
        val process = ProcessBuilder(command).start()
        val stdoutFuture = streamReaderPool.submit<String> {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderrFuture = streamReaderPool.submit<String> {
            process.errorStream.bufferedReader().use { it.readText() }
        }
        val finished = waitForProcess(process, timeoutMs)
        if (!finished) {
            process.destroy()
            waitForProcess(process, 500)
            val stdout = runCatching { stdoutFuture.get(1, TimeUnit.SECONDS) }.getOrDefault("")
            val stderr =
                    runCatching { stderrFuture.get(1, TimeUnit.SECONDS) }
                            .getOrDefault("")
                            .ifBlank { "adb command timed out after ${timeoutMs}ms." }
            return AdbCommandResult(124, stdout, stderr)
        }
        val stdout = runCatching { stdoutFuture.get(1, TimeUnit.SECONDS) }.getOrDefault("")
        val stderr = runCatching { stderrFuture.get(1, TimeUnit.SECONDS) }.getOrDefault("")
        val exitCode = process.exitValue()
        return AdbCommandResult(exitCode, stdout, stderr)
    }

    private fun adbPath(context: Context): String {
        return File(context.applicationInfo.nativeLibraryDir, "libadb.so").absolutePath
    }

    private fun adbFinderPath(context: Context): String {
        return File(context.applicationInfo.nativeLibraryDir, "libAdbFinder.so").absolutePath
    }

    private fun getLocalIpAddresses(context: Context): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return emptySet()
        }
        val connectivityManager =
                context.getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
                        ?: return emptySet()
        val activeNetwork = connectivityManager.activeNetwork ?: return emptySet()
        val linkProperties =
                connectivityManager.getLinkProperties(activeNetwork) ?: return emptySet()

        return linkProperties
                .linkAddresses
                .mapNotNull { it.address?.hostAddress?.substringBefore('%')?.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
    }

    private fun isProcessAlive(process: Process?): Boolean {
        if (process == null) {
            return false
        }
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun waitForProcess(process: Process, timeoutMs: Long): Boolean {
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val deadline = System.nanoTime() + timeoutNanos
        while (isProcessAlive(process)) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) {
                return false
            }
            val sleepMs =
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                            .coerceIn(1L, 50L)
            Thread.sleep(sleepMs)
        }
        return true
    }
}
