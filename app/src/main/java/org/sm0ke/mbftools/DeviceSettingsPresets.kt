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

    fun batterySaver(): List<DeviceSettingCommand> =
            cpuLevel(2) + gpuLevel(2) + foveation(3)

    fun balanced(): List<DeviceSettingCommand> =
            cpuLevel(3) + gpuLevel(3) + foveation(2)

    fun maxPower(): List<DeviceSettingCommand> =
            cpuLevel(4) + gpuLevel(4) + foveation(0)

    fun resetOverrides(): List<DeviceSettingCommand> =
            listOf(
                    command("Refresh rate default", "setprop", "debug.oculus.refreshRate", "0"),
                    command("CPU default", "setprop", "debug.oculus.cpuLevel", "0"),
                    command("GPU default", "setprop", "debug.oculus.gpuLevel", "0"),
                    command("Foveation default", "setprop", "debug.oculus.foveation.level", "0"),
                    command("Texture width default", "setprop", "debug.oculus.textureWidth", "0"),
                    command("Texture height default", "setprop", "debug.oculus.textureHeight", "0")
            )

    private fun command(label: String, vararg args: String): DeviceSettingCommand =
            DeviceSettingCommand(label, args.toList())
}
