package org.sm0ke.mbftools

import android.os.Build

internal fun Process.isRunningCompat(): Boolean {
    return try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }
}

internal fun Process.waitForCompat(timeoutMs: Long): Boolean {
    if (!isRunningCompat()) return true

    val timeoutNanos = timeoutMs.coerceAtLeast(0L) * 1_000_000L
    val deadline = System.nanoTime() + timeoutNanos
    while (isRunningCompat()) {
        val remainingNanos = deadline - System.nanoTime()
        if (remainingNanos <= 0L) return false
        Thread.sleep((remainingNanos / 1_000_000L).coerceIn(1L, 50L))
    }
    return true
}

internal fun Process.terminateCompat(gracePeriodMs: Long = 500L) {
    destroy()
    if (waitForCompat(gracePeriodMs)) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        destroyForcibly()
        waitForCompat(gracePeriodMs)
    }
}
