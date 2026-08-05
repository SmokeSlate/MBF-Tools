package org.sm0ke.mbftools

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiagnoseActivity : ComponentActivity() {

    private lateinit var btnDiagnoseBack: Button
    private lateinit var btnDiagPatchDiscord: Button
    private lateinit var txtStatusHeading: TextView
    private lateinit var panelStatusItems: LinearLayout
    private lateinit var btnRefreshStatus: Button
    private lateinit var btnDeepScanAndDiagnose: Button
    private lateinit var txtDeepScanStatus: TextView
    private lateinit var btnDiagOpenDevSettings: Button
    private lateinit var btnDiagOpenAppInfo: Button
    private lateinit var btnDiagReconnect: Button
    private lateinit var btnDiagOpenGuide: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        AppLog.info(TAG, "Diagnose screen opened.")
        setContentView(R.layout.activity_diagnose)

        btnDiagnoseBack = findViewById(R.id.btnDiagnoseBack)
        btnDiagPatchDiscord = findViewById(R.id.btnDiagPatchDiscord)
        txtStatusHeading = findViewById(R.id.txtStatusHeading)
        panelStatusItems = findViewById(R.id.panelStatusItems)
        btnRefreshStatus = findViewById(R.id.btnRefreshStatus)
        btnDeepScanAndDiagnose = findViewById(R.id.btnDeepScanAndDiagnose)
        txtDeepScanStatus = findViewById(R.id.txtDeepScanStatus)
        btnDiagOpenDevSettings = findViewById(R.id.btnDiagOpenDevSettings)
        btnDiagOpenAppInfo = findViewById(R.id.btnDiagOpenAppInfo)
        btnDiagReconnect = findViewById(R.id.btnDiagReconnect)
        btnDiagOpenGuide = findViewById(R.id.btnDiagOpenGuide)

        btnDiagnoseBack.setOnClickListener { finish() }
        btnDiagPatchDiscord.setOnClickListener { openInBrowser(DISCORD_URL) }
        btnRefreshStatus.setOnClickListener { runQuickCheck() }
        btnDeepScanAndDiagnose.setOnClickListener { runDeepScanAndDiagnose() }
        btnDiagOpenDevSettings.setOnClickListener { openDeveloperSettings() }
        btnDiagOpenAppInfo.setOnClickListener { openSettingsAppInfo() }
        btnDiagReconnect.setOnClickListener { reconnectHeadset() }
        btnDiagOpenGuide.setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }

        runQuickCheck()
    }

    // ── Quick status check ────────────────────────────────────────────────────

    private fun runQuickCheck() {
        txtStatusHeading.text = getString(R.string.diag_status_checking)
        panelStatusItems.removeAllViews()
        btnRefreshStatus.isEnabled = false

        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { collectQuickStatus() }
            if (!isFinishing && !isDestroyed) {
                txtStatusHeading.text = getString(R.string.diag_status_title)
                renderStatusItems(items)
                btnRefreshStatus.isEnabled = true
            }
        }
    }

    private fun collectQuickStatus(): List<StatusItem> {
        val devMode = SetupState.isDeveloperModeEnabled(this)
        val wireless = SetupState.isWirelessDebuggingEnabled(this)
        val devices = runCatching { AdbManager.getDevices(this) }.getOrDefault(emptyList())
        val connectedDevice = devices.firstOrNull { it.authorized }
        val gameId = AppPrefs.getGameId(this).ifBlank { "com.beatgames.beatsaber" }
        val bsInstalled = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(gameId, 0) != null
        }.getOrDefault(false)
        val setupComplete = AppPrefs.isSetupComplete(this)

        return listOf(
            StatusItem(
                label = if (setupComplete) getString(R.string.diag_check_setup_done)
                        else getString(R.string.diag_check_setup_incomplete),
                ok = setupComplete,
            ),
            StatusItem(
                label = if (devMode) getString(R.string.diag_check_devmode_on)
                        else getString(R.string.diag_check_devmode_off),
                ok = devMode,
                hint = if (!devMode) getString(R.string.diag_hint_devmode) else null,
            ),
            StatusItem(
                label = if (wireless) getString(R.string.diag_check_wireless_on)
                        else getString(R.string.diag_check_wireless_off),
                ok = wireless,
                hint = if (!wireless) getString(R.string.diag_hint_wireless) else null,
            ),
            StatusItem(
                label = if (connectedDevice != null)
                            getString(R.string.diag_check_adb_connected, connectedDevice.name)
                        else getString(R.string.diag_check_adb_disconnected),
                ok = connectedDevice != null,
                hint = if (connectedDevice == null) getString(R.string.diag_hint_adb) else null,
            ),
            StatusItem(
                label = if (bsInstalled) getString(R.string.diag_check_bs_found, gameId)
                        else getString(R.string.diag_check_bs_missing, gameId),
                ok = bsInstalled,
            ),
        )
    }

    private fun renderStatusItems(items: List<StatusItem>) {
        panelStatusItems.removeAllViews()
        items.forEach { item -> panelStatusItems.addView(createStatusRow(item)) }
    }

    private fun createStatusRow(item: StatusItem): LinearLayout {
        val rowParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = rowParams
        }

        val innerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val chip = TextView(this).apply {
            text = if (item.ok) "✓" else "✗"
            setBackgroundResource(if (item.ok) R.drawable.bg_chip else R.drawable.bg_chip_warning)
            setTextColor(
                ContextCompat.getColor(
                    this@DiagnoseActivity,
                    if (item.ok) R.color.success_chip_text else R.color.warning_chip_text,
                )
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            minWidth = dp(38)
        }

        val labelParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { marginStart = dp(10) }

        val label = TextView(this).apply {
            text = item.label
            setTextColor(
                ContextCompat.getColor(
                    this@DiagnoseActivity,
                    if (item.ok) R.color.text_secondary else R.color.text_primary,
                )
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = labelParams
        }

        innerRow.addView(chip)
        innerRow.addView(label)
        row.addView(innerRow)

        if (!item.hint.isNullOrBlank()) {
            val hintParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                marginStart = dp(48)
            }
            val hint = TextView(this).apply {
                text = item.hint
                setTextColor(ContextCompat.getColor(this@DiagnoseActivity, R.color.text_muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = hintParams
            }
            row.addView(hint)
        }

        return row
    }

    // ── Deep scan + AI diagnose ───────────────────────────────────────────────

    private fun runDeepScanAndDiagnose() {
        setDeepScanBusy(true)
        AppLog.info(TAG, "Starting deep scan for AI diagnosis.")

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.diag_deep_scan_loading_title)
            .setMessage(R.string.diag_deep_scan_loading_body)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val payload = DiagnosticsCollector.collect(this@DiagnoseActivity)
                    DiagnosticsShareClient.upload(DebugShareHelper.currentBackendUrl(), payload)
                }
            }

            if (isFinishing || isDestroyed) return@launch

            dialog.dismiss()
            setDeepScanBusy(false)

            result
                .onSuccess { receipt ->
                    AppLog.info(TAG, "Deep scan upload succeeded, code=${receipt.code}.")
                    val diagnoseUrl = "https://wiki.sm0ke.org/fix?log=${Uri.encode(receipt.code)}"
                    txtDeepScanStatus.visibility = android.view.View.VISIBLE
                    txtDeepScanStatus.text = getString(R.string.diag_deep_scan_success, receipt.code)
                    openInBrowser(diagnoseUrl)
                }
                .onFailure { err ->
                    val msg = err.message ?: getString(R.string.toast_log_share_failed)
                    AppLog.error(TAG, "Deep scan failed: $msg")
                    txtDeepScanStatus.visibility = android.view.View.VISIBLE
                    txtDeepScanStatus.text = getString(R.string.diag_deep_scan_failed, msg)
                    toast(msg)
                }
        }
    }

    private fun setDeepScanBusy(busy: Boolean) {
        btnDeepScanAndDiagnose.isEnabled = !busy
        btnRefreshStatus.isEnabled = !busy
        if (busy) {
            txtDeepScanStatus.visibility = android.view.View.GONE
        }
    }

    // ── Reconnect ─────────────────────────────────────────────────────────────

    private fun reconnectHeadset() {
        btnDiagReconnect.isEnabled = false
        AppLog.info(TAG, "Manual reconnect initiated from diagnose screen.")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val savedPort = AppPrefs.getDebugPort(this@DiagnoseActivity).trim().toIntOrNull()
                    val port = savedPort
                        ?: runCatching { AdbManager.detectDebugPort(this@DiagnoseActivity) }.getOrDefault(0)
                    if (port > 0) {
                        AdbManager.connect(this@DiagnoseActivity, port)
                    } else {
                        null
                    }
                }
            }

            if (isFinishing || isDestroyed) return@launch
            btnDiagReconnect.isEnabled = true

            val cmd = result.getOrNull()
            val succeeded = cmd != null
                && cmd.exitCode == 0
                && cmd.stdout.contains("connected", ignoreCase = true)

            if (succeeded) {
                AppLog.info(TAG, "Manual reconnect succeeded.")
                toast(getString(R.string.toast_connect_success))
                runQuickCheck()
            } else {
                val reason = result.exceptionOrNull()?.message
                    ?: cmd?.stdout?.trim()
                    ?: getString(R.string.toast_connect_failed)
                AppLog.warn(TAG, "Manual reconnect failed: $reason")
                toast(getString(R.string.toast_connect_failed))
            }
        }
    }

    // ── Settings intents ──────────────────────────────────────────────────────

    private fun openDeveloperSettings() {
        val launched = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent().setComponent(
                ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity",
                ),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent().setComponent(
                ComponentName(
                    "com.android.settings",
                    "com.android.settings.DevelopmentSettings",
                ),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ).any { it.tryLaunch() }

        val target = getString(R.string.target_developer_settings)
        if (launched) toast(getString(R.string.toast_opened, target))
        else toast(getString(R.string.toast_could_not_open, target))
    }

    private fun openSettingsAppInfo() {
        val launched = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ).any { it.tryLaunch() }

        val target = getString(R.string.target_settings_app_info)
        if (launched) toast(getString(R.string.toast_opened, target))
        else toast(getString(R.string.toast_could_not_open, target))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun openInBrowser(url: String) {
        startActivity(
            Intent(this, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_URL, url),
        )
    }

    private fun Intent.tryLaunch(): Boolean =
        try { startActivity(this); true } catch (_: Exception) { false }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
        ).toInt()

    companion object {
        private const val TAG = "Diagnose"
        private const val DISCORD_URL = "https://d.sm0ke.org"
    }
}

private data class StatusItem(
    val label: String,
    val ok: Boolean,
    val hint: String? = null,
)
