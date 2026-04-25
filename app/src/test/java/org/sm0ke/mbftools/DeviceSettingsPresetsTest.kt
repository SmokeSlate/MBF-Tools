package org.sm0ke.mbftools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSettingsPresetsTest {
    @Test
    fun refreshRatePresets_coverExpectedQuestOptions() {
        val rates =
                DeviceSettingsPresets.refreshRates.mapNotNull { preset ->
                    preset.commands.singleOrNull()?.lastOrNull()
                }

        assertEquals(listOf("60", "72", "90", "120"), rates)
    }

    @Test
    fun brightnessPresets_forceManualModeBeforeApplyingBrightness() {
        DeviceSettingsPresets.brightnessLevels.forEach { preset ->
            assertEquals(
                    listOf("settings", "put", "system", "screen_brightness_mode", "0"),
                    preset.commands.first()
            )
            assertEquals("screen_brightness", preset.commands.last()[3])
        }
    }

    @Test
    fun animationPresets_writeAllThreeAndroidAnimationScales() {
        DeviceSettingsPresets.animationScales.forEach { preset ->
            val keys = preset.commands.map { it[3] }
            assertEquals(
                    listOf(
                            "window_animation_scale",
                            "transition_animation_scale",
                            "animator_duration_scale"
                    ),
                    keys
            )
            assertTrue(preset.commands.all { it.last() == preset.commands.first().last() })
        }
    }
}
