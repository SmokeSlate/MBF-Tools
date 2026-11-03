package org.sm0ke.mbftools

/** Retry a stale/failed endpoint once using fresh discovery; never loop on saved ports. */
internal object ConnectionRecovery {
    fun <T> connect(initialPort: Int, detect: () -> Int, connect: (Int) -> T,
                    succeeded: (T) -> Boolean): Result<T> {
        val firstPort = if (initialPort in 1..65535) initialPort else runCatching(detect).getOrDefault(0)
        val first = runCatching {
            require(firstPort in 1..65535) { "No wireless debug port found." }
            connect(firstPort)
        }
        if (first.isSuccess && succeeded(first.getOrThrow())) return first
        val fresh = runCatching(detect).getOrDefault(0)
        if (fresh !in 1..65535) return first
        return runCatching { connect(fresh) }
    }
}
