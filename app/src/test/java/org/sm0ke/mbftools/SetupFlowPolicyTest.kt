package org.sm0ke.mbftools

import org.junit.Assert.*
import org.junit.Test

class SetupFlowPolicyTest {
    @Test fun queuedConnectedPollCannotUnlockBusyAction() {
        var busy = true
        // Both an already-queued poll and a later poll must preserve the operation lock.
        repeat(2) {
            assertFalse(SetupFlowPolicy.actionEnabled(busy, available = true))
            assertFalse(SetupFlowPolicy.actionEnabled(busy))
        }
        busy = false
        assertTrue(SetupFlowPolicy.actionEnabled(busy, available = true))
        assertFalse(SetupFlowPolicy.actionEnabled(busy, available = false))
    }

    @Test fun nextAndBackStayDisabledUntilWorkFinishesAndStepAllowsThem() {
        for (available in listOf(false, true)) {
            assertFalse(SetupFlowPolicy.actionEnabled(busy = true, available = available))
            assertEquals(available, SetupFlowPolicy.actionEnabled(busy = false, available = available))
        }
    }

    @Test fun completedSetupOnlySkipsAutomaticGuideRouting() {
        assertTrue(SetupFlowPolicy.skipGuide(setupComplete = true, explicitRevisit = false))
        assertFalse(SetupFlowPolicy.skipGuide(setupComplete = true, explicitRevisit = true))
        assertFalse(SetupFlowPolicy.skipGuide(setupComplete = false, explicitRevisit = false))
        assertFalse(SetupFlowPolicy.skipGuide(setupComplete = false, explicitRevisit = true))
    }

    @Test fun revisitAndBusyPollingCannotAutoLaunch() {
        for (busy in listOf(false, true)) {
            assertFalse(SetupFlowPolicy.autoLaunchAllowed(true, busy, false))
        }
        assertFalse(SetupFlowPolicy.autoLaunchAllowed(false, true, false))
        assertFalse(SetupFlowPolicy.autoLaunchAllowed(false, false, true))
        assertTrue(SetupFlowPolicy.autoLaunchAllowed(false, false, false))
    }

    @Test fun explicitDetectionReplacesSavedPortsButBackgroundAutofillDoesNot() {
        assertTrue(SetupFlowPolicy.replacePort(" 12345 ", 54321, force = true))
        assertFalse(SetupFlowPolicy.replacePort(" 12345 ", 54321, force = false))
        assertTrue(SetupFlowPolicy.replacePort("", 54321, force = false))
        assertTrue(SetupFlowPolicy.replacePort("invalid", 54321, force = false))
        assertFalse(SetupFlowPolicy.replacePort("12345", 0, force = true))
        assertFalse(SetupFlowPolicy.replacePort("12345", 65536, force = true))
    }
}
