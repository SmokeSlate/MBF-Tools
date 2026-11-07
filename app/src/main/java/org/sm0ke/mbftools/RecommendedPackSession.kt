package org.sm0ke.mbftools

/** Keeps recommendations tied to the installed version, not the launch intent. */
internal class RecommendedPackSession {
    var versionTag: String? = null
        private set
    var pack: RecommendedModPack? = null
        private set
    var handled = false
    var shouldAutoPrompt = false

    fun refresh(currentVersion: String?, alreadyPrompted: (String) -> Boolean) {
        val currentPack = RecommendedModPacks.forVersion(currentVersion)
        if (currentPack?.fingerprint != pack?.fingerprint) handled = false
        versionTag = currentVersion
        pack = currentPack
        shouldAutoPrompt = currentPack != null && !handled && !alreadyPrompted(currentPack.fingerprint)
    }
}
