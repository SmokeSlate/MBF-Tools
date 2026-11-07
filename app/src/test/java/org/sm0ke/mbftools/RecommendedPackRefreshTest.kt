package org.sm0ke.mbftools

import org.junit.Assert.*
import org.junit.Test

class RecommendedPackRefreshTest {
    @Test fun concurrentReadyAndToolbarRequestsShareOneLookup() {
        val refresh = RecommendedPackRefresh()
        val request = refresh.begin(false, 0)!!
        assertNull(refresh.begin(false, 1))
        assertNull(refresh.begin(true, 2))
        assertTrue(refresh.toolbarRequested)
        assertTrue(refresh.isCurrent(request))
        refresh.finish(request)
        assertFalse(refresh.toolbarRequested)
        assertNull(refresh.begin(false, 3))
        assertNotNull(refresh.begin(true, 3)) // Explicit requests bypass observer throttle.
    }

    @Test fun navigationDiscardsOldCompletionAndPreservesNewToolbarRequest() {
        val refresh = RecommendedPackRefresh()
        val old = refresh.begin(false, 0)!!
        refresh.pageChanged()
        assertFalse(refresh.isCurrent(old))
        assertNull(refresh.begin(true, 1))
        refresh.finish(old)
        assertTrue(refresh.toolbarRequested)
        val current = refresh.begin(true, 2)!!
        assertTrue(refresh.isCurrent(current))
        refresh.finish(current)
        assertFalse(refresh.toolbarRequested)
    }

    @Test fun completionAfterFailureAllowsRetryAndThrottleExpires() {
        val refresh = RecommendedPackRefresh()
        val request = refresh.begin(false, 0)!!
        refresh.finish(request) // Same cleanup on success, failure, or cancellation.
        assertNull(refresh.begin(false, 4_999))
        assertNotNull(refresh.begin(false, 5_000))
    }

    @Test fun leavingPageClearsItsPendingToolbarIntent() {
        val refresh = RecommendedPackRefresh()
        val request = refresh.begin(true, 0)!!
        refresh.pageChanged()
        refresh.finish(request)
        assertFalse(refresh.toolbarRequested)
        assertFalse(refresh.isCurrent(request))
    }
}
