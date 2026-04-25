package org.sm0ke.mbftools

import org.junit.Assert.assertEquals
import org.junit.Test

class AdbCommandResultTest {
    @Test
    fun bestMessage_prefersTrimmedStdout() {
        val result = AdbCommandResult(exitCode = 0, stdout = "  connected  ", stderr = "error")

        assertEquals("connected", result.bestMessage("fallback"))
    }

    @Test
    fun bestMessage_fallsBackToTrimmedStderrWhenStdoutBlank() {
        val result = AdbCommandResult(exitCode = 1, stdout = "   ", stderr = "  failed  ")

        assertEquals("failed", result.bestMessage("fallback"))
    }

    @Test
    fun bestMessage_returnsFallbackWhenBothStreamsBlank() {
        val result = AdbCommandResult(exitCode = 1, stdout = " ", stderr = " ")

        assertEquals("fallback", result.bestMessage("fallback"))
    }
}
