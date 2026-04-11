package org.sm0ke.mbftools

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class WirelessDebugRequiredActivity : ComponentActivity() {
    private lateinit var statusDetail: TextView
    private lateinit var retryButton: Button
    private lateinit var openDeveloperSettingsButton: Button
    private lateinit var guideButton: Button
    private lateinit var advancedButton: Button
    private lateinit var shareLogsButton: Button
    private var attemptedAutoOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        AppLog.warn("WirelessBlock", "Opened blocker screen because Wireless Debugging is disabled after setup.")
        setContentView(R.layout.activity_wireless_required)

        statusDetail = findViewById(R.id.txtWirelessRequiredDetail)
        retryButton = findViewById(R.id.btnWirelessRequiredRetry)
        openDeveloperSettingsButton = findViewById(R.id.btnWirelessRequiredOpenDeveloperSettings)
        guideButton = findViewById(R.id.btnWirelessRequiredGuide)
        advancedButton = findViewById(R.id.btnWirelessRequiredAdvanced)
        shareLogsButton = findViewById(R.id.btnWirelessRequiredShareLogs)

        retryButton.setOnClickListener { routeForwardIfReady() }
        openDeveloperSettingsButton.setOnClickListener {
            if (!openDeveloperOptions()) {
                Toast.makeText(
                                this,
                                getString(
                                        R.string.toast_could_not_open,
                                        getString(R.string.target_developer_settings)
                                ),
                                Toast.LENGTH_SHORT
                        )
                        .show()
            }
        }
        guideButton.setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        advancedButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        shareLogsButton.setOnClickListener {
            DebugShareHelper.share(
                    activity = this,
                    sourceTag = "WirelessBlock",
                    onBusyChanged = { busy -> setButtonsEnabled(!busy) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        routeForwardIfReady()
        if (!attemptedAutoOpen && !SetupState.isWirelessDebuggingEnabled(this)) {
            attemptedAutoOpen = true
            openDeveloperOptions()
        }
    }

    private fun routeForwardIfReady() {
        val wirelessEnabled = SetupState.isWirelessDebuggingEnabled(this)
        val developerModeEnabled = SetupState.isDeveloperModeEnabled(this)
        if (wirelessEnabled) {
            AppLog.info("WirelessBlock", "Wireless Debugging is enabled again. Returning to home.")
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        statusDetail.text =
                getString(
                        R.string.wireless_required_detail,
                        if (developerModeEnabled) getString(R.string.status_enabled)
                        else getString(R.string.status_not_detected)
                )
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        retryButton.isEnabled = enabled
        openDeveloperSettingsButton.isEnabled = enabled
        guideButton.isEnabled = enabled
        advancedButton.isEnabled = enabled
        shareLogsButton.isEnabled = enabled
    }

    private fun openDeveloperOptions(): Boolean {
        val intents =
                listOf(
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                            `package` = SETTINGS_PACKAGE
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME)
                        },
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME)
                        },
                        Intent(Intent.ACTION_MAIN).apply {
                            component =
                                    ComponentName(
                                            SETTINGS_PACKAGE,
                                            "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"
                                    )
                            addCategory(Intent.CATEGORY_DEFAULT)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                        Intent(Intent.ACTION_MAIN).apply {
                            component =
                                    ComponentName(
                                            SETTINGS_PACKAGE,
                                            "com.android.settings.Settings\$DevelopmentSettingsActivity"
                                    )
                            addCategory(Intent.CATEGORY_DEFAULT)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                )

        intents.forEach { intent ->
            val canLaunch = runCatching { packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)
            if (canLaunch) {
                runCatching {
                            startActivity(intent)
                            AppLog.info("WirelessBlock", "Opened Developer Settings from blocker screen.")
                            return true
                        }
                        .getOrElse {
                            AppLog.warn("WirelessBlock", "Failed to open Developer Settings: ${it.message ?: "unknown"}")
                        }
            }
        }
        return false
    }

    companion object {
        private const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
