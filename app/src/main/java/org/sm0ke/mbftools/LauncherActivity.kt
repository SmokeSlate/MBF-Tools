package org.sm0ke.mbftools

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        AppLog.info("Launcher", "Launcher opened.")

        val target =
                if (AppPrefs.isSetupComplete(this) && !SetupState.isWirelessDebuggingEnabled(this)) {
                    WirelessDebugRequiredActivity::class.java
                } else if (AppPrefs.isSetupComplete(this)) {
                    HomeActivity::class.java
                } else {
                    GuideActivity::class.java
                }

        if (target == HomeActivity::class.java) {
            AppPrefs.clearCurrentGuideStep(this)
        }
        startActivity(Intent(this, target))
        finish()
    }
}
