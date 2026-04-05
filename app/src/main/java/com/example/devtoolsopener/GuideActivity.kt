package com.example.devtoolsopener

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GuideActivity : ComponentActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollingRunnable = object : Runnable {
        override fun run() {
            refreshGuideState()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private lateinit var stepCounter: TextView
    private lateinit var stepTitle: TextView
    private lateinit var stepBody: TextView
    private lateinit var stepImage: ImageView
    private lateinit var stepCaption: TextView
    private lateinit var guideStatus: TextView
    private lateinit var primaryButton: Button
    private lateinit var nextButton: Button
    private lateinit var backButton: Button
    private lateinit var precheckPanel: android.view.View
    private lateinit var pairingPanel: android.view.View
    private lateinit var connectPanel: android.view.View
    private lateinit var multipleAccountsCheckBox: CheckBox
    private lateinit var unlockCodeCheckBox: CheckBox
    private lateinit var pairingCodeInput: EditText
    private lateinit var pairingPortInput: EditText
    private lateinit var debugPortInput: EditText
    private lateinit var detectPortButton: Button
    private lateinit var openFixFormButton: Button
    private lateinit var openFaqButton: Button

    @Volatile
    private var connectedDeviceName: String? = null

    private var stepIndex = 0
    private val autoOpenedSteps = mutableSetOf<Int>()
    private var detectedWirelessDebugging = false
    private var hasAutoLaunchedCompletedSetup = false

    private val steps by lazy {
        listOf(
            GuideStep(
                titleRes = R.string.guide_step_welcome_title,
                bodyRes = R.string.guide_step_welcome_body,
                captionRes = R.string.guide_caption_welcome,
                primaryLabelRes = null,
                panel = GuidePanel.PRECHECK,
                primaryAction = null
            ),
            GuideStep(
                titleRes = R.string.guide_step_enable_dev_mode_title,
                bodyRes = R.string.guide_step_enable_dev_mode_body,
                captionRes = R.string.guide_caption_enable_dev_mode,
                primaryLabelRes = R.string.action_open_settings,
                panel = GuidePanel.NONE,
                primaryAction = { openSettingsAppInfo() }
            ),
            GuideStep(
                titleRes = R.string.guide_step_about_title,
                bodyRes = R.string.guide_step_about_body,
                captionRes = R.string.guide_caption_about,
                primaryLabelRes = null,
                panel = GuidePanel.NONE,
                primaryAction = null
            ),
            GuideStep(
                titleRes = R.string.guide_step_dev_title,
                bodyRes = R.string.guide_step_dev_body,
                captionRes = R.string.guide_caption_dev_settings,
                primaryLabelRes = R.string.action_open_developer_settings,
                panel = GuidePanel.NONE,
                primaryAction = { openDeveloperOptions() }
            ),
            GuideStep(
                titleRes = R.string.guide_step_wireless_title,
                bodyRes = R.string.guide_step_wireless_body,
                captionRes = R.string.guide_caption_wireless_debugging,
                primaryLabelRes = null,
                panel = GuidePanel.NONE,
                primaryAction = null
            ),
            GuideStep(
                titleRes = R.string.guide_step_pair_title,
                bodyRes = R.string.guide_step_pair_body,
                captionRes = R.string.guide_caption_pair_code,
                primaryLabelRes = R.string.action_pair_device,
                panel = GuidePanel.PAIR,
                primaryAction = { pairDevice() }
            ),
            GuideStep(
                titleRes = R.string.guide_step_connect_title,
                bodyRes = R.string.guide_step_connect_body,
                captionRes = R.string.guide_caption_connect_port,
                primaryLabelRes = R.string.action_connect_device,
                panel = GuidePanel.CONNECT,
                primaryAction = { connectToDevice() }
            ),
            GuideStep(
                titleRes = R.string.guide_step_launch_title,
                bodyRes = R.string.guide_step_launch_body,
                captionRes = R.string.guide_caption_launch_mbf,
                primaryLabelRes = R.string.action_launch_integrated_mbf,
                panel = GuidePanel.NONE,
                primaryAction = { launchIntegratedMbf() }
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)

        stepCounter = findViewById(R.id.txtStepCounter)
        stepTitle = findViewById(R.id.txtGuideTitle)
        stepBody = findViewById(R.id.txtGuideBody)
        stepImage = findViewById(R.id.imgGuideStep)
        stepCaption = findViewById(R.id.txtGuideCaption)
        guideStatus = findViewById(R.id.txtGuideStatus)
        primaryButton = findViewById(R.id.btnGuidePrimary)
        nextButton = findViewById(R.id.btnGuideNext)
        backButton = findViewById(R.id.btnGuideBack)
        precheckPanel = findViewById(R.id.panelGuidePrecheck)
        pairingPanel = findViewById(R.id.panelGuidePair)
        connectPanel = findViewById(R.id.panelGuideConnect)
        multipleAccountsCheckBox = findViewById(R.id.checkboxGuideMultipleAccounts)
        unlockCodeCheckBox = findViewById(R.id.checkboxGuideUnlockCode)
        pairingCodeInput = findViewById(R.id.inputGuidePairingCode)
        pairingPortInput = findViewById(R.id.inputGuidePairingPort)
        debugPortInput = findViewById(R.id.inputGuideDebugPort)
        detectPortButton = findViewById(R.id.btnGuideDetectPort)
        openFixFormButton = findViewById(R.id.btnGuideFixForm)
        openFaqButton = findViewById(R.id.btnGuideFaq)

        pairingPortInput.setText(AppPrefs.getPairingPort(this))
        debugPortInput.setText(AppPrefs.getDebugPort(this))

        val precheckListener = android.widget.CompoundButton.OnCheckedChangeListener { _, _ ->
            updateNextAvailability()
        }
        multipleAccountsCheckBox.setOnCheckedChangeListener(precheckListener)
        unlockCodeCheckBox.setOnCheckedChangeListener(precheckListener)

        findViewById<Button>(R.id.btnOpenAdvancedSmall).setOnClickListener {
            openAdvanced()
        }

        detectPortButton.setOnClickListener {
            detectDebugPort()
        }
        openFixFormButton.setOnClickListener { openUrl(FIX_FORM_URL) }
        openFaqButton.setOnClickListener { openUrl(FAQ_PAGE_URL) }

        backButton.setOnClickListener {
            goBackOneStep()
        }

        nextButton.setOnClickListener {
            if (stepIndex < steps.lastIndex) {
                stepIndex += 1
                renderStep()
            } else {
                finish()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goBackOneStep()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        renderStep()
    }

    override fun onResume() {
        super.onResume()
        refreshGuideState()
        startPolling()
    }

    override fun onPause() {
        stopPolling()
        super.onPause()
    }

    override fun onDestroy() {
        stopPolling()
        super.onDestroy()
        worker.shutdownNow()
    }

    private fun renderStep() {
        val step = steps[stepIndex]
        stepCounter.text = getString(R.string.guide_step_counter, stepIndex + 1, steps.size)
        stepTitle.setText(step.titleRes)
        stepBody.setText(step.bodyRes)
        stepCaption.setText(step.captionRes)
        stepImage.setImageResource(android.R.drawable.ic_menu_gallery)
        updatePanelVisibility(step.panel)
        if (step.primaryLabelRes != null && step.primaryAction != null) {
            primaryButton.visibility = android.view.View.VISIBLE
            primaryButton.setText(step.primaryLabelRes)
            primaryButton.setOnClickListener { step.primaryAction.invoke() }
        } else {
            primaryButton.visibility = android.view.View.GONE
        }
        backButton.isEnabled = stepIndex > 0
        nextButton.text = getString(
            if (stepIndex == steps.lastIndex) R.string.guide_action_finish else R.string.guide_action_next
        )
        maybeAutoOpenCurrentStep()
        updateNextAvailability()
        refreshGuideState()
    }

    private fun goBackOneStep(): Boolean {
        if (stepIndex <= 0) {
            return false
        }
        stepIndex -= 1
        renderStep()
        return true
    }

    private fun openAdvanced() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun openUrl(url: String) {
        startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url))
    }

    private fun startPolling() {
        mainHandler.removeCallbacks(pollingRunnable)
        mainHandler.postDelayed(pollingRunnable, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        mainHandler.removeCallbacks(pollingRunnable)
    }

    private fun updatePanelVisibility(panel: GuidePanel) {
        precheckPanel.visibility = if (panel == GuidePanel.PRECHECK) android.view.View.VISIBLE else android.view.View.GONE
        pairingPanel.visibility = if (panel == GuidePanel.PAIR) android.view.View.VISIBLE else android.view.View.GONE
        connectPanel.visibility = if (panel == GuidePanel.CONNECT) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun updateNextAvailability() {
        nextButton.isEnabled = when (stepIndex) {
            STEP_PRECHECK -> multipleAccountsCheckBox.isChecked && unlockCodeCheckBox.isChecked
            STEP_ABOUT -> isDeveloperModeEnabled()
            STEP_WIRELESS -> isWirelessDebuggingEnabled()
            else -> true
        }
    }

    private fun refreshGuideState() {
        worker.execute {
            val device = runCatching { AdbManager.getAuthorizedDevices(this).firstOrNull() }.getOrNull()
            val detectedDebugPort = runCatching { AdbManager.detectDebugPort(this) }.getOrDefault(0)
            val wirelessEnabled = isWirelessDebuggingSettingEnabled() || detectedDebugPort > 0
            connectedDeviceName = device?.name

            runOnUiThread {
                detectedWirelessDebugging = wirelessEnabled
                guideStatus.text = if (device != null) {
                    getString(R.string.status_connected_device, device.name)
                } else {
                    getString(R.string.status_not_connected)
                }
                maybeLaunchCompletedSetup()
                updateNextAvailability()
            }
        }
    }

    private fun pairDevice() {
        val pairingCode = pairingCodeInput.text.toString().trim()
        val pairingPort = pairingPortInput.text.toString().trim().toIntOrNull()

        if (pairingCode.length != 6 || pairingPort == null) {
            toast(getString(R.string.toast_invalid_pairing))
            return
        }

        AppPrefs.setPairingPort(this, pairingPort.toString())
        setBusy(true)
        worker.execute {
            val result = runCatching { AdbManager.pair(this, pairingPort, pairingCode) }

            runOnUiThread {
                setBusy(false)
                result.onSuccess { command ->
                    if (command.exitCode == 0 && !command.stdout.startsWith("Failed:", ignoreCase = true)) {
                        toast(getString(R.string.toast_pair_success))
                        if (stepIndex == STEP_PAIR) {
                            stepIndex = STEP_CONNECT
                            renderStep()
                        }
                        refreshGuideState()
                    } else {
                        toast(command.bestMessage(getString(R.string.toast_pair_failed)))
                    }
                }.onFailure {
                    toast(it.message ?: getString(R.string.toast_pair_failed))
                }
            }
        }
    }

    private fun detectDebugPort() {
        detectDebugPort(showToast = true)
    }

    private fun detectDebugPort(showToast: Boolean) {
        setBusy(true)
        worker.execute {
            val port = runCatching { AdbManager.detectDebugPort(this) }.getOrDefault(0)
            runOnUiThread {
                setBusy(false)
                if (port > 0) {
                    autofillDebugPort(port, force = true)
                    if (showToast) {
                        toast(getString(R.string.toast_debug_port_detected, port))
                    }
                } else if (showToast) {
                    toast(getString(R.string.toast_debug_port_missing))
                }
            }
        }
    }

    private fun connectToDevice() {
        val debugPort = debugPortInput.text.toString().trim().toIntOrNull()
        if (debugPort == null) {
            toast(getString(R.string.toast_invalid_debug_port))
            return
        }

        AppPrefs.setDebugPort(this, debugPort.toString())
        setBusy(true)
        worker.execute {
            val result = runCatching { AdbManager.connect(this, debugPort) }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { command ->
                    if (command.exitCode == 0 && command.stdout.contains("connected", ignoreCase = true)) {
                        connectedDeviceName = AdbManager.getAuthorizedDevices(this).firstOrNull()?.name
                        runCatching { AdbManager.grantSelfPermissions(this, connectedDeviceName) }
                        AppPrefs.setSetupComplete(this, true)
                        toast(getString(R.string.toast_connect_success))
                        if (stepIndex == STEP_CONNECT) {
                            stepIndex = STEP_LAUNCH
                            renderStep()
                        }
                        refreshGuideState()
                    } else {
                        toast(command.bestMessage(getString(R.string.toast_connect_failed)))
                    }
                }.onFailure {
                    toast(it.message ?: getString(R.string.toast_connect_failed))
                }
            }
        }
    }

    private fun launchIntegratedMbf() {
        val device = connectedDeviceName
        if (device == null) {
            toast(getString(R.string.toast_connect_first))
            refreshGuideState()
            return
        }

        setBusy(true)
        worker.execute {
            runCatching { AdbManager.grantSelfPermissions(this, device) }
            val browserUrl = runCatching {
                val baseUrl = BridgeManager.startOrGetBrowserUrl(this, MBF_APP_URL)
                buildBrowserUrl(baseUrl)
            }

            runOnUiThread {
                setBusy(false)
                browserUrl.onSuccess { url ->
                    startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url))
                }.onFailure {
                    toast(it.message ?: getString(R.string.toast_launch_failed))
                }
            }
        }
    }

    private fun setBusy(isBusy: Boolean) {
        primaryButton.isEnabled = !isBusy
        detectPortButton.isEnabled = !isBusy
        nextButton.isEnabled = !isBusy && isNextStepAvailable()
        backButton.isEnabled = !isBusy && stepIndex > 0
    }

    private fun isNextStepAvailable(): Boolean {
        return when (stepIndex) {
            STEP_PRECHECK -> multipleAccountsCheckBox.isChecked && unlockCodeCheckBox.isChecked
            STEP_ABOUT -> isDeveloperModeEnabled()
            STEP_WIRELESS -> isWirelessDebuggingEnabled()
            else -> true
        }
    }

    private fun buildBrowserUrl(baseUrl: String): String {
        val params = mutableListOf<String>()
        val gameId = AppPrefs.getGameId(this)
        if (gameId.isNotBlank()) {
            params.add("game_id=${Uri.encode(gameId)}")
        }
        if (params.isEmpty()) {
            return baseUrl
        }
        val separator = if (baseUrl.contains("?")) "&" else "?"
        return baseUrl + separator + params.joinToString("&")
    }

    private fun autofillDebugPort(port: Int, force: Boolean = false) {
        if (port <= 0) {
            return
        }
        if (!force && debugPortInput.text.toString().trim().toIntOrNull() != null) {
            return
        }
        debugPortInput.setText(port.toString())
        AppPrefs.setDebugPort(this, port.toString())
    }

    private fun openDeveloperOptions(): Boolean {
        val launched = launchFirstAvailable(
            listOf(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    `package` = SETTINGS_PACKAGE
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME)
                },
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME)
                },
                Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(
                        SETTINGS_PACKAGE,
                        "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"
                    )
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(
                        SETTINGS_PACKAGE,
                        "com.android.settings.Settings\$DevelopmentSettingsActivity"
                    )
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(
                        SETTINGS_PACKAGE,
                        "com.android.settings.DevelopmentSettings"
                    )
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        )

        if (!launched) {
            toast(getString(R.string.toast_could_not_open, getString(R.string.target_developer_settings)))
        }
        return launched
    }

    private fun openSettingsAppInfo(): Boolean {
        val launched = launchFirstAvailable(
            listOf(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$SETTINGS_PACKAGE")
                    `package` = SETTINGS_PACKAGE
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                },
                Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(SETTINGS_PACKAGE, "com.android.settings.SubSettings")
                    putExtra(":settings:show_fragment", "com.android.settings.applications.InstalledAppDetails")
                    putExtra(":settings:show_fragment_title_resid", 0)
                    putExtra(":settings:show_fragment_args", Bundle().apply {
                        putString("package", SETTINGS_PACKAGE)
                    })
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                packageManager.getLaunchIntentForPackage(SETTINGS_PACKAGE)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
            ).filterNotNull()
        )

        if (!launched) {
            toast(getString(R.string.toast_could_not_open, getString(R.string.target_settings_app_info)))
        }
        return launched
    }

    private fun maybeAutoOpenCurrentStep() {
        if (!autoOpenedSteps.add(stepIndex)) {
            return
        }

        when (stepIndex) {
            STEP_ENABLE_DEV_MODE -> openSettingsAppInfo()
            STEP_DEV -> openDeveloperOptions()
        }
    }

    private fun isDeveloperModeEnabled(): Boolean {
        return readGlobalOrSecureFlag(DEVELOPMENT_SETTINGS_KEY) || readGlobalOrSecureFlag(ADB_ENABLED_KEY)
    }

    private fun isWirelessDebuggingEnabled(): Boolean {
        return detectedWirelessDebugging || isWirelessDebuggingSettingEnabled()
    }

    private fun isWirelessDebuggingSettingEnabled(): Boolean {
        return readGlobalOrSecureFlag(ADB_WIFI_KEY)
    }

    private fun readGlobalOrSecureFlag(name: String): Boolean {
        fun getIntSecure(): Int = try {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(contentResolver, name, 0)
        } catch (_: Exception) {
            0
        }

        fun getIntGlobal(): Int = try {
            Settings.Global.getInt(contentResolver, name, 0)
        } catch (_: Exception) {
            0
        }

        return getIntGlobal() == 1 || getIntSecure() == 1
    }

    private fun maybeLaunchCompletedSetup() {
        if (hasAutoLaunchedCompletedSetup || connectedDeviceName == null) {
            return
        }
        if (!isDeveloperModeEnabled() || !isWirelessDebuggingEnabled()) {
            return
        }

        hasAutoLaunchedCompletedSetup = true
        if (stepIndex != STEP_LAUNCH) {
            stepIndex = STEP_LAUNCH
            renderStep()
        }
        launchIntegratedMbf()
    }

    private fun launchFirstAvailable(intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                startActivity(intent)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val STEP_PRECHECK = 0
        private const val STEP_ENABLE_DEV_MODE = 1
        private const val STEP_ABOUT = 2
        private const val STEP_DEV = 3
        private const val STEP_WIRELESS = 4
        private const val STEP_PAIR = 5
        private const val STEP_CONNECT = 6
        private const val STEP_LAUNCH = 7
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val ADB_WIFI_KEY = "adb_wifi_enabled"
        private const val DEVELOPMENT_SETTINGS_KEY = "development_settings_enabled"
        private const val ADB_ENABLED_KEY = "adb_enabled"
        private const val MBF_APP_URL = "https://dantheman827.github.io/ModsBeforeFriday/"
        private const val FIX_FORM_URL = "https://wiki.sm0ke.org/fix"
        private const val FAQ_PAGE_URL = "https://wiki.sm0ke.org/#/faq"
        private const val POLL_INTERVAL_MS = 3_000L
    }
}

private data class GuideStep(
    val titleRes: Int,
    val bodyRes: Int,
    val captionRes: Int,
    val primaryLabelRes: Int?,
    val panel: GuidePanel,
    val primaryAction: (() -> Unit)?
)

private enum class GuidePanel {
    PRECHECK,
    NONE,
    PAIR,
    CONNECT
}
