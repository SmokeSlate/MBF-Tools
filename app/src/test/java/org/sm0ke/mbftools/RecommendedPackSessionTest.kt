package org.sm0ke.mbftools

import org.junit.Assert.*
import org.junit.Test

class RecommendedPackSessionTest {
    @Test fun unmappedLaunchThenDowngradeOffersCurrentPack() {
        val session = RecommendedPackSession()
        session.refresh("99.0.0_1") { false }
        assertNull(session.pack)
        assertFalse(session.shouldAutoPrompt)
        session.refresh("1.40.8_7379") { false }
        assertEquals("1.40.8_7379", session.pack?.versionTag)
        assertTrue(session.shouldAutoPrompt)
    }

    @Test fun repeatedReadyCallbacksDoNotRepeatHandledPrompt() {
        val session = RecommendedPackSession()
        session.refresh("1.40.8_7379") { false }
        session.handled = true
        session.refresh("1.40.8_7379") { false }
        assertFalse(session.shouldAutoPrompt)
        assertTrue(session.handled)
    }

    @Test fun changedVersionResetsHandlingButHonorsSavedPreference() {
        val session = RecommendedPackSession()
        session.refresh("1.40.7_7060") { false }
        session.handled = true
        session.refresh("1.40.8_7379") { true }
        assertFalse(session.handled)
        assertFalse(session.shouldAutoPrompt)
        // The toolbar still has the current pack even when auto-prompt is disabled.
        assertEquals("1.40.8_7379", session.pack?.versionTag)
        session.refresh("1.40.6_6407") { false }
        assertTrue(session.shouldAutoPrompt)
    }

    @Test fun missingOrUnsupportedCurrentVersionDoesNotRetainStalePack() {
        for (version in listOf(null, "99.0.0_1")) {
            val session = RecommendedPackSession()
            session.refresh("1.40.8_7379") { false }
            session.refresh(version) { false }
            assertNull(session.pack)
            assertFalse(session.shouldAutoPrompt)
        }
    }
}
