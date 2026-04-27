package org.sm0ke.mbftools

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONObject

object BridgeManager {
    @Volatile private var bridgeProcess: Process? = null

    @Volatile private var browserUrl: String? = null

    @Synchronized
    fun startOrGetBrowserUrl(context: Context, appUrl: String): String {
        if (isProcessAlive(bridgeProcess) && !browserUrl.isNullOrBlank()) {
            return browserUrl!!
        }

        bridgeProcess?.destroy()
        bridgeProcess = null
        browserUrl = null

        val startupUrl = AtomicReference<String>()
        val latch = CountDownLatch(1)
        val bridgeBinary =
                File(context.applicationInfo.nativeLibraryDir, "libMbfBridge.so").absolutePath

        val process =
                ProcessBuilder(
                                bridgeBinary,
                                "--url",
                                appUrl,
                                "--proxy",
                                "--port",
                                "0",
                                "--adb-port",
                                "5037",
                                "--output-json"
                        )
                        .start()

        bridgeProcess = process

        thread(name = "mbf-bridge-reader", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    try {
                        val json = JSONObject(line)
                        if (json.optString("message_type") == "StartupInfo") {
                            val payload = json.getJSONObject("payload")
                            val url = payload.optString("browser_url")
                            if (url.isNotBlank()) {
                                browserUrl = url
                                startupUrl.set(url)
                                latch.countDown()
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        if (!latch.await(20, TimeUnit.SECONDS)) {
            process.destroy()
            bridgeProcess = null
            throw IllegalStateException("Timed out waiting for MBF bridge startup.")
        }

        return startupUrl.get()
                ?: throw IllegalStateException("MBF bridge did not return a browser URL.")
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
}
