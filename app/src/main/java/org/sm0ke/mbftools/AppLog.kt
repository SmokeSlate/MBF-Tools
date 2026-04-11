package org.sm0ke.mbftools

import android.content.pm.ApplicationInfo
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object AppLog {
    private const val PERSIST_DELAY_MS = 250L

    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val entries = ArrayDeque<String>()
    private val persistenceExecutor =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "mbf-app-log-writer").apply { isDaemon = true }
            }

    @Volatile private var logFile: File? = null
    @Volatile private var loadedFromDisk = false
    @Volatile private var verboseLogging = false
    @Volatile private var persistScheduled = false
    @Volatile private var persistDirty = false

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
        requestPersistLocked()
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
        requestPersistLocked()
    }

    private fun requestPersistLocked() {
        persistDirty = true
        if (persistScheduled) {
            return
        }
        persistScheduled = true
        persistenceExecutor.schedule({ flushPersisted() }, PERSIST_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun flushPersisted() {
        while (true) {
            val target: File
            val snapshot: String
            synchronized(this) {
                if (!persistDirty) {
                    persistScheduled = false
                    return
                }
                persistDirty = false
                target = logFile ?: run {
                    persistScheduled = false
                    return
                }
                snapshot = entries.joinToString(separator = "\n")
            }

            runCatching {
                        target.parentFile?.mkdirs()
                        target.writeText(snapshot)
                    }
                    .getOrElse {
                        // Keep logging in-memory even if disk persistence fails.
                    }
        }
    }

    fun isVerboseLoggingEnabled(): Boolean = verboseLogging

    private fun maxLines(): Int {
        return if (verboseLogging) 3000 else 500
    }
}
