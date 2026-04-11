package org.sm0ke.mbftools

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

class BrowserActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var backButton: Button
    private lateinit var titleView: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.webBrowser)
        backButton = findViewById(R.id.btnBrowserBack)
        titleView = findViewById(R.id.txtBrowserTitle)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            userAgentString = "MbfLauncher/1.0"
        }

        webView.webChromeClient =
                object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        titleView.text =
                                title?.takeIf { it.isNotBlank() }
                                        ?: getString(R.string.browser_loading)
                    }
                }
        webView.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        syncBackButton()
                    }
                }

        backButton.setOnClickListener { handleBackPress() }

        onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        handleBackPress()
                    }
                }
        )

        val url =
                intent.getStringExtra(EXTRA_URL)
                        ?: throw IllegalStateException("Browser URL is required.")
        AppLog.info("Browser", "Opening browser for $url")
        webView.loadUrl(url)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun handleBackPress() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
        syncBackButton()
    }

    private fun syncBackButton() {
        backButton.text =
                getString(
                        if (webView.canGoBack()) R.string.browser_back else R.string.browser_close
                )
    }

    companion object {
        const val EXTRA_URL = "extra_url"
    }
}
