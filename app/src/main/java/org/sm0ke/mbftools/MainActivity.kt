package org.sm0ke.mbftools

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollingRunnable =
            object : Runnable {
                override fun run() {
                    refreshState()
                    mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }

    private lateinit var recommendationText: TextView
    private lateinit var mbfStatusValue: TextView
    private lateinit var developerStatusValue: TextView
    private lateinit var settingsStatusValue: TextView
    private lateinit var connectionStatusValue: TextView
    private lateinit var pairingCodeInput: EditText
    private lateinit var pairingPortInput: EditText
    private lateinit var debugPortInput: EditText
    private lateinit var gameIdInput: EditText
    private lateinit var launchMbfButton: Button
    private lateinit var deviceSettingsMenuButton: Button
    private lateinit var advancedLogView: TextView
    private lateinit var advancedLogScroll: ScrollView
    private lateinit var shareDebugLogsButton: Button

    @Volatile private var connectedDeviceName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        setContentView(R.layout.activity_main)

        recommendationText = findViewById(R.id.txtRecommendation)
        mbfStatusValue = findViewById(R.id.valueMbfStatus)
        developerStatusValue = findViewById(R.id.valueDeveloperStatus)
        settingsStatusValue = findViewById(R.id.valueSettingsStatus)
        connectionStatusValue = findViewById(R.id.valueConnectionStatus)
        pairingCodeInput = findViewById(R.id.inputPairingCode)
        pairingPortInput = findViewById(R.id.inputPairingPort)
        debugPortInput = findViewById(R.id.inputDebugPort)
        gameIdInput = findViewById(R.id.inputGameId)
        launchMbfButton = findViewById(R.id.btnLaunchIntegratedMbf)
        deviceSettingsMenuButton = findViewById(R.id.btnDeviceSettingsMenu)
        advancedLogView = findViewById(R.id.txtAdvancedLog)
        advancedLogScroll = findViewById(R.id.scrollAdvancedLog)
        shareDebugLogsButton = findViewById(R.id.btnShareDebugLogs)

        pairingPortInput.setText(AppPrefs.getPairingPort(this))
        debugPortInput.setText(AppPrefs.getDebugPort(this))
        gameIdInput.setText(AppPrefs.getGameId(this))

        gameIdInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                AppPrefs.setGameId(this, gameIdInput.text.toString().trim())
            }
        }

        findViewById<Button>(R.id.btnAdvancedBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            appendLog("Opening Settings app info.")
            if (!openSettingsAppInfo()) {
                toast(
                        getString(
                                R.string.toast_could_not_open,
                                getString(R.string.target_settings_app_info)
                        )
                )
            }
        }

        findViewById<Button>(R.id.btnOpenDeveloperSettings).setOnClickListener {
            appendLog("Opening Developer Settings.")
            if (!openDeveloperOptions()) {
                toast(
                        getString(
                                R.string.toast_could_not_open,
                                getString(R.string.target_developer_settings)
                        )
                )
            }
        }
        deviceSettingsMenuButton.setOnClickListener { showDeviceSettingsMenu() }

        findViewById<Button>(R.id.btnRefreshConnection).setOnClickListener {
            appendLog("Refreshing headset connection state.")
            refreshState()
        }

        findViewById<Button>(R.id.btnPairDevice).setOnClickListener { pairDevice() }

        findViewById<Button>(R.id.btnDetectPairingPort).setOnClickListener { detectPairingPort() }

        findViewById<Button>(R.id.btnDetectDebugPort).setOnClickListener { detectDebugPort() }

        findViewById<Button>(R.id.btnConnectDevice).setOnClickListener { connectToDevice() }

        findViewById<Button>(R.id.btnResetSettings).setOnClickListener { showResetSettingsDialog() }

        launchMbfButton.setOnClickListener { launchIntegratedMbf() }
        shareDebugLogsButton.setOnClickListener {
            DebugShareHelper.share(activity = this, sourceTag = "Advanced", onBusyChanged = ::setBusy)
        }

        appendLog("Advanced screen opened.")
        updateLogView()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
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

    private fun startPolling() {
        mainHandler.removeCallbacks(pollingRunnable)
        mainHandler.postDelayed(pollingRunnable, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        mainHandler.removeCallbacks(pollingRunnable)
    }

    private fun refreshState() {
        updateBasicStatus()
        runAsync {
            val device =
                    runCatching { AdbManager.getAuthorizedDevices(this).firstOrNull() }.getOrNull()
            connectedDeviceName = device?.name

            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
                if (device != null) {
                    updateStatusValue(
                            connectionStatusValue,
                            true,
                            getString(R.string.status_connected_device, device.name),
                            getString(R.string.status_not_connected)
                    )
                    recommendationText.text = getString(R.string.recommendation_connected)
                    launchMbfButton.isEnabled = true
                } else {
                    updateStatusValue(
                            connectionStatusValue,
                            false,
                            getString(R.string.status_connected_generic),
                            getString(R.string.status_not_connected)
                    )
                    recommendationText.text = getString(R.string.recommendation_connect_first)
                    launchMbfButton.isEnabled = false
                }
                updateLogView()
            }
        }
    }

    private fun updateBasicStatus() {
        val bridgeReady = AdbManager.hasRequiredSettingsPermission(this)
        val developerModeEnabled = isDeveloperModeEnabled()
        val settingsAvailable = isPackageInstalled(SETTINGS_PACKAGE)

        updateStatusValue(
                mbfStatusValue,
                true,
                getString(R.string.status_integrated),
                getString(R.string.status_integrated)
        )
        updateStatusValue(
                developerStatusValue,
                developerModeEnabled,
                getString(R.string.status_enabled),
                getString(R.string.status_not_detected)
        )
        updateStatusValue(
                settingsStatusValue,
                settingsAvailable,
                getString(R.string.status_available),
                getString(R.string.status_unavailable)
        )

        if (bridgeReady) {
            recommendationText.text = getString(R.string.recommendation_permissions_ready)
        }
    }

    private fun pairDevice() {
        val pairingCode = pairingCodeInput.text.toString().trim()
        if (pairingCode.length != 6) {
            toast(getString(R.string.toast_invalid_pairing))
            return
        }

        val enteredPairingPort = pairingPortInput.text.toString().trim().toIntOrNull()
        appendLog(
                if (enteredPairingPort != null) {
                    "Pairing with localhost:$enteredPairingPort."
                } else {
                    "Pairing port missing. Detecting pairing port before pairing."
                }
        )
        setBusy(true)
        runAsync {
            val pairingPort =
                    enteredPairingPort
                            ?: runCatching { AdbManager.detectPairingPort(this) }.getOrDefault(0)
            val result =
                    if (pairingPort > 0) {
                        AppPrefs.setPairingPort(this, pairingPort.toString())
                        runCatching { AdbManager.pair(this, pairingPort, pairingCode) }
                    } else {
                        Result.failure(
                                IllegalArgumentException(
                                        getString(R.string.toast_pairing_port_missing)
                                )
                        )
                    }

            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
                setBusy(false)
                if (enteredPairingPort == null && pairingPort > 0) {
                    autofillPairingPort(pairingPort)
                    appendLog("Detected pairing port $pairingPort.")
                    toast(getString(R.string.toast_pairing_port_detected, pairingPort))
                }
                result
                        .onSuccess { command ->
                            if (command.exitCode == 0 &&
                                            !command.stdout.startsWith("Failed:", ignoreCase = true)
                            ) {
                                appendLog("Pairing succeeded.")
                                toast(getString(R.string.toast_pair_success))
                                refreshState()
                            } else {
                                appendLog(
                                        "Pairing failed: ${command.bestMessage(getString(R.string.toast_pair_failed))}"
                                )
                                toast(command.bestMessage(getString(R.string.toast_pair_failed)))
                            }
                        }
                        .onFailure {
                            appendLog(
                                    "Pairing failed: ${it.message ?: getString(R.string.toast_pair_failed)}"
                            )
                            toast(it.message ?: getString(R.string.toast_pair_failed))
                        }
                updateLogView()
            }
        }
    }

    private fun detectPairingPort() {
        appendLog("Detecting pairing port.")
        setBusy(true)
        runAsync {
            val port = runCatching { AdbManager.detectPairingPort(this) }.getOrDefault(0)
            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
                setBusy(false)
                if (port > 0) {
                    autofillPairingPort(port)
                    appendLog("Detected pairing port $port.")
                    toast(getString(R.string.toast_pairing_port_detected, port))
                } else {
                    appendLog("Pairing port was not detected.")
                    toast(getString(R.string.toast_pairing_port_missing))
                }
            }
        }
    }

    private fun detectDebugPort() {
        appendLog("Detecting wireless debug port.")
        setBusy(true)
        runAsync {
            val port = runCatching { AdbManager.detectDebugPort(this) }.getOrDefault(0)
            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
                setBusy(false)
                if (port > 0) {
                    autofillDebugPort(port)
                    appendLog("Detected wireless debug port $port.")
                    toast(getString(R.string.toast_debug_port_detected, port))
                } else {
                    appendLog("Wireless debug port was not detected.")
                    toast(getString(R.string.toast_debug_port_missing))
                }
                updateLogView()
            }
        }
    }

    private fun connectToDevice() {
        val debugPort = debugPortInput.text.toString().trim().toIntOrNull()
        if (debugPort == null) {
            toast(getString(R.string.toast_invalid_debug_port))
            return
        }

        appendLog("Connecting to localhost:$debugPort.")
        setBusy(true)
        runAsync {
            val connectAttempt = connectWithRecoveredDebugPort(debugPort)
            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
                setBusy(false)
                if (connectAttempt.redetectedPort != null) {
                    autofillDebugPort(connectAttempt.finalPort, force = true)
                    appendLog(
                            if (connectAttempt.finalPort != debugPort) {
                                "Connection retry used re-detected wireless debug port ${connectAttempt.finalPort}."
                            } else {
                                "Connection retry re-checked wireless debug port ${connectAttempt.finalPort}."
                            }
                    )
                }
                connectAttempt.result
                        .onSuccess { command ->
                            if (isSuccessfulConnect(command)) {
                                connectedDeviceName =
                                        AdbManager.getAuthorizedDevices(this).firstOrNull()?.name
                                runCatching {
                                    AdbManager.grantSelfPermissions(this, connectedDeviceName)
                                }
                                AppPrefs.setSetupComplete(this, true)
                                appendLog(
                                        "Device connected${connectedDeviceName?.let { ": $it" } ?: "."}"
                                )
                                toast(getString(R.string.toast_connect_success))
                                refreshState()
                            } else {
                                appendLog(
                                        "Connection failed: ${command.bestMessage(getString(R.string.toast_connect_failed))}"
                                )
                                toast(command.bestMessage(getString(R.string.toast_connect_failed)))
                            }
                        }
                        .onFailure {
                            appendLog(
                                    "Connection failed: ${it.message ?: getString(R.string.toast_connect_failed)}"
                            )
                            toast(it.message ?: getString(R.string.toast_connect_failed))
                        }
                updateLogView()
            }
        }
    }

    private fun connectWithRecoveredDebugPort(initialPort: Int): AdvancedConnectAttempt {
        AppPrefs.setDebugPort(this, initialPort.toString())
        val initialResult = runCatching { AdbManager.connect(this, initialPort) }
        if (initialResult.isSuccess && isSuccessfulConnect(initialResult.getOrThrow())) {
            return AdvancedConnectAttempt(finalPort = initialPort, result = initialResult)
        }

        val redetectedPort = runCatching { AdbManager.detectDebugPort(this) }.getOrDefault(0)
        if (redetectedPort <= 0) {
            return AdvancedConnectAttempt(finalPort = initialPort, result = initialResult)
        }

        AppPrefs.setDebugPort(this, redetectedPort.toString())
        val retryResult = runCatching { AdbManager.connect(this, redetectedPort) }
        return AdvancedConnectAttempt(
                finalPort = redetectedPort,
                result = retryResult,
                redetectedPort = redetectedPort
        )
    }

    private fun isSuccessfulConnect(command: AdbCommandResult): Boolean {
        return command.exitCode == 0 && command.stdout.contains("connected", ignoreCase = true)
    }

    private fun launchIntegratedMbf() {
        AppPrefs.setGameId(this, gameIdInput.text.toString().trim())

        setBusy(true)
        appendLog("Launching MBF.")
        runAsync {
            val device =
                    connectedDeviceName
                            ?: runCatching {
                                        AdbManager.getAuthorizedDevices(this).firstOrNull()?.name
                                    }
                                    .getOrNull()
            if (device == null) {
                runOnUiThread {
                    if (isDestroyed || isFinishing) {
                        return@runOnUiThread
                    }
                    setBusy(false)
                    refreshState()
                    appendLog("Launch blocked because no headset is connected.")
                    updateLogView()
                    toast(getString(R.string.toast_connect_first))
                }
                return@runAsync
            }

            runCatching { AdbManager.grantSelfPermissions(this, device) }

            val browserUrl = runCatching {
                val baseUrl =
                        BridgeManager.startOrGetBrowserUrl(context = this, appUrl = MBF_APP_URL)
                buildBrowserUrl(baseUrl)
            }

            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
                setBusy(false)
                browserUrl
                        .onSuccess { url ->
                            appendLog("MBF bridge ready. Opening in-app browser.")
                            updateLogView()
                            AppNavigation.openBrowser(this, url)
                        }
                        .onFailure {
                            appendLog(
                                    "Failed to start MBF: ${it.message ?: getString(R.string.toast_launch_failed)}"
                            )
                            updateLogView()
                            toast(it.message ?: getString(R.string.toast_launch_failed))
                        }
            }
        }
    }

    private fun setBusy(isBusy: Boolean) {
        findViewById<Button>(R.id.btnPairDevice).isEnabled = !isBusy
        findViewById<Button>(R.id.btnDetectPairingPort).isEnabled = !isBusy
        findViewById<Button>(R.id.btnDetectDebugPort).isEnabled = !isBusy
        findViewById<Button>(R.id.btnConnectDevice).isEnabled = !isBusy
        findViewById<Button>(R.id.btnRefreshConnection).isEnabled = !isBusy
        findViewById<Button>(R.id.btnResetSettings).isEnabled = !isBusy
        findViewById<Button>(R.id.btnOpenDeveloperSettings).isEnabled = !isBusy
        findViewById<Button>(R.id.btnOpenSettings).isEnabled = !isBusy
        deviceSettingsMenuButton.isEnabled = !isBusy
        shareDebugLogsButton.isEnabled = !isBusy
        launchMbfButton.isEnabled = !isBusy && connectedDeviceName != null
    }

    private fun showDeviceSettingsMenu() {
        val items =
                arrayOf(
                        getString(R.string.device_settings_refresh_rate),
                        getString(R.string.device_settings_brightness),
                        getString(R.string.device_settings_animation_scale),
                        getString(R.string.device_settings_display_page)
                )
        AlertDialog.Builder(this)
                .setTitle(R.string.device_settings_title)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> showRefreshRateMenu()
                        1 -> showBrightnessMenu()
                        2 -> showAnimationScaleMenu()
                        else -> {
                            appendLog("Opening Android Display Settings.")
                            if (!openDisplaySettings()) {
                                toast(
                                        getString(
                                                R.string.toast_could_not_open,
                                                getString(R.string.target_display_settings)
                                        )
                                )
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun showRefreshRateMenu() {
        val presets = DeviceSettingsPresets.refreshRates
        val labels = presets.map { getString(it.labelRes) }.toTypedArray()
        AlertDialog.Builder(this)
                .setTitle(R.string.device_settings_refresh_title)
                .setItems(labels) { _, which ->
                    applyDeviceSetting(labels[which], presets[which].commands)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun showBrightnessMenu() {
        val presets = DeviceSettingsPresets.brightnessLevels
        val labels = presets.map { getString(it.labelRes) }.toTypedArray()
        AlertDialog.Builder(this)
                .setTitle(R.string.device_settings_brightness_title)
                .setItems(labels) { _, which ->
                    applyDeviceSetting(labels[which], presets[which].commands)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun showAnimationScaleMenu() {
        val presets = DeviceSettingsPresets.animationScales
        val labels = presets.map { getString(it.labelRes) }.toTypedArray()
        AlertDialog.Builder(this)
                .setTitle(R.string.device_settings_animation_title)
                .setItems(labels) { _, which ->
                    applyDeviceSetting(labels[which], presets[which].commands)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun applyDeviceSetting(label: String, commands: List<List<String>>) {
        appendLog("Applying device setting: $label.")
        setBusy(true)
        runAsync {
            val device =
                    connectedDeviceName
                            ?: runCatching {
                                        AdbManager.getAuthorizedDevices(this).firstOrNull()?.name
                                    }
                                    .getOrNull()
            val result =
                    if (device.isNullOrBlank()) {
                        Result.failure(
                                IllegalStateException(
                                        getString(R.string.device_settings_requires_connection)
                                )
                        )
                    } else {
                        runCatching {
                            commands.forEach { command ->
                                val output = AdbManager.shellArgs(this, device, command)
                                if (output.exitCode != 0) {
                                    throw IllegalStateException(
                                            output.bestMessage(
                                                    getString(
                                                            R.string.device_settings_failed,
                                                            label
                                                    )
                                            )
                                    )
                                }
                            }
                        }
                    }

            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
                setBusy(false)
                result
                        .onSuccess {
                            appendLog("Applied device setting: $label.")
                            toast(getString(R.string.device_settings_applied, label))
                        }
                        .onFailure {
                            appendLog(
                                    "Failed to apply device setting $label: ${it.message ?: "unknown"}"
                            )
                            toast(
                                    it.message
                                            ?: getString(
                                                    R.string.device_settings_failed,
                                                    label
                                            )
                            )
                        }
                updateLogView()
            }
        }
    }

    private fun updateStatusValue(
            textView: TextView,
            enabled: Boolean,
            enabledText: String,
            disabledText: String
    ) {
        textView.text = if (enabled) enabledText else disabledText
        textView.setTextColor(if (enabled) STATUS_OK_COLOR else STATUS_WARN_COLOR)
    }

    private fun openDeveloperOptions(showToast: Boolean = true): Boolean {
        val launched =
                launchFirstAvailable(
                        listOf(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                                    `package` = SETTINGS_PACKAGE
                                    addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                                    Intent.FLAG_ACTIVITY_TASK_ON_HOME
                                    )
                                },
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                                    addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                                    Intent.FLAG_ACTIVITY_TASK_ON_HOME
                                    )
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
                                },
                                Intent(Intent.ACTION_MAIN).apply {
                                    component =
                                            ComponentName(
                                                    SETTINGS_PACKAGE,
                                                    "com.android.settings.DevelopmentSettings"
                                            )
                                    addCategory(Intent.CATEGORY_DEFAULT)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                                Intent(Intent.ACTION_MAIN).apply {
                                    component =
                                            ComponentName(
                                                    SETTINGS_PACKAGE,
                                                    "com.android.settings.SubSettings"
                                            )
                                    putExtra(
                                            ":settings:show_fragment",
                                            "com.android.settings.DevelopmentSettingsDashboardFragment"
                                    )
                                    addCategory(Intent.CATEGORY_DEFAULT)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                        )
                )

        if (launched) {
            if (showToast) {
                toast(
                        getString(
                                R.string.toast_opened,
                                getString(R.string.target_developer_settings)
                        )
                )
            }
            return true
        }

        return openSettingsAppInfo(showToast = showToast)
    }

    private fun openSettingsAppInfo(showToast: Boolean = true): Boolean {
        val launched =
                launchFirstAvailable(
                        listOfNotNull(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:$SETTINGS_PACKAGE")
                                    `package` = SETTINGS_PACKAGE
                                    addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                                    )
                                },
                                Intent(Intent.ACTION_MAIN).apply {
                                    component =
                                            ComponentName(
                                                    SETTINGS_PACKAGE,
                                                    "com.android.settings.SubSettings"
                                            )
                                    putExtra(
                                            ":settings:show_fragment",
                                            "com.android.settings.applications.InstalledAppDetails"
                                    )
                                    putExtra(":settings:show_fragment_title_resid", 0)
                                    putExtra(
                                            ":settings:show_fragment_args",
                                            Bundle().apply {
                                                putString("package", SETTINGS_PACKAGE)
                                            }
                                    )
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                                Intent(Intent.ACTION_MAIN).apply {
                                    component =
                                            ComponentName(
                                                    SETTINGS_PACKAGE,
                                                    "com.android.settings.applications.InstalledAppDetails"
                                            )
                                    putExtra("package", SETTINGS_PACKAGE)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                                Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).apply {
                                    addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                                    Intent.FLAG_ACTIVITY_TASK_ON_HOME
                                    )
                                },
                                packageManager.getLaunchIntentForPackage(SETTINGS_PACKAGE)?.apply {
                                    addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                                    )
                                }
                        )
                )

        if (launched && showToast) {
            toast(getString(R.string.toast_opened, getString(R.string.target_settings_app_info)))
        }

        return launched
    }

    private fun openDisplaySettings(showToast: Boolean = true): Boolean {
        val launched =
                launchFirstAvailable(
                        listOf(
                                Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                                    `package` = SETTINGS_PACKAGE
                                    addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                                    Intent.FLAG_ACTIVITY_TASK_ON_HOME
                                    )
                                },
                                Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                                    addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                                    Intent.FLAG_ACTIVITY_TASK_ON_HOME
                                    )
                                }
                        )
                )

        if (launched && showToast) {
            toast(getString(R.string.toast_opened, getString(R.string.target_display_settings)))
        }

        return launched
    }

    private fun launchFirstAvailable(intents: List<Intent>): Boolean {
        for (intent in intents) {
            if (intent.tryLaunch()) {
                return true
            }
        }
        return false
    }

    private fun Intent.tryLaunch(): Boolean {
        return try {
            startActivity(this)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(
                        packageName,
                        android.content.pm.PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION") packageManager.getApplicationInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isDeveloperModeEnabled(): Boolean {
        val cr = contentResolver

        fun getIntSecure(name: String): Int =
                try {
                    @Suppress("DEPRECATION") Settings.Secure.getInt(cr, name, 0)
                } catch (_: Exception) {
                    0
                }

        fun getIntGlobal(name: String): Int =
                try {
                    if (Build.VERSION.SDK_INT >= 17) Settings.Global.getInt(cr, name, 0) else 0
                } catch (_: Exception) {
                    0
                }

        val dev1 = getIntGlobal(DEVELOPMENT_SETTINGS_KEY)
        val dev2 = getIntSecure(DEVELOPMENT_SETTINGS_KEY)
        val adb1 = getIntGlobal(ADB_ENABLED_KEY)
        val adb2 = getIntSecure(ADB_ENABLED_KEY)

        return dev1 == 1 || dev2 == 1 || adb1 == 1 || adb2 == 1
    }

    private fun runAsync(block: () -> Unit) {
        worker.execute(block)
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
        appendLog("Auto-filled wireless debug port $port.")
    }

    private fun autofillPairingPort(port: Int) {
        if (port <= 0 || pairingPortInput.text.toString().trim().toIntOrNull() != null) {
            return
        }
        pairingPortInput.setText(port.toString())
        AppPrefs.setPairingPort(this, port.toString())
        appendLog("Auto-filled pairing port $port.")
    }

    private fun showResetSettingsDialog() {
        AlertDialog.Builder(this)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_reset_settings) { _, _ -> resetSettings() }
                .show()
    }

    private fun resetSettings() {
        AppPrefs.reset(this)
        pairingCodeInput.setText("")
        pairingPortInput.setText("")
        debugPortInput.setText("")
        gameIdInput.setText(AppPrefs.getGameId(this))
        appendLog("Saved setup values were reset.")
        updateLogView()
        refreshState()
    }

    private fun appendLog(message: String) {
        AppLog.info("Advanced", message)
        if (::advancedLogView.isInitialized) {
            runOnUiThread { updateLogView() }
        }
    }

    private fun updateLogView() {
        val shouldStickToBottom =
                isNearBottom(advancedLogScroll, LOG_AUTO_SCROLL_THRESHOLD_PX)
        val previousScrollY = advancedLogScroll.scrollY
        val text = AppLog.text().ifBlank { getString(R.string.log_empty) }
        advancedLogView.text = text
        advancedLogScroll.post {
            if (shouldStickToBottom) {
                advancedLogScroll.fullScroll(android.view.View.FOCUS_DOWN)
            } else {
                val child = advancedLogScroll.getChildAt(0)
                val maxScroll = (child?.height ?: 0) - advancedLogScroll.height
                advancedLogScroll.scrollTo(0, previousScrollY.coerceIn(0, maxScroll.coerceAtLeast(0)))
            }
        }
    }

    private fun isNearBottom(scrollView: ScrollView, thresholdPx: Int): Boolean {
        val child = scrollView.getChildAt(0) ?: return true
        val distanceToBottom = child.bottom - (scrollView.scrollY + scrollView.height)
        return distanceToBottom <= thresholdPx
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val DEVELOPMENT_SETTINGS_KEY = "development_settings_enabled"
        private const val ADB_ENABLED_KEY = "adb_enabled"
        private const val MBF_APP_URL = "https://dantheman827.github.io/ModsBeforeFriday/"
        private const val STATUS_OK_COLOR = 0xFF65D17A.toInt()
        private const val STATUS_WARN_COLOR = 0xFFFFB35C.toInt()
        private const val POLL_INTERVAL_MS = 3_000L
        private const val LOG_AUTO_SCROLL_THRESHOLD_PX = 96
    }
}

private data class AdvancedConnectAttempt(
        val finalPort: Int,
        val result: Result<AdbCommandResult>,
        val redetectedPort: Int? = null
)
