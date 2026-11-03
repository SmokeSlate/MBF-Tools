package org.sm0ke.mbftools

internal data class DiagnosticLogFile(val path: String, val modifiedSeconds: Long)

internal object DiagnosticLogSelection {
    fun parseStatOutput(output: String): List<DiagnosticLogFile> =
            output.lineSequence().mapNotNull { line ->
                val separator = line.indexOf(' ')
                if (separator <= 0) return@mapNotNull null
                val modifiedSeconds = line.substring(0, separator).toLongOrNull()
                        ?: return@mapNotNull null
                val path = line.substring(separator + 1).trimEnd('\r')
                if (!path.startsWith('/') || !path.endsWith(".log", ignoreCase = true)) {
                    return@mapNotNull null
                }
                DiagnosticLogFile(path, modifiedSeconds)
            }.toList()

    fun selectNewest(files: List<DiagnosticLogFile>, limit: Int): List<String> {
        // Sort globally before deduplication/capping; logs and logs2 can interleave.
        // Return chronological order so the collector's takeLast line cap keeps
        // the newest sessions (and the most recent interesting lines).
        return files.sortedWith(compareByDescending<DiagnosticLogFile> { it.modifiedSeconds }
                        .thenBy { it.path })
                .distinctBy { canonicalStoragePath(it.path) }
                .take(limit)
                .asReversed()
                .map { it.path }
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun canonicalStoragePath(path: String): String =
            if (path.startsWith("/sdcard/")) {
                "/storage/emulated/0/" + path.removePrefix("/sdcard/")
            } else {
                path
            }
}
