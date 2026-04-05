package com.example.devtoolsopener

import android.content.Context
import android.os.Build
import android.provider.Settings

object SetupState {
    private const val DEVELOPMENT_SETTINGS_KEY = "development_settings_enabled"
    private const val ADB_ENABLED_KEY = "adb_enabled"
    private const val ADB_WIFI_KEY = "adb_wifi_enabled"

    fun isDeveloperModeEnabled(context: Context): Boolean {
        return readGlobalOrSecureFlag(context, DEVELOPMENT_SETTINGS_KEY) ||
            readGlobalOrSecureFlag(context, ADB_ENABLED_KEY)
    }

    fun isWirelessDebuggingEnabled(context: Context): Boolean {
        return readGlobalOrSecureFlag(context, ADB_WIFI_KEY)
    }

    private fun readGlobalOrSecureFlag(context: Context, name: String): Boolean {
        val cr = context.contentResolver

        fun getIntSecure(): Int = try {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(cr, name, 0)
        } catch (_: Exception) {
            0
        }

        fun getIntGlobal(): Int = try {
            if (Build.VERSION.SDK_INT >= 17) Settings.Global.getInt(cr, name, 0) else 0
        } catch (_: Exception) {
            0
        }

        return getIntGlobal() == 1 || getIntSecure() == 1
    }
}
