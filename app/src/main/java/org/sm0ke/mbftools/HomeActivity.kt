package org.sm0ke.mbftools

import android.content.Intent
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

    @Volatile private var connectedDeviceName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        launchMbfButton.setOnClickListener { launchIntegratedMbf() }
        openFixButton.setOnClickListener { openUrl(FIX_FORM_URL) }
        openFaqButton.setOnClickListener { openUrl(FAQ_PAGE_URL) }
        openGuideButton.setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        openAdvancedButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        loadFaq()
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
        worker.execute {
            val device =
                    runCatching { AdbManager.getAuthorizedDevices(this).firstOrNull() }.getOrNull()
            connectedDeviceName = device?.name
            val devMode = SetupState.isDeveloperModeEnabled(this)
            val wireless = SetupState.isWirelessDebuggingEnabled(this)

            runOnUiThread {
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
            }
        }
    }

    private fun loadFaq() {
        faqStatus.text = getString(R.string.home_faq_loading)
        worker.execute {
            val result = runCatching { FaqRepository.fetchFaqItems() }
            runOnUiThread {
                result
                        .onSuccess { items ->
                            faqStatus.text = getString(R.string.home_faq_live)
                            renderFaq(items)
                        }
                        .onFailure {
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
                    setTextColor(resources.getColor(R.color.text_primary, theme))
                    setTypeface(typeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                }

        val answer =
                TextView(this).apply {
                    text = item.answer
                    setTextColor(resources.getColor(R.color.text_secondary, theme))
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
            toast(getString(R.string.toast_connect_first))
            return
        }

        worker.execute {
            runCatching { AdbManager.grantSelfPermissions(this, device) }
            val browserUrl = runCatching {
                val baseUrl = BridgeManager.startOrGetBrowserUrl(this, MBF_APP_URL)
                buildBrowserUrl(baseUrl)
            }

            runOnUiThread {
                browserUrl
                        .onSuccess { url ->
                            startActivity(
                                    Intent(this, BrowserActivity::class.java)
                                            .putExtra(BrowserActivity.EXTRA_URL, url)
                            )
                        }
                        .onFailure { toast(it.message ?: getString(R.string.toast_launch_failed)) }
            }
        }
    }

    private fun openUrl(url: String) {
        startActivity(
                Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url)
        )
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

    companion object {
        private const val MBF_APP_URL = "https://dantheman827.github.io/ModsBeforeFriday/"
        private const val FIX_FORM_URL = "https://wiki.sm0ke.org/fix"
        private const val FAQ_PAGE_URL = "https://wiki.sm0ke.org/#/faq"
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
