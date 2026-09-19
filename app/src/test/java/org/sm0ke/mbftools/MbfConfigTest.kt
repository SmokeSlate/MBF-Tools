package org.sm0ke.mbftools

import org.junit.Assert.assertEquals
import org.junit.Test

class MbfConfigTest {

    @Test
    fun appUrl_usesCanonicalMbfDeployment() {
        assertEquals("https://mbf.bsquest.xyz/", MbfConfig.APP_URL)
    }
}
