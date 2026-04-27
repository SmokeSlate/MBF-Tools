package org.sm0ke.mbftools

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
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

    @Volatile private var connectedDeviceName: String? = null
    @Volatile private var autoReconnectAttempted = false

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

        launchMbfButton.setOnClickListener { launchIntegratedMbf() }
        openFixButton.setOnClickListener { openUrl(FIX_FORM_URL) }
        openFaqButton.setOnClickListener { openUrl(FAQ_PAGE_URL) }
        openGuideButton.setOnClickListener {
            AppNavigation.openScreen(this, GuideActivity::class.java)
        }
        openAdvancedButton.setOnClickListener {
            AppNavigation.openScreen(this, MainActivity::class.java)
        }
        shareDebugLogsButton.setOnClickListener {
            DebugShareHelper.share(
                    activity = this,
                    sourceTag = "Home",
                    onBusyChanged = { busy -> setActionButtonsEnabled(!busy) }
            )
        }

        loadFaq()
    }

    override fun onResume() {
        super.onResume()
        if (redirectToWirelessRequiredIfNeeded()) {
            return
        }
        autoReconnectAttempted = false
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
        worker.execute {
            val setupComplete = AppPrefs.isSetupComplete(this)
            var device =
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
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
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
        AppNavigation.openScreen(this, WirelessDebugRequiredActivity::class.java)
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
            val browserUrl = runCatching {
                val baseUrl = BridgeManager.startOrGetBrowserUrl(this, MBF_APP_URL)
                buildBrowserUrl(baseUrl)
            }

            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
                browserUrl
                        .onSuccess { url ->
                            AppLog.info("Home", "MBF browser launched successfully.")
                            AppNavigation.openBrowser(this, url)
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
        AppNavigation.openBrowser(this, url)
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

    private fun setActionButtonsEnabled(enabled: Boolean) {
        launchMbfButton.isEnabled = enabled && connectedDeviceName != null
        openFixButton.isEnabled = enabled
        openFaqButton.isEnabled = enabled
        openGuideButton.isEnabled = enabled
        openAdvancedButton.isEnabled = enabled
        shareDebugLogsButton.isEnabled = enabled
    }

    companion object {
        private const val MBF_APP_URL = "https://dantheman827.github.io/ModsBeforeFriday/"
        private const val FIX_FORM_URL = "https://wiki.sm0ke.org/fix"
        private const val FAQ_PAGE_URL = "https://wiki.sm0ke.org/#/faq"
        private const val POLL_INTERVAL_MS = 3_000L
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
