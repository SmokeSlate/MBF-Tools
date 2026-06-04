package org.sm0ke.mbftools

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.json.JSONArray

class BrowserActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var backButton: Button
    private lateinit var titleView: TextView
    private var recommendedPack: RecommendedModPack? = null
    private var hasInjectedRecommendedPackObserver = false
    private var hasHandledRecommendedPackPrompt = false
    private var closeToHomeOnExit = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.webBrowser)
        backButton = findViewById(R.id.btnBrowserBack)
        titleView = findViewById(R.id.txtBrowserTitle)
        closeToHomeOnExit = intent.getBooleanExtra(EXTRA_CLOSE_TO_HOME_ON_EXIT, false)
        recommendedPack =
                intent.getStringExtra(EXTRA_BEAT_SABER_VERSION_TAG)
                        ?.let { versionTag -> RecommendedModPacks.forVersion(versionTag) }
                        ?.takeUnless {
                            AppPrefs.hasPromptedRecommendedPackVersion(this, it.versionTag)
                        }

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
        if (intent.getBooleanExtra(EXTRA_ENABLE_RECOMMENDED_MOD_PACK_PROMPT, false)) {
            webView.addJavascriptInterface(RecommendedPackBridge(), JS_BRIDGE_NAME)
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
                        injectRecommendedPackObserverIfNeeded()
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
            closeBrowser()
        }
        syncBackButton()
    }

    private fun closeBrowser() {
        if (closeToHomeOnExit) {
            startActivity(
                    Intent(this, HomeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
            )
        }
        finish()
    }

    private fun syncBackButton() {
        backButton.text =
                getString(
                        if (webView.canGoBack()) R.string.browser_back else R.string.browser_close
                )
    }

    private fun injectRecommendedPackObserverIfNeeded() {
        if (hasInjectedRecommendedPackObserver || recommendedPack == null) {
            return
        }
        hasInjectedRecommendedPackObserver = true
        webView.evaluateJavascript(RECOMMENDED_PACK_OBSERVER_SCRIPT, null)
    }

    private fun maybeShowRecommendedPackPrompt() {
        val pack = recommendedPack ?: return
        if (hasHandledRecommendedPackPrompt) {
            return
        }
        hasHandledRecommendedPackPrompt = true
        AppPrefs.markRecommendedPackVersionPrompted(this, pack.versionTag)

        val message =
                getString(
                        R.string.recommended_pack_prompt_body,
                        pack.mods.joinToString(separator = "\n• ", prefix = "• ") {
                            it.displayName
                        }
                )
        AlertDialog.Builder(this)
                .setTitle(getString(R.string.recommended_pack_prompt_title))
                .setMessage(message)
                .setNegativeButton(R.string.recommended_pack_prompt_skip, null)
                .setPositiveButton(R.string.recommended_pack_prompt_install) { _, _ ->
                    installRecommendedPack(pack)
                }
                .show()
    }

    private fun installRecommendedPack(pack: RecommendedModPack) {
        val modsJson =
                JSONArray().apply {
                    pack.mods.forEach { mod ->
                        put(
                                org.json.JSONObject()
                                        .put("id", mod.id)
                                        .put("name", mod.displayName)
                        )
                    }
                }
        val script =
                """
                (function(mods) {
                    const wait = (ms) => new Promise(resolve => setTimeout(resolve, ms));
                    const normalize = (value) => String(value || '')
                        .toLowerCase()
                        .replace(/[^a-z0-9]+/g, ' ')
                        .trim();
                    async function install(mod) {
                        for (let attempt = 0; attempt < 40; attempt++) {
                            const cards = Array.from(document.querySelectorAll('.modRepoCard'));
                            if (cards.length === 0) {
                                await wait(500);
                                continue;
                            }
                            const wanted = normalize(mod.name);
                            const card = cards.find(node => normalize(node.innerText).includes(wanted));
                            if (!card) {
                                await wait(500);
                                continue;
                            }
                            const button = card.querySelector('button.installMod');
                            if (button) {
                                button.click();
                                await wait(250);
                                return true;
                            }
                            await wait(500);
                        }
                        return false;
                    }
                    (async function() {
                        for (const mod of mods) {
                            await install(mod);
                        }
                    })();
                })($modsJson);
                """
                        .trimIndent()
        webView.evaluateJavascript(script, null)
        Toast.makeText(this, R.string.recommended_pack_install_started, Toast.LENGTH_LONG).show()
    }

    private inner class RecommendedPackBridge {
        @JavascriptInterface
        fun onMbfState(state: String?) {
            if (state != RECOMMENDED_PACK_READY_STATE) {
                return
            }
            runOnUiThread { maybeShowRecommendedPackPrompt() }
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_ENABLE_RECOMMENDED_MOD_PACK_PROMPT =
                "extra_enable_recommended_mod_pack_prompt"
        const val EXTRA_BEAT_SABER_VERSION_TAG = "extra_beat_saber_version_tag"
        const val EXTRA_CLOSE_TO_HOME_ON_EXIT = "extra_close_to_home_on_exit"

        private const val JS_BRIDGE_NAME = "MbfToolsBridge"
        private const val RECOMMENDED_PACK_READY_STATE = "ready"
        private val RECOMMENDED_PACK_OBSERVER_SCRIPT =
                """
                (function() {
                    if (window.__mbfToolsRecommendedPackObserverInstalled) {
                        return;
                    }
                    window.__mbfToolsRecommendedPackObserverInstalled = true;
                    const bridge = window.$JS_BRIDGE_NAME;
                    if (!bridge || typeof bridge.onMbfState !== 'function') {
                        return;
                    }
                    const notify = () => {
                        const text = document.body ? String(document.body.innerText || '') : '';
                        if (text.includes('App is modded') || text.includes('Everything should be ready to go!')) {
                            bridge.onMbfState('$RECOMMENDED_PACK_READY_STATE');
                        }
                    };
                    const observer = new MutationObserver(notify);
                    observer.observe(document.documentElement || document.body, {
                        childList: true,
                        subtree: true,
                        characterData: true
                    });
                    notify();
                    setInterval(notify, 1500);
                })();
                """
                        .trimIndent()
    }
}
