package com.example.devtoolsopener

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val MAX_LINES = 250
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val entries = ArrayDeque<String>()

    @Synchronized
    fun append(message: String) {
        val line = "${formatter.format(Date())}  $message"
        entries.addLast(line)
        while (entries.size > MAX_LINES) {
            entries.removeFirst()
        }
    }

    @Synchronized
    fun text(): String {
        return entries.joinToString(separator = "\n")
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
