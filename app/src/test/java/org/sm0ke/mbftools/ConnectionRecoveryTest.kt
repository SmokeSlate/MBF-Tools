package org.sm0ke.mbftools

import org.junit.Assert.*
import org.junit.Test

class ConnectionRecoveryTest {
    @Test fun stalePortIsReplacedOnce() {
        val attempts = mutableListOf<Int>()
        val result = ConnectionRecovery.connect(1000, { 2000 },
            { port -> attempts.add(port); port == 2000 }, { it })
        assertTrue(result.getOrThrow())
        assertEquals(listOf(1000, 2000), attempts)
    }
    @Test fun thrownConnectCanRecover() {
        val result = ConnectionRecovery.connect(1000, { 2000 },
            { port -> if (port == 1000) error("expired") else "connected" }, { it == "connected" })
        assertEquals("connected", result.getOrThrow())
    }
    @Test fun successfulPortDoesNotRediscover() {
        val result = ConnectionRecovery.connect(1000, { error("unexpected discovery") }, { true }, { it })
        assertTrue(result.getOrThrow())
    }
    @Test fun failedDiscoveryDoesNotLoop() {
        var attempts = 0
        val result = ConnectionRecovery.connect(1000, { 0 }, { attempts++; false }, { it })
        assertFalse(result.getOrThrow())
        assertEquals(1, attempts)
    }
    @Test fun absentPortUsesDiscoveryAndDoesNotConnectToZero() {
        val attempts = mutableListOf<Int>()
        val result = ConnectionRecovery.connect(0, { 2000 },
            { port -> attempts.add(port); true }, { it })
        assertTrue(result.getOrThrow())
        assertEquals(listOf(2000), attempts)
    }
    @Test fun invalidDiscoveryNeverConnects() {
        var attempts = 0
        val result = ConnectionRecovery.connect(-1, { 70000 }, { attempts++; true }, { it })
        assertTrue(result.isFailure)
        assertEquals(0, attempts)
    }
    @Test fun thrownDiscoveryPreservesConnectionFailure() {
        val result = ConnectionRecovery.connect(1000, { error("discovery failed") },
            { error("connection failed") }, { _: Boolean -> false })
        assertEquals("connection failed", result.exceptionOrNull()?.message)
    }
    @Test fun repeatedFailedPortStopsAfterOneRetry() {
        var attempts = 0
        val result = ConnectionRecovery.connect(1000, { 1000 }, { attempts++; false }, { it })
        assertFalse(result.getOrThrow())
        assertEquals(2, attempts)
    }
}
