package org.sm0ke.mbftools

data class DeviceSettingCommand(val label: String, val args: List<String>)

object DeviceSettingsPresets {
    fun refreshRate(hz: Int): List<DeviceSettingCommand> =
            listOf(command("Refresh rate ${hz}Hz", "setprop", "debug.oculus.refreshRate", hz.toString()))

    fun cpuLevel(level: Int): List<DeviceSettingCommand> =
            listOf(command("CPU level $level", "setprop", "debug.oculus.cpuLevel", level.toString()))

    fun gpuLevel(level: Int): List<DeviceSettingCommand> =
            listOf(command("GPU level $level", "setprop", "debug.oculus.gpuLevel", level.toString()))

    fun foveation(level: Int): List<DeviceSettingCommand> =
            listOf(command("Foveation level $level", "setprop", "debug.oculus.foveation.level", level.toString()))

    fun textureSize(size: Int): List<DeviceSettingCommand> =
            listOf(
                    command("Texture width $size", "setprop", "debug.oculus.textureWidth", size.toString()),
                    command("Texture height $size", "setprop", "debug.oculus.textureHeight", size.toString())
            )

    fun guardianOff(): List<DeviceSettingCommand> =
            listOf(command("Guardian disabled", "setprop", "debug.oculus.enableGuardian", "0"))

    fun guardianOn(): List<DeviceSettingCommand> =
            listOf(command("Guardian enabled", "setprop", "debug.oculus.enableGuardian", "1"))

    fun aswOff(): List<DeviceSettingCommand> =
            listOf(command("ASW disabled", "setprop", "debug.oculus.forceASW", "0"))

    fun aswOn(): List<DeviceSettingCommand> =
            listOf(command("ASW enabled", "setprop", "debug.oculus.forceASW", "1"))

    fun trackingFrequency(hz: Int): List<DeviceSettingCommand> =
            listOf(command("Tracking frequency ${hz}Hz", "setprop", "debug.oculus.trackingFrequency", hz.toString()))

    fun batterySaver(): List<DeviceSettingCommand> =
            refreshRate(72) + cpuLevel(2) + gpuLevel(2) + foveation(3)

    fun balanced(): List<DeviceSettingCommand> =
            refreshRate(90) + cpuLevel(3) + gpuLevel(3) + foveation(2)

    fun maxPower(): List<DeviceSettingCommand> =
            refreshRate(120) + cpuLevel(4) + gpuLevel(4) + foveation(0)

    fun lowPower(): List<DeviceSettingCommand> =
            refreshRate(60) + cpuLevel(2) + gpuLevel(2) + foveation(3) + aswOn()

    fun resetOverrides(): List<DeviceSettingCommand> =
            listOf(
                    command("Refresh rate default", "setprop", "debug.oculus.refreshRate", "0"),
                    command("CPU default", "setprop", "debug.oculus.cpuLevel", "0"),
                    command("GPU default", "setprop", "debug.oculus.gpuLevel", "0"),
                    command("Foveation default", "setprop", "debug.oculus.foveation.level", "0"),
                    command("Texture width default", "setprop", "debug.oculus.textureWidth", "0"),
                    command("Texture height default", "setprop", "debug.oculus.textureHeight", "0"),
                    command("Guardian default", "setprop", "debug.oculus.enableGuardian", "1"),
                    command("ASW default", "setprop", "debug.oculus.forceASW", "0")
            )

    private fun command(label: String, vararg args: String): DeviceSettingCommand =
            DeviceSettingCommand(label, args.toList())
}
