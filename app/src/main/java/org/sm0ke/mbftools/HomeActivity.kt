package org.sm0ke.mbftools

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HomeActivity : ComponentActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollingRunnable =
            object : Runnable {
                override fun run() {
                    refreshState()
                    mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }

    private lateinit var homeStatus: TextView
    private lateinit var homeDetail: TextView
    private lateinit var faqStatus: TextView
    private lateinit var faqContainer: LinearLayout
    private lateinit var launchMbfButton: Button
    private lateinit var openFixButton: Button
    private lateinit var openFaqButton: Button
    private lateinit var openGuideButton: Button
    private lateinit var openAdvancedButton: Button
    private lateinit var shareDebugLogsButton: Button
    private lateinit var diagnoseButton: Button
    private lateinit var updateStatus: TextView
    private lateinit var checkUpdatesButton: Button
    private lateinit var supportChatButton: Button
    private lateinit var discordButton: Button

    @Volatile private var connectedDeviceName: String? = null
    @Volatile private var autoReconnectAttempted = false
    @Volatile private var updateCheckInProgress = false
    private var pendingUpdateApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        AppPrefs.clearCurrentGuideStep(this)
        AppLog.info("Home", "Home screen opened.")
        setContentView(R.layout.activity_home)

        homeStatus = findViewById(R.id.txtHomeStatus)
        homeDetail = findViewById(R.id.txtHomeDetail)
        faqStatus = findViewById(R.id.txtFaqStatus)
        faqContainer = findViewById(R.id.panelFaqEntries)
        launchMbfButton = findViewById(R.id.btnHomeLaunchMbf)
        openFixButton = findViewById(R.id.btnHomeFixForm)
        openFaqButton = findViewById(R.id.btnHomeFaq)
        openGuideButton = findViewById(R.id.btnHomeGuide)
        openAdvancedButton = findViewById(R.id.btnHomeAdvanced)
        shareDebugLogsButton = findViewById(R.id.btnHomeShareDebugLogs)
        diagnoseButton = findViewById(R.id.btnHomeDiagnose)
        updateStatus = findViewById(R.id.txtUpdateStatus)
        checkUpdatesButton = findViewById(R.id.btnHomeCheckUpdates)
        supportChatButton = findViewById(R.id.btnHomeSupportChat)
        discordButton = findViewById(R.id.btnHomeDiscord)

        launchMbfButton.setOnClickListener { launchIntegratedMbf() }
        openFixButton.setOnClickListener { openUrl(FIX_FORM_URL) }
        openFaqButton.setOnClickListener { openUrl(FAQ_PAGE_URL) }
        openGuideButton.setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        openAdvancedButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        shareDebugLogsButton.setOnClickListener {
            DebugShareHelper.share(
                    activity = this,
                    sourceTag = "Home",
                    onBusyChanged = { busy -> setActionButtonsEnabled(!busy) }
            )
        }
        diagnoseButton.setOnClickListener {
            startActivity(Intent(this, DiagnoseActivity::class.java))
        }
        checkUpdatesButton.setOnClickListener { checkForUpdates(showNoUpdateToast = true) }
        supportChatButton.setOnClickListener { openUrl(getString(R.string.support_chat_url)) }
        discordButton.setOnClickListener { openUrl(getString(R.string.support_discord_url)) }

        loadFaq()
        checkForUpdates(showNoUpdateToast = false)
    }

    override fun onResume() {
        super.onResume()
        if (redirectToWirelessRequiredIfNeeded()) {
            return
        }
        autoReconnectAttempted = false
        refreshState()
        startPolling()
        resumePendingUpdateInstallIfAllowed()
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
        worker.execute {
            val setupComplete = AppPrefs.isSetupComplete(this)
            val device =
                    runCatching { AdbManager.getAuthorizedDevices(this).firstOrNull() }.getOrNull()
            connectedDeviceName = device?.name
            val devMode = SetupState.isDeveloperModeEnabled(this)
            val wireless = SetupState.isWirelessDebuggingEnabled(this)

            if (device == null && setupComplete && !autoReconnectAttempted) {
                autoReconnectAttempted = true
                AppLog.info(
                        "Home",
                        "No authorized headset found on open. Re-detecting wireless debug port."
                )
                val reconnectResult = autoReconnectAfterSetup()
                if (reconnectResult.connectedDeviceName != null) {
                    connectedDeviceName = reconnectResult.connectedDeviceName
                    AppLog.info(
                            "Home",
                            "Post-setup reconnect succeeded on port ${reconnectResult.debugPort} for ${reconnectResult.connectedDeviceName}."
                    )
                } else if (reconnectResult.debugPort > 0) {
                    AppLog.warn(
                            "Home",
                            "Detected wireless debug port ${reconnectResult.debugPort}, but reconnect did not authorize a device."
                    )
                } else {
                    AppLog.warn(
                            "Home",
                            "Post-setup reconnect could not detect a usable wireless debug port."
                    )
                }
            }

            runOnUiThread {
                if (redirectToWirelessRequiredIfNeeded()) {
                    return@runOnUiThread
                }
                val connected = connectedDeviceName != null
                homeStatus.text =
                        if (connected) {
                            getString(R.string.home_status_ready)
                        } else {
                            getString(R.string.home_status_setup_complete)
                        }
                homeDetail.text =
                        if (connected) {
                            getString(R.string.home_detail_connected, connectedDeviceName ?: "")
                        } else {
                            getString(
                                    R.string.home_detail_not_connected,
                                    if (devMode) getString(R.string.status_enabled)
                                    else getString(R.string.status_not_detected),
                                    if (wireless) getString(R.string.status_enabled)
                                    else getString(R.string.status_not_detected)
                            )
                        }
                launchMbfButton.isEnabled = connected
                shareDebugLogsButton.isEnabled = true
            }
        }
    }

    private fun redirectToWirelessRequiredIfNeeded(): Boolean {
        if (!AppPrefs.isSetupComplete(this)) {
            return false
        }
        if (SetupState.isWirelessDebuggingEnabled(this)) {
            return false
        }
        AppLog.warn("Home", "Wireless Debugging is disabled after setup. Redirecting to blocker screen.")
        startActivity(Intent(this, WirelessDebugRequiredActivity::class.java))
        finish()
        return true
    }

    private fun autoReconnectAfterSetup(): HomeReconnectResult {
        val savedDebugPort = AppPrefs.getDebugPort(this).trim().toIntOrNull()
        val initialPort = savedDebugPort ?: runCatching { AdbManager.detectDebugPort(this) }.getOrDefault(0)
        if (initialPort <= 0) {
            return HomeReconnectResult()
        }

        val connectAttempt = connectWithRecoveredDebugPort(initialPort)
        if (connectAttempt.finalPort > 0) {
            AppPrefs.setDebugPort(this, connectAttempt.finalPort.toString())
        }

        val command = connectAttempt.result.getOrNull()
        if (command != null && isSuccessfulConnect(command)) {
            val deviceName = runCatching { AdbManager.getAuthorizedDevices(this).firstOrNull()?.name }.getOrNull()
            if (deviceName != null) {
                runCatching { AdbManager.grantSelfPermissions(this, deviceName) }
                return HomeReconnectResult(
                        debugPort = connectAttempt.finalPort,
                        connectedDeviceName = deviceName
                )
            }
        }

        return HomeReconnectResult(debugPort = connectAttempt.finalPort)
    }

    private fun connectWithRecoveredDebugPort(initialPort: Int): HomeReconnectAttempt {
        AppPrefs.setDebugPort(this, initialPort.toString())
        val initialResult = runCatching { AdbManager.connect(this, initialPort) }
        if (initialResult.isSuccess && isSuccessfulConnect(initialResult.getOrThrow())) {
            return HomeReconnectAttempt(finalPort = initialPort, result = initialResult)
        }

        val redetectedPort = runCatching { AdbManager.detectDebugPort(this) }.getOrDefault(0)
        if (redetectedPort <= 0) {
            return HomeReconnectAttempt(finalPort = initialPort, result = initialResult)
        }

        AppPrefs.setDebugPort(this, redetectedPort.toString())
        val retryResult = runCatching { AdbManager.connect(this, redetectedPort) }
        return HomeReconnectAttempt(
                finalPort = redetectedPort,
                result = retryResult,
                redetectedPort = redetectedPort
        )
    }

    private fun isSuccessfulConnect(command: AdbCommandResult): Boolean {
        return command.exitCode == 0 && command.stdout.contains("connected", ignoreCase = true)
    }

    private fun loadFaq() {
        faqStatus.text = getString(R.string.home_faq_loading)
        worker.execute {
            val result = runCatching { FaqRepository.fetchFaqItems() }
            runOnUiThread {
                result
                        .onSuccess { items ->
                            AppLog.info("Home", "Loaded ${items.size} FAQ entries from the wiki.")
                            faqStatus.text = getString(R.string.home_faq_live)
                            renderFaq(items)
                        }
                        .onFailure {
                            AppLog.warn(
                                    "Home",
                                    "Failed to load live FAQ: ${it.message ?: "unknown error"}"
                            )
                            faqStatus.text = getString(R.string.home_faq_failed)
                            faqContainer.removeAllViews()
                        }
            }
        }
    }

    private fun renderFaq(items: List<FaqItem>) {
        faqContainer.removeAllViews()
        items.forEach { item -> faqContainer.addView(createFaqEntryView(item)) }
    }

    private fun checkForUpdates(showNoUpdateToast: Boolean) {
        if (updateCheckInProgress) {
            return
        }

        updateCheckInProgress = true
        updateStatus.text = getString(R.string.update_status_checking)
        checkUpdatesButton.isEnabled = false
        AppLog.info("Updater", "Checking GitHub releases for updates.")

        worker.execute {
            val result = runCatching { AppUpdater.checkForUpdate(this) }
            runOnUiThread {
                updateCheckInProgress = false
                checkUpdatesButton.isEnabled = true
                result
                        .onSuccess { check ->
                            if (check.updateAvailable) {
                                AppLog.info(
                                        "Updater",
                                        "Update available: ${check.release.versionLabel}."
                                )
                                updateStatus.text =
                                        getString(
                                                R.string.update_status_available,
                                                check.release.versionLabel
                                        )
                                showUpdateAvailableDialog(check)
                            } else {
                                val status =
                                        if (check.comparison == null) {
                                            getString(
                                                    R.string.update_status_unknown,
                                                    check.release.versionLabel
                                            )
                                        } else {
                                            getString(
                                                    R.string.update_status_current,
                                                    check.currentVersionName
                                            )
                                        }
                                updateStatus.text = status
                                if (showNoUpdateToast) {
                                    toast(status)
                                }
                            }
                        }
                        .onFailure {
                            val message = it.message ?: getString(R.string.update_status_failed)
                            AppLog.warn("Updater", "Update check failed: $message")
                            updateStatus.text = getString(R.string.update_status_failed)
                            if (showNoUpdateToast) {
                                toast(message)
                            }
                        }
            }
        }
    }

    private fun showUpdateAvailableDialog(check: AppUpdateCheck) {
        val release = check.release
        val notes =
                release.body
                        .trim()
                        .take(MAX_RELEASE_NOTES_CHARS)
                        .ifBlank { getString(R.string.update_no_release_notes) }
        AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_available_title, release.versionLabel))
                .setMessage(
                        getString(
                                R.string.update_available_body,
                                check.currentVersionName,
                                release.versionLabel,
                                formatSize(release.asset.sizeBytes),
                                notes
                        )
                )
                .setNegativeButton(R.string.update_later, null)
                .setNeutralButton(R.string.update_release_page) { _, _ ->
                    if (release.htmlUrl.isNotBlank()) {
                        openUrl(release.htmlUrl)
                    }
                }
                .setPositiveButton(R.string.update_download_install) { _, _ ->
                    downloadAndInstallUpdate(release)
                }
                .show()
    }

    private fun downloadAndInstallUpdate(release: GitHubRelease) {
        setActionButtonsEnabled(false)
        updateStatus.text = getString(R.string.update_status_downloading, release.versionLabel)
        AppLog.info("Updater", "Downloading update APK ${release.asset.name}.")

        worker.execute {
            val result = runCatching { AppUpdater.downloadApk(this, release) }
            runOnUiThread {
                setActionButtonsEnabled(true)
                result
                        .onSuccess { apk ->
                            AppLog.info("Updater", "Downloaded update APK to ${apk.absolutePath}.")
                            updateStatus.text = getString(R.string.update_status_downloaded)
                            launchDownloadedUpdate(apk)
                        }
                        .onFailure {
                            val message = it.message ?: getString(R.string.update_download_failed)
                            AppLog.error("Updater", "Update download failed: $message")
                            updateStatus.text = getString(R.string.update_download_failed)
                            toast(message)
                        }
            }
        }
    }

    private fun launchDownloadedUpdate(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        !packageManager.canRequestPackageInstalls()
        ) {
            pendingUpdateApk = apk
            showInstallPermissionDialog()
            return
        }

        runCatching {
                    updateStatus.text = getString(R.string.update_status_installing)
                    startActivity(AppUpdater.createInstallIntent(this, apk))
                }
                .onSuccess {
                    AppLog.info("Updater", "Opened Android package installer for update.")
                }
                .onFailure {
                    val message = it.message ?: getString(R.string.update_install_failed)
                    AppLog.error("Updater", "Could not open update installer: $message")
                    updateStatus.text = getString(R.string.update_install_failed)
                    toast(message)
                }
    }

    private fun showInstallPermissionDialog() {
        AlertDialog.Builder(this)
                .setTitle(R.string.update_install_permission_title)
                .setMessage(R.string.update_install_permission_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.update_action_open_install_settings) { _, _ ->
                    openInstallPermissionSettings()
                }
                .show()
    }

    private fun openInstallPermissionSettings() {
        val fallbackIntent =
                Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val settingsIntent =
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(Uri.parse("package:$packageName"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (settingsIntent.tryLaunch()) return
        }
        fallbackIntent.tryLaunch()
    }

    private fun resumePendingUpdateInstallIfAllowed() {
        val apk = pendingUpdateApk ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                        packageManager.canRequestPackageInstalls()
        ) {
            pendingUpdateApk = null
            launchDownloadedUpdate(apk)
        }
    }

    private fun createFaqEntryView(item: FaqItem): LinearLayout {
        val container =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_panel_secondary)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { bottomMargin = dp(10) }
                }

        val question =
                TextView(this).apply {
                    text = item.question
                    setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.text_primary))
                    setTypeface(typeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                }

        val answer =
                TextView(this).apply {
                    text = item.answer
                    setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.text_secondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setLineSpacing(0f, 1.15f)
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { topMargin = dp(8) }
                }

        container.addView(question)
        container.addView(answer)
        return container
    }

    private fun launchIntegratedMbf() {
        val device = connectedDeviceName
        if (device == null) {
            AppLog.warn("Home", "Launch MBF blocked because no headset is connected.")
            toast(getString(R.string.toast_connect_first))
            return
        }

        AppLog.info("Home", "Launching MBF from the home screen.")
        worker.execute {
            runCatching { AdbManager.grantSelfPermissions(this, device) }
            val beatSaberVersionTag =
                    BeatSaberVersionResolver.resolveVersionTag(
                            context = this,
                            packageName = AppPrefs.getGameId(this),
                            deviceName = device
                    )
            AppLog.info(
                    "Home",
                    "Resolved Beat Saber version tag for MBF launch: ${beatSaberVersionTag ?: "<none>"}"
            )
            val browserUrl = runCatching {
                val baseUrl = BridgeManager.startOrGetBrowserUrl(this, MBF_APP_URL)
                buildBrowserUrl(baseUrl)
            }

            runOnUiThread {
                browserUrl
                        .onSuccess { url ->
                            AppLog.info("Home", "MBF browser launched successfully.")
                            startActivity(
                                    Intent(this, BrowserActivity::class.java)
                                            .putExtra(
                                                    BrowserActivity.EXTRA_ENABLE_RECOMMENDED_MOD_PACK_PROMPT,
                                                    true
                                            )
                                            .putExtra(
                                                    BrowserActivity.EXTRA_BEAT_SABER_VERSION_TAG,
                                                    beatSaberVersionTag
                                            )
                                            .putExtra(BrowserActivity.EXTRA_URL, url)
                            )
                        }
                        .onFailure {
                            AppLog.error(
                                    "Home",
                                    "Failed to launch MBF: ${it.message ?: getString(R.string.toast_launch_failed)}"
                            )
                            toast(it.message ?: getString(R.string.toast_launch_failed))
                        }
            }
        }
    }

    private fun openUrl(url: String) {
        AppLog.info("Home", "Opening support page: $url")
        startActivity(
                Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url)
        )
    }

    private fun Intent.tryLaunch(): Boolean {
        return try {
            startActivity(this)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildBrowserUrl(baseUrl: String): String {
        val gameId = AppPrefs.getGameId(this)
        return if (gameId.isBlank()) {
            baseUrl
        } else {
            val separator = if (baseUrl.contains("?")) "&" else "?"
            baseUrl + separator + "game_id=" + Uri.encode(gameId)
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        value.toFloat(),
                        resources.displayMetrics
                )
                .toInt()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatSize(sizeBytes: Long): String {
        if (sizeBytes <= 0L) {
            return getString(R.string.update_size_unknown)
        }
        val sizeMb = sizeBytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", sizeMb)
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        launchMbfButton.isEnabled = enabled && connectedDeviceName != null
        openFixButton.isEnabled = enabled
        openFaqButton.isEnabled = enabled
        openGuideButton.isEnabled = enabled
        openAdvancedButton.isEnabled = enabled
        shareDebugLogsButton.isEnabled = enabled
        diagnoseButton.isEnabled = enabled
        checkUpdatesButton.isEnabled = enabled && !updateCheckInProgress
        supportChatButton.isEnabled = enabled
        discordButton.isEnabled = enabled
    }

    companion object {
        private const val MBF_APP_URL = "https://dantheman827.github.io/ModsBeforeFriday/"
        private const val FIX_FORM_URL = "https://wiki.sm0ke.org/fix"
        private const val FAQ_PAGE_URL = "https://wiki.sm0ke.org/#/faq"
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_RELEASE_NOTES_CHARS = 900
    }
}

private data class HomeReconnectAttempt(
        val finalPort: Int,
        val result: Result<AdbCommandResult>,
        val redetectedPort: Int? = null
)

private data class HomeReconnectResult(
        val debugPort: Int = 0,
        val connectedDeviceName: String? = null
)
