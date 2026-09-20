package org.sm0ke.mbftools

import org.junit.Assert.assertEquals
import org.junit.Test

class MbfConfigTest {

    @Test
    fun appUrl_usesBridgeAwareDeployment() {
        assertEquals("https://github.sm0ke.org/MBF-Tools/mbf/", MbfConfig.APP_URL)
    }
}
