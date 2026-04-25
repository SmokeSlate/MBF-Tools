package org.sm0ke.mbftools

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DebugShareHelper {
    private const val DEFAULT_BACKEND_URL = "https://logs.sm0ke.org"

    fun currentBackendUrl(): String {
        return DEFAULT_BACKEND_URL
    }

    fun share(
            activity: ComponentActivity,
            sourceTag: String,
            backendUrlOverride: String? = null,
            onBusyChanged: ((Boolean) -> Unit)? = null
    ) {
        val backendUrl = backendUrlOverride?.trim().orEmpty().ifBlank { currentBackendUrl() }
        AppLog.info(sourceTag, "Collecting diagnostics for debug log share.")
        onBusyChanged?.invoke(true)
        val loadingDialog = showLoadingDialog(activity)

        activity.lifecycleScope.launch {
            val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val payload = DiagnosticsCollector.collect(activity)
                            DiagnosticsShareClient.upload(backendUrl, payload)
                        }
                    }

            if (!activity.isFinishing && !activity.isDestroyed) {
                loadingDialog.dismiss()
                onBusyChanged?.invoke(false)
                result.onSuccess { receipt ->
                    AppLog.info(sourceTag, "Debug logs shared with code ${receipt.code}.")
                    toast(activity, activity.getString(R.string.toast_log_share_success))
                    showSharedLogsDialog(activity, receipt)
                }
                result.onFailure {
                    val message =
                            it.message
                                    ?: activity.getString(
                                            R.string.toast_log_share_failed
                                    )
                    AppLog.error(
                            sourceTag,
                            "Debug log share failed: $message"
                    )
                    showShareErrorDialog(activity, message)
                }
            }
        }
    }

    private fun showSharedLogsDialog(activity: ComponentActivity, receipt: SharedLogReceipt) {
        AlertDialog.Builder(activity)
                .setTitle(R.string.share_logs_title)
                .setMessage(activity.getString(R.string.share_logs_message, receipt.code))
                .setPositiveButton(R.string.share_logs_copy_command) { _, _ ->
                    copyToClipboard(activity, receipt.command)
                }
                .setNegativeButton(R.string.share_logs_close, null)
                .show()
    }

    private fun copyToClipboard(activity: ComponentActivity, text: String) {
        val clipboard =
                activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("MBF Debug Command", text))
        toast(activity, activity.getString(R.string.toast_command_copied))
    }

    private fun showLoadingDialog(activity: ComponentActivity): AlertDialog {
        val padding = (activity.resources.displayMetrics.density * 20).toInt()
        val spacing = (activity.resources.displayMetrics.density * 14).toInt()
        val container =
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(padding, padding, padding, padding)
                }
        val progress =
                ProgressBar(activity).apply { isIndeterminate = true }
        val label =
                TextView(activity).apply {
                    text = activity.getString(R.string.share_logs_loading)
                    setPadding(spacing, 0, 0, 0)
                }
        container.addView(progress)
        container.addView(label)

        return AlertDialog.Builder(activity)
                .setTitle(R.string.share_logs_loading_title)
                .setView(container)
                .setCancelable(false)
                .show()
    }

    private fun showShareErrorDialog(activity: ComponentActivity, message: String) {
        AlertDialog.Builder(activity)
                .setTitle(R.string.share_logs_error_title)
                .setMessage(
                        activity.getString(
                                R.string.share_logs_error_message,
                                message
                        )
                )
                .setPositiveButton(R.string.share_logs_copy_error) { _, _ ->
                    copyToClipboard(activity, message)
                }
                .setNegativeButton(R.string.share_logs_close, null)
                .show()
    }

    private fun toast(activity: ComponentActivity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
