package com.example.devtoolsopener

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        // If developer mode appears enabled, try Wireless Debugging first; otherwise, show guide.
        if (isDeveloperModeEnabled()) {
            log("Developer mode detected. Use the buttons below to open targets.")
        } else if (!getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("hideGuide", false)) {
            showDevModeGuide()
        }

        // Wire buttons
        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            if (!openSettingsInstalledAppDetails()) {
                Toast.makeText(this, "Could not open Settings app details.", Toast.LENGTH_SHORT).show()
                log("Settings app details launch failed")
            }
        }

        findViewById<Button>(R.id.btnOpenDeveloperSettings).setOnClickListener {
            if (!openDeveloperOptions()) {
                Toast.makeText(this, "Developer Options not available.", Toast.LENGTH_SHORT).show()
                log("Developer Options launch attempts failed")
            }
        }

        if (isDebuggableBuild()) {
            populateExportedSettingsActivities()
        } else {
            findViewById<TextView>(R.id.labelDiscovered)?.visibility = View.GONE
            findViewById<ScrollView>(R.id.scrollDiscovered)?.visibility = View.GONE
            findViewById<TextView>(R.id.txtLog)?.visibility = View.GONE
        }
    }

    private fun openDeveloperOptions(): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                `package` = "com.android.settings"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME)
            } to "Developer options (targeted action)",
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME)
            } to "Developer options (public action)",
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsActivity")
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } to "Developer options component 1",
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName("com.android.settings", "com.android.settings.DevelopmentSettings")
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } to "Developer options component 2",
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName("com.android.settings", "com.android.settings.SubSettings")
                putExtra(":settings:show_fragment", "com.android.settings.DevelopmentSettingsDashboardFragment")
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } to "Developer options fragment",
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName("com.android.settings", "com.android.settings.Settings")
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME)
            } to "Main Settings"
        )

        for ((intent, label) in intents) {
            if (tryLaunch(intent, label, silentOnSuccess = label != "Developer options component 1")) {
                if (label != "Developer options component 1") {
                    Toast.makeText(this, "Opened: $label", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        return false
    }

    private fun openSettingsInstalledAppDetails(): Boolean {
        val settingsPkg = "com.android.settings"

        @Suppress("DEPRECATION")
        val primaryIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$settingsPkg")
            `package` = settingsPkg
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET
            )
        }
        if (tryLaunch(primaryIntent, "Settings app (Installed App Details)")) return true

        val launcherIntent = packageManager.getLaunchIntentForPackage(settingsPkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        if (launcherIntent != null && tryLaunch(launcherIntent, "Android Settings app launcher", silentOnSuccess = true)) {
            return true
        }

        val fallbacks = listOf(
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(settingsPkg, "com.android.settings.SubSettings")
                putExtra(":settings:show_fragment", "com.android.settings.applications.InstalledAppDetails")
                putExtra(":settings:show_fragment_title_resid", 0)
                putExtra(":settings:show_fragment_args", Bundle().apply {
                    putString("package", settingsPkg)
                })
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(settingsPkg, "com.android.settings.applications.InstalledAppDetails")
                putExtra("package", settingsPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
        )
        for (intent in fallbacks) {
            if (tryLaunch(intent, "Settings app (Installed App Details)", silentOnSuccess = true)) return true
        }

        return false
    }

    private fun openTargetedDevOptions(): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            `package` = "com.android.settings"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME)
        }
        return tryLaunch(intent, "Dev options action (targeted)", silentOnSuccess = true)
    }

    private fun openWirelessDebugging(): Boolean {
        // Available API 30+; still try on lower with catch
        val base = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
        val variants = listOf(
            base,
            Intent(base).apply { `package` = "com.android.settings" }
        )
        for (i in variants) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME)
            if (tryLaunch(i, "Wireless Debugging", silentOnSuccess = true)) return true
        }
        return false
    }

    private fun safeGetActivities(pkgName: String): Array<android.content.pm.ActivityInfo> {
        val pm = packageManager
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())).activities
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, PackageManager.GET_ACTIVITIES).activities
            } ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }
    }

    private fun isDeveloperModeEnabled(): Boolean {
        val cr = contentResolver
        fun getIntSecure(name: String): Int = try {
            @Suppress("DEPRECATION")
            android.provider.Settings.Secure.getInt(cr, name, 0)
        } catch (_: Exception) { 0 }
        fun getIntGlobal(name: String): Int = try {
            if (Build.VERSION.SDK_INT >= 17) android.provider.Settings.Global.getInt(cr, name, 0) else 0
        } catch (_: Exception) { 0 }

        val dev1 = getIntGlobal(android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
        val dev2 = getIntSecure(android.provider.Settings.Secure.DEVELOPMENT_SETTINGS_ENABLED)
        val adb1 = getIntGlobal(android.provider.Settings.Global.ADB_ENABLED)
        val adb2 = getIntSecure(android.provider.Settings.Secure.ADB_ENABLED)

        val enabled = (dev1 == 1 || dev2 == 1 || adb1 == 1 || adb2 == 1)
        // Cache result if enabled so we don’t bother user next time
        if (enabled) getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("hideGuide", true).apply()
        return enabled
    }

    private fun isAdbEnabled(): Boolean {
        val cr = contentResolver
        fun giG(name: String) = try { if (Build.VERSION.SDK_INT >= 17) Settings.Global.getInt(cr, name, 0) else 0 } catch (_: Exception) { 0 }
        fun giS(name: String) = try { @Suppress("DEPRECATION") Settings.Secure.getInt(cr, name, 0) } catch (_: Exception) { 0 }
        val adb1 = giG(Settings.Global.ADB_ENABLED)
        val adb2 = giS(Settings.Secure.ADB_ENABLED)
        return adb1 == 1 || adb2 == 1
    }

    private fun isWifiOnOrConnected(): Boolean {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) { false }
    }

    private fun maybePromptAdbWifi() {
        val adb = isAdbEnabled()
        val wifi = isWifiOnOrConnected()
        if (adb && wifi) return
        val msg = buildString {
            append("To use Wireless Debugging, make sure:\n\n")
            append("• Developer options: Enabled (tap Build number 7 times).\n")
            append("• ADB debugging: ")
            append(if (adb) "ON" else "OFF — enable in Developer options.")
            append("\n")
            append("• Wi‑Fi: ")
            append(if (wifi) "Connected" else "OFF/Not connected — connect to Wi‑Fi.")
        }
        AlertDialog.Builder(this)
            .setTitle("Enable ADB and Wi‑Fi")
            .setMessage(msg)
            .setPositiveButton("Open Wireless Debugging") { _, _ -> openWirelessDebugging() }
            .setNeutralButton("Wi‑Fi Settings") { _, _ -> tryLaunch(Intent(Settings.ACTION_WIFI_SETTINGS), "Wi‑Fi Settings", true) }
            .setNegativeButton("Developer Options") { _, _ -> openTargetedDevOptions() }
            .show()
    }

    private fun showDevModeGuide() {
        // Use generic Android steps per your request (Quest treated same as Android)
        val message = (
            "To enable Android Developer Options:\n\n" +
            "1. Open Settings → About device.\n" +
            "2. Tap Build number seven times to enable Developer options.\n" +
            "3. Go back and open System → Developer options.\n" +
            "4. Enable USB/Wireless debugging (ADB).\n\n" +
            "Tip: Use Wireless Debugging for ADB over Wi‑Fi (both device and host must be on the same network)."
        )

        AlertDialog.Builder(this)
            .setTitle("Enable Developer Mode")
            .setMessage(message)
            .setPositiveButton("Open Wireless Debugging") { _, _ -> openWirelessDebugging() }
            .setNeutralButton("Check again") { _, _ ->
                if (isDeveloperModeEnabled()) {
                    Toast.makeText(this, "Developer mode detected. This message won’t show again.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Still not detected. Complete the steps, then try again.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Don’t show again") { _, _ ->
                getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("hideGuide", true).apply()
                Toast.makeText(this, "Guide suppressed.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun tryLaunch(intent: Intent, label: String, silentOnSuccess: Boolean = false): Boolean {
        return try {
            startActivity(intent)
            if (!silentOnSuccess) Toast.makeText(this, "Launched: $label", Toast.LENGTH_SHORT).show()
            log("OK: $label -> $intent")
            true
        } catch (e: Exception) {
            Toast.makeText(this, "Failed: $label", Toast.LENGTH_SHORT).show()
            log("Fail: $label -> ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun log(msg: String) {
        if (!isDebuggableBuild()) return
        val tv = findViewById<TextView>(R.id.txtLog)
        tv?.append(msg + "\n")
    }

    private fun isDebuggableBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun populateExportedSettingsActivities() {
        val container = findViewById<LinearLayout>(R.id.containerActivities) ?: return
        val pm = packageManager
        val pkgName = "com.android.settings"
        val info: PackageInfo? = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, PackageManager.GET_ACTIVITIES)
            }
        } catch (e: Exception) {
            log("Could not get $pkgName activities: ${e.javaClass.simpleName}")
            null
        }

        val acts = info?.activities ?: emptyArray()
        if (acts.isEmpty()) {
            log("No visible activities from $pkgName (package visibility?)")
        }

        acts.filter { it.exported }
            .sortedBy { it.name }
            .forEach { ai ->
                val btn = Button(this)
                btn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                btn.text = ai.name + if (!ai.enabled) " (disabled)" else ""
                btn.setOnClickListener {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName(pkgName, ai.name)
                        addCategory(Intent.CATEGORY_DEFAULT)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    tryLaunch(intent, ai.name)
                }
                container.addView(btn)
            }
    }
}

