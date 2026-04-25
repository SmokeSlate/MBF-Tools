package org.sm0ke.mbftools

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity

object AppNavigation {
    fun openScreen(
            activity: Activity,
            target: Class<out ComponentActivity>,
            configure: (Intent.() -> Unit)? = null
    ) {
        val intent =
                Intent(activity, target).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    configure?.invoke(this)
                }
        activity.startActivity(intent)
    }

    fun openBrowser(activity: Activity, url: String) {
        openScreen(activity, BrowserActivity::class.java) {
            putExtra(BrowserActivity.EXTRA_URL, url)
        }
    }
}
