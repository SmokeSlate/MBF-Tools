package org.sm0ke.mbftools

import android.content.pm.ApplicationInfo
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val entries = ArrayDeque<String>()

    @Volatile private var logFile: File? = null
    @Volatile private var loadedFromDisk = false
    @Volatile private var verboseLogging = false

    fun init(context: Context) {
        if (logFile != null && loadedFromDisk) {
            return
        }
        synchronized(this) {
            if (logFile == null) {
                logFile = File(context.filesDir, "mbf-tools.log")
            }
            verboseLogging =
                    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (!loadedFromDisk) {
                val persisted =
                        runCatching { logFile?.takeIf { it.exists() }?.readLines() ?: emptyList() }
                                .getOrDefault(emptyList())
                entries.clear()
                persisted.takeLast(maxLines()).forEach { entries.addLast(it) }
                loadedFromDisk = true
            }
        }
    }

    @Synchronized
    fun append(message: String, tag: String = "App", level: String = "INFO") {
        val line = "${formatter.format(Date())}  [$level/$tag] $message"
        entries.addLast(line)
        while (entries.size > maxLines()) {
            entries.removeFirst()
        }
        persist()
    }

    fun info(tag: String, message: String) = append(message = message, tag = tag, level = "INFO")

    fun warn(tag: String, message: String) = append(message = message, tag = tag, level = "WARN")

    fun error(tag: String, message: String) =
            append(message = message, tag = tag, level = "ERROR")

    @Synchronized
    fun text(): String {
        return entries.joinToString(separator = "\n")
    }

    @Synchronized
    fun lines(): List<String> {
        return entries.toList()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        persist()
    }

    @Synchronized
    private fun persist() {
        runCatching {
                    val target = logFile ?: return
                    target.parentFile?.mkdirs()
                    target.writeText(text())
                }
                .getOrElse {
                    // Keep logging in-memory even if disk persistence fails.
                }
    }

    fun isVerboseLoggingEnabled(): Boolean = verboseLogging

    private fun maxLines(): Int {
        return if (verboseLogging) 3000 else 500
    }
}
