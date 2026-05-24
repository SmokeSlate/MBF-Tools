package org.sm0ke.mbftools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSettingsPresetsTest {

    @Test
    fun refreshRate_producesCorrectSetpropCommand() {
        val cmds = DeviceSettingsPresets.refreshRate(90)
        assertEquals(1, cmds.size)
        assertEquals(listOf("setprop", "debug.oculus.refreshRate", "90"), cmds[0].args)
    }

    @Test
    fun refreshRate30_isSupported() {
        val cmds = DeviceSettingsPresets.refreshRate(30)
        assertEquals("30", cmds[0].args.last())
    }

    @Test
    fun refreshRate60_isSupported() {
        val cmds = DeviceSettingsPresets.refreshRate(60)
        assertEquals("60", cmds[0].args.last())
    }

    @Test
    fun cpuLevel_rangeIsValid() {
        for (level in 1..4) {
            val cmds = DeviceSettingsPresets.cpuLevel(level)
            assertEquals(1, cmds.size)
            assertEquals(level.toString(), cmds[0].args.last())
        }
    }

    @Test
    fun gpuLevel_rangeIsValid() {
        for (level in 1..4) {
            val cmds = DeviceSettingsPresets.gpuLevel(level)
            assertEquals(1, cmds.size)
            assertEquals(level.toString(), cmds[0].args.last())
        }
    }

    @Test
    fun foveation_allLevels() {
        for (level in 0..3) {
            val cmds = DeviceSettingsPresets.foveation(level)
            assertEquals(1, cmds.size)
            assertEquals("debug.oculus.foveation.level", cmds[0].args[1])
            assertEquals(level.toString(), cmds[0].args.last())
        }
    }

    @Test
    fun textureSize_setsBothDimensions() {
        val cmds = DeviceSettingsPresets.textureSize(2048)
        assertEquals(2, cmds.size)
        assertTrue(cmds.any { it.args.contains("debug.oculus.textureWidth") })
        assertTrue(cmds.any { it.args.contains("debug.oculus.textureHeight") })
        assertTrue(cmds.all { it.args.last() == "2048" })
    }

    @Test
    fun guardianOff_setsCorrectProp() {
        val cmds = DeviceSettingsPresets.guardianOff()
        assertEquals(1, cmds.size)
        assertEquals("debug.oculus.enableGuardian", cmds[0].args[1])
        assertEquals("0", cmds[0].args.last())
    }

    @Test
    fun guardianOn_setsCorrectProp() {
        val cmds = DeviceSettingsPresets.guardianOn()
        assertEquals("1", cmds[0].args.last())
    }

    @Test
    fun aswOff_setsCorrectProp() {
        val cmds = DeviceSettingsPresets.aswOff()
        assertEquals("debug.oculus.forceASW", cmds[0].args[1])
        assertEquals("0", cmds[0].args.last())
    }

    @Test
    fun aswOn_setsCorrectProp() {
        val cmds = DeviceSettingsPresets.aswOn()
        assertEquals("1", cmds[0].args.last())
    }

    @Test
    fun batterySaver_includesRefreshRateCpuGpuFoveation() {
        val cmds = DeviceSettingsPresets.batterySaver()
        assertTrue(cmds.any { it.args.contains("debug.oculus.refreshRate") && it.args.last() == "72" })
        assertTrue(cmds.any { it.args.contains("debug.oculus.cpuLevel") && it.args.last() == "2" })
        assertTrue(cmds.any { it.args.contains("debug.oculus.gpuLevel") && it.args.last() == "2" })
        assertTrue(cmds.any { it.args.contains("debug.oculus.foveation.level") && it.args.last() == "3" })
    }

    @Test
    fun balanced_usesModerateSettings() {
        val cmds = DeviceSettingsPresets.balanced()
        assertTrue(cmds.any { it.args.contains("debug.oculus.refreshRate") && it.args.last() == "90" })
        assertTrue(cmds.any { it.args.contains("debug.oculus.cpuLevel") && it.args.last() == "3" })
        assertTrue(cmds.any { it.args.contains("debug.oculus.gpuLevel") && it.args.last() == "3" })
    }

    @Test
    fun maxPower_usesMaxSettings() {
        val cmds = DeviceSettingsPresets.maxPower()
        assertTrue(cmds.any { it.args.contains("debug.oculus.refreshRate") && it.args.last() == "120" })
        assertTrue(cmds.any { it.args.contains("debug.oculus.cpuLevel") && it.args.last() == "4" })
        assertTrue(cmds.any { it.args.contains("debug.oculus.gpuLevel") && it.args.last() == "4" })
        assertTrue(cmds.any { it.args.contains("debug.oculus.foveation.level") && it.args.last() == "0" })
    }

    @Test
    fun lowPower_includes60HzAndAsw() {
        val cmds = DeviceSettingsPresets.lowPower()
        assertTrue(cmds.any { it.args.contains("debug.oculus.refreshRate") && it.args.last() == "60" })
        assertTrue(cmds.any { it.args.contains("debug.oculus.forceASW") && it.args.last() == "1" })
    }

    @Test
    fun resetOverrides_resetsAllKnownProps() {
        val cmds = DeviceSettingsPresets.resetOverrides()
        val props = cmds.map { it.args[1] }.toSet()
        assertTrue(props.contains("debug.oculus.refreshRate"))
        assertTrue(props.contains("debug.oculus.cpuLevel"))
        assertTrue(props.contains("debug.oculus.gpuLevel"))
        assertTrue(props.contains("debug.oculus.foveation.level"))
        assertTrue(props.contains("debug.oculus.textureWidth"))
        assertTrue(props.contains("debug.oculus.textureHeight"))
        assertTrue(props.contains("debug.oculus.enableGuardian"))
        assertTrue(props.contains("debug.oculus.forceASW"))
    }

    @Test
    fun allPresetsHaveNonEmptyLabels() {
        val allPresets = listOf(
            DeviceSettingsPresets.refreshRate(72),
            DeviceSettingsPresets.cpuLevel(3),
            DeviceSettingsPresets.gpuLevel(3),
            DeviceSettingsPresets.foveation(2),
            DeviceSettingsPresets.guardianOff(),
            DeviceSettingsPresets.guardianOn(),
            DeviceSettingsPresets.aswOff(),
            DeviceSettingsPresets.aswOn(),
            DeviceSettingsPresets.batterySaver(),
            DeviceSettingsPresets.balanced(),
            DeviceSettingsPresets.maxPower(),
            DeviceSettingsPresets.lowPower(),
            DeviceSettingsPresets.resetOverrides()
        )
        allPresets.flatten().forEach { cmd ->
            assertTrue("Label should not be blank: '${cmd.label}'", cmd.label.isNotBlank())
            assertTrue("Args should not be empty for '${cmd.label}'", cmd.args.isNotEmpty())
        }
    }
}
