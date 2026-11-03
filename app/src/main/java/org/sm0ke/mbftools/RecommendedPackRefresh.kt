package org.sm0ke.mbftools

/** Serializes async lookups and rejects results from a page that has been left. */
internal class RecommendedPackRefresh {
    private var generation = 0
    private var inFlight: Int? = null
    private var lastRefresh = -5_000L
    var toolbarRequested = false
        private set

    fun pageChanged() {
        generation += 1
        toolbarRequested = false
    }

    fun begin(fromToolbar: Boolean, now: Long): Int? {
        toolbarRequested = toolbarRequested || fromToolbar
        if (inFlight != null || (!fromToolbar && now - lastRefresh < 5_000L)) return null
        lastRefresh = now
        inFlight = generation
        return generation
    }

    fun isCurrent(request: Int): Boolean = request == generation

    fun finish(request: Int) {
        if (inFlight != request) return
        inFlight = null
        // Keep a newer page's toolbar request queued for a fresh lookup.
        if (isCurrent(request)) toolbarRequested = false
    }
}
