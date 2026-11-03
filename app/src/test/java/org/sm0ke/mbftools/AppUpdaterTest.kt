package org.sm0ke.mbftools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun compareVersionLabels_detectsNewerReleaseTag() {
        assertEquals(1, AppUpdater.compareVersionLabels("v3.4", "3.3"))
    }

    @Test
    fun compareVersionLabels_treatsMissingPatchAsEqual() {
        assertEquals(0, AppUpdater.compareVersionLabels("v3.3.0", "3.3"))
    }

    @Test
    fun compareVersionLabels_detectsReleaseAssetVersion() {
        assertEquals(1, AppUpdater.compareVersionLabels("MBF-Tools-and-Setup-v3.4-release.apk", "3.3"))
    }

    @Test
    fun compareVersionLabels_returnsNullForUnparseableLabels() {
        assertNull(AppUpdater.compareVersionLabels("latest", "3.3"))
    }
    @Test
    fun betaVersions_useNumericCountersAndReleasePrecedence() {
        assertEquals(1, AppUpdater.compareVersionLabels("v3.6.4-beta.10", "3.6.4-beta.9"))
        assertEquals(-1, AppUpdater.compareVersionLabels("3.6.4-beta.1", "3.6.4"))
        assertEquals(1, AppUpdater.compareVersionLabels("3.6.4", "3.6.4-beta.10"))
        assertEquals(0, AppUpdater.compareVersionLabels("v3.6.4-beta.1", "3.6.4-beta.1"))
        assertEquals(-1, AppUpdater.compareVersionLabels("3.6.3", "3.6.4-beta.1"))
        assertEquals(1, AppUpdater.compareVersionLabels("3.6.5-beta.1", "3.6.4-beta.99"))
    }

    @Test
    fun betaChannel_isOnlyEnabledForBetaVersionNames() {
        assertEquals(true, AppUpdater.isBetaVersion("3.6.4-beta.1"))
        assertEquals(false, AppUpdater.isBetaVersion("3.6.4"))
        assertEquals(false, AppUpdater.isBetaVersion("3.6.4-rc.1"))
    }

    @Test
    fun newestRelease_usesVersionOrderRatherThanPublicationOrder() {
        fun release(version: String) = GitHubRelease(version, version, "", "", "",
                GitHubReleaseAsset("release.apk", "https://example.invalid/release.apk", 100))
        val releases = listOf(release("3.6.4-beta.10"), release("3.6.4-beta.2"), release("3.6.4-beta.9"))
        assertEquals("3.6.4-beta.10", AppUpdater.newestRelease(releases)?.tagName)
        assertNull(AppUpdater.newestRelease(emptyList()))
    }
}
