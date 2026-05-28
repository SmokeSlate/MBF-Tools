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
}
