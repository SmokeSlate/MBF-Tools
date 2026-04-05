package org.sm0ke.mbftools

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val target =
                if (AppPrefs.isSetupComplete(this)) {
                    HomeActivity::class.java
                } else {
                    GuideActivity::class.java
                }

        startActivity(Intent(this, target))
        finish()
    }
}
