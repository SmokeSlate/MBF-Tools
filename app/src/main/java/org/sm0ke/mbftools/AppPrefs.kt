package org.sm0ke.mbftools

import android.content.Context

object AppPrefs {
    private const val PREFS_NAME = "mbf_prefs"
    private const val KEY_PAIRING_PORT = "pairing_port"
    private const val KEY_DEBUG_PORT = "debug_port"
    private const val KEY_GAME_ID = "game_id"
    private const val KEY_SETUP_COMPLETE = "setup_complete"

    private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPairingPort(context: Context): String =
            prefs(context).getString(KEY_PAIRING_PORT, "") ?: ""

    fun setPairingPort(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PAIRING_PORT, value).apply()
    }

    fun getDebugPort(context: Context): String = prefs(context).getString(KEY_DEBUG_PORT, "") ?: ""

    fun setDebugPort(context: Context, value: String) {
        prefs(context).edit().putString(KEY_DEBUG_PORT, value).apply()
    }

    fun getGameId(context: Context): String {
        return prefs(context).getString(KEY_GAME_ID, "com.beatgames.beatsaber")
                ?: "com.beatgames.beatsaber"
    }

    fun setGameId(context: Context, value: String) {
        prefs(context)
                .edit()
                .putString(KEY_GAME_ID, value.ifBlank { "com.beatgames.beatsaber" })
                .apply()
    }

    fun isSetupComplete(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SETUP_COMPLETE, false)
    }

    fun setSetupComplete(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SETUP_COMPLETE, value).apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
