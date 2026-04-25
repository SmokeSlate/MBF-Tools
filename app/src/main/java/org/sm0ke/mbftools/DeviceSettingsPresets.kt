package org.sm0ke.mbftools

data class DeviceSettingPreset(val labelRes: Int, val commands: List<List<String>>)

object DeviceSettingsPresets {
    val refreshRates: List<DeviceSettingPreset> =
            listOf(
                    DeviceSettingPreset(
                            R.string.device_settings_refresh_60,
                            listOf(listOf("setprop", "debug.oculus.refreshRate", "60"))
                    ),
                    DeviceSettingPreset(
                            R.string.device_settings_refresh_72,
                            listOf(listOf("setprop", "debug.oculus.refreshRate", "72"))
                    ),
                    DeviceSettingPreset(
                            R.string.device_settings_refresh_90,
                            listOf(listOf("setprop", "debug.oculus.refreshRate", "90"))
                    ),
                    DeviceSettingPreset(
                            R.string.device_settings_refresh_120,
                            listOf(listOf("setprop", "debug.oculus.refreshRate", "120"))
                    )
            )

    val brightnessLevels: List<DeviceSettingPreset> =
            listOf(
                    DeviceSettingPreset(
                            R.string.device_settings_brightness_low,
                            listOf(
                                    listOf("settings", "put", "system", "screen_brightness_mode", "0"),
                                    listOf("settings", "put", "system", "screen_brightness", "40")
                            )
                    ),
                    DeviceSettingPreset(
                            R.string.device_settings_brightness_medium,
                            listOf(
                                    listOf("settings", "put", "system", "screen_brightness_mode", "0"),
                                    listOf("settings", "put", "system", "screen_brightness", "110")
                            )
                    ),
                    DeviceSettingPreset(
                            R.string.device_settings_brightness_high,
                            listOf(
                                    listOf("settings", "put", "system", "screen_brightness_mode", "0"),
                                    listOf("settings", "put", "system", "screen_brightness", "180")
                            )
                    ),
                    DeviceSettingPreset(
                            R.string.device_settings_brightness_max,
                            listOf(
                                    listOf("settings", "put", "system", "screen_brightness_mode", "0"),
                                    listOf("settings", "put", "system", "screen_brightness", "255")
                            )
                    )
            )

    val animationScales: List<DeviceSettingPreset> =
            listOf(
                    DeviceSettingPreset(
                            R.string.device_settings_animation_off,
                            animationCommands("0")
                    ),
                    DeviceSettingPreset(
                            R.string.device_settings_animation_half,
                            animationCommands("0.5")
                    ),
                    DeviceSettingPreset(
                            R.string.device_settings_animation_normal,
                            animationCommands("1.0")
                    )
            )

    private fun animationCommands(value: String): List<List<String>> =
            listOf(
                    listOf("settings", "put", "global", "window_animation_scale", value),
                    listOf("settings", "put", "global", "transition_animation_scale", value),
                    listOf("settings", "put", "global", "animator_duration_scale", value)
            )
}
