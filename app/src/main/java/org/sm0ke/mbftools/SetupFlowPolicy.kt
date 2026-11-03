package org.sm0ke.mbftools

/** Shared decisions used whenever polling or navigation recomputes setup controls. */
internal object SetupFlowPolicy {
    fun actionEnabled(busy: Boolean, available: Boolean = true): Boolean = !busy && available

    fun skipGuide(setupComplete: Boolean, explicitRevisit: Boolean): Boolean =
        setupComplete && !explicitRevisit

    fun autoLaunchAllowed(explicitRevisit: Boolean, busy: Boolean, alreadyLaunched: Boolean): Boolean =
        !explicitRevisit && !busy && !alreadyLaunched

    fun replacePort(current: String, detected: Int, force: Boolean): Boolean =
        detected in 1..65535 && (force || current.trim().toIntOrNull() == null)
}
