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
import androidx.core.view.isVisible
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.json.JSONArray

class BrowserActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var backButton: Button
    private lateinit var recommendedPackButton: Button
    private lateinit var titleView: TextView
    private var recommendedPack: RecommendedModPack? = null
    private var beatSaberVersionTag: String? = null
    private var hasInjectedRecommendedPackObserver = false
    private var hasHandledRecommendedPackPrompt = false
    private var closeToHomeOnExit = false
    private var recommendedPackUiEnabled = false
    private var shouldAutoPromptRecommendedPack = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.webBrowser)
        backButton = findViewById(R.id.btnBrowserBack)
        recommendedPackButton = findViewById(R.id.btnBrowserRecommendedPack)
        titleView = findViewById(R.id.txtBrowserTitle)
        closeToHomeOnExit = intent.getBooleanExtra(EXTRA_CLOSE_TO_HOME_ON_EXIT, false)
        beatSaberVersionTag = intent.getStringExtra(EXTRA_BEAT_SABER_VERSION_TAG)
        recommendedPackUiEnabled =
                intent.getBooleanExtra(EXTRA_ENABLE_RECOMMENDED_MOD_PACK_PROMPT, false)
        recommendedPack =
                beatSaberVersionTag?.let { versionTag ->
                    RecommendedModPacks.forVersion(versionTag)
                }
        val alreadyPrompted =
                recommendedPack?.let { AppPrefs.hasPromptedRecommendedPack(this, it.fingerprint) }
                        ?: false
        shouldAutoPromptRecommendedPack =
                recommendedPackUiEnabled && recommendedPack != null && !alreadyPrompted
        logRecommendedPackSetup(alreadyPrompted)

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
        if (recommendedPackUiEnabled || recommendedPack != null) {
            webView.addJavascriptInterface(RecommendedPackBridge(), JS_BRIDGE_NAME)
            AppLog.info("Browser", "Registered MBF recommended-pack JavaScript bridge.")
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
                        AppLog.info(
                                "Browser",
                                "Page finished loading: ${url ?: "<unknown>"}"
                        )
                        syncBackButton()
                        syncRecommendedPackButton()
                        injectRecommendedPackObserverIfNeeded()
                    }
                }

        backButton.setOnClickListener { handleBackPress() }
        recommendedPackButton.setOnClickListener { openRecommendedPackFromToolbar() }
        syncRecommendedPackButton()

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
        AppLog.info(
                "Browser",
                "Closing browser. closeToHomeOnExit=$closeToHomeOnExit currentUrl=${webView.url ?: "<unknown>"}"
        )
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

    private fun syncRecommendedPackButton() {
        recommendedPackButton.isVisible = recommendedPackUiEnabled
    }

    private fun logRecommendedPackSetup(alreadyPrompted: Boolean) {
        val pack = recommendedPack
        if (!recommendedPackUiEnabled) {
            AppLog.info("Browser", "Recommended-pack UI is disabled for this browser session.")
            return
        }
        if (pack == null) {
            AppLog.warn(
                    "Browser",
                    "Recommended-pack UI enabled, but no pack is mapped for versionTag=${beatSaberVersionTag ?: "<none>"}. Supported versions=${RecommendedModPacks.supportedVersionTags().sorted().joinToString()}"
            )
            return
        }
        AppLog.info(
                "Browser",
                "Recommended-pack setup: versionTag=${pack.versionTag}, title=${pack.title}, modCount=${pack.mods.size}, fingerprint=${pack.fingerprint}, alreadyPrompted=$alreadyPrompted, autoPrompt=$shouldAutoPromptRecommendedPack"
        )
    }

    private fun injectRecommendedPackObserverIfNeeded() {
        if (hasInjectedRecommendedPackObserver) {
            AppLog.info("Browser", "Recommended-pack observer already injected for this page.")
            return
        }
        if (!shouldAutoPromptRecommendedPack) {
            AppLog.info(
                    "Browser",
                    "Skipping recommended-pack observer injection. autoPrompt=$shouldAutoPromptRecommendedPack versionTag=${beatSaberVersionTag ?: "<none>"}"
            )
            return
        }
        if (recommendedPack == null) {
            AppLog.warn(
                    "Browser",
                    "Skipping recommended-pack observer injection because no pack was resolved for versionTag=${beatSaberVersionTag ?: "<none>"}"
            )
            return
        }
        hasInjectedRecommendedPackObserver = true
        AppLog.info(
                "Browser",
                "Injecting recommended-pack observer for versionTag=${recommendedPack?.versionTag}"
        )
        webView.evaluateJavascript(RECOMMENDED_PACK_OBSERVER_SCRIPT, null)
    }

    private fun maybeShowRecommendedPackPrompt(trigger: String) {
        val pack = recommendedPack
        if (pack == null) {
            AppLog.warn(
                    "Browser",
                    "Recommended-pack prompt skipped ($trigger) because no pack is mapped for versionTag=${beatSaberVersionTag ?: "<none>"}"
            )
            return
        }
        if (!shouldAutoPromptRecommendedPack) {
            AppLog.info(
                    "Browser",
                    "Recommended-pack prompt skipped ($trigger). autoPrompt=$shouldAutoPromptRecommendedPack fingerprint=${pack.fingerprint}"
            )
            return
        }
        if (hasHandledRecommendedPackPrompt) {
            AppLog.info(
                    "Browser",
                    "Recommended-pack prompt already handled for fingerprint=${pack.fingerprint}; trigger=$trigger"
            )
            return
        }
        hasHandledRecommendedPackPrompt = true
        showRecommendedPackPrompt(pack, "automatic/$trigger")
    }

    private fun showRecommendedPackPrompt(pack: RecommendedModPack, source: String) {
        AppLog.info(
                "Browser",
                "Showing recommended-pack dialog from $source for versionTag=${pack.versionTag} with ${pack.mods.size} mods."
        )
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
                .setNegativeButton(R.string.recommended_pack_prompt_skip) { _, _ ->
                    AppLog.info(
                            "Browser",
                            "User skipped recommended-pack install from $source for fingerprint=${pack.fingerprint}"
                    )
                    AppPrefs.markRecommendedPackPrompted(this, pack.fingerprint)
                }
                .setPositiveButton(R.string.recommended_pack_prompt_install) { _, _ ->
                    AppLog.info(
                            "Browser",
                            "User accepted recommended-pack install from $source for fingerprint=${pack.fingerprint}"
                    )
                    AppPrefs.markRecommendedPackPrompted(this, pack.fingerprint)
                    installRecommendedPack(pack)
                }
                .show()
    }

    private fun openRecommendedPackFromToolbar() {
        val pack = recommendedPack
        if (pack == null) {
            AppLog.warn(
                    "Browser",
                    "Toolbar recommended-pack button pressed, but no pack is mapped for versionTag=${beatSaberVersionTag ?: "<none>"}"
            )
            Toast.makeText(this, R.string.recommended_pack_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        AppLog.info(
                "Browser",
                "Toolbar recommended-pack button pressed for versionTag=${pack.versionTag} fingerprint=${pack.fingerprint}"
        )
        hasHandledRecommendedPackPrompt = true
        shouldAutoPromptRecommendedPack = false
        showRecommendedPackPrompt(pack, "toolbar")
    }

    private fun installRecommendedPack(pack: RecommendedModPack) {
        AppLog.info(
                "Browser",
                "Starting recommended-pack install for fingerprint=${pack.fingerprint} modCount=${pack.mods.size}"
        )
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
                    const bridge = window.$JS_BRIDGE_NAME;
                    const log = (message) => {
                        try {
                            if (bridge && typeof bridge.logRecommendedPack === 'function') {
                                bridge.logRecommendedPack(message);
                            }
                        } catch (_) {}
                    };
                    const wait = (ms) => new Promise(resolve => setTimeout(resolve, ms));
                    const normalize = (value) => String(value || '')
                        .toLowerCase()
                        .replace(/[^a-z0-9]+/g, ' ')
                        .trim();
                    log('Install script started for ' + mods.length + ' recommended mods.');
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
                                log('Queued mod install for ' + mod.name + ' on attempt ' + (attempt + 1) + '.');
                                await wait(250);
                                return true;
                            }
                            await wait(500);
                        }
                        log('Could not queue mod install for ' + mod.name + ' after 40 attempts.');
                        return false;
                    }
                    (async function() {
                        let successCount = 0;
                        for (const mod of mods) {
                            if (await install(mod)) {
                                successCount += 1;
                            }
                        }
                        log('Install script finished. queued=' + successCount + ' total=' + mods.length + '.');
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
            AppLog.info("Browser", "Received MBF recommended-pack state callback: ${state ?: "<null>"}")
            if (state != RECOMMENDED_PACK_READY_STATE) {
                return
            }
            runOnUiThread { maybeShowRecommendedPackPrompt("bridge") }
        }

        @JavascriptInterface
        fun logRecommendedPack(message: String?) {
            AppLog.info(
                    "Browser",
                    "MBF recommended-pack observer: ${message?.trim().orEmpty().ifBlank { "<blank>" }}"
            )
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
                    const log = (message) => {
                        try {
                            if (typeof bridge.logRecommendedPack === 'function') {
                                bridge.logRecommendedPack(message);
                            }
                        } catch (_) {}
                    };
                    log('Observer installed for ' + window.location.href);
                    const notify = () => {
                        const text = document.body ? String(document.body.innerText || '') : '';
                        const hasModCards =
                            document.querySelector('.modRepoCard') !== null ||
                            document.querySelector('button.installMod') !== null;
                        const hasModTabs =
                            Array.from(document.querySelectorAll('button, a, [role="tab"]'))
                                .some(node => {
                                    const label = String(node.textContent || '').trim();
                                    return label === 'Your Mods' || label === 'Add Mods';
                                });
                        const hasReadyText =
                            text.includes('App is modded') ||
                            text.includes('Everything should be ready to go!') ||
                            (text.includes('Your Mods') && text.includes('Add Mods'));
                        const summary =
                            'url=' + window.location.href +
                            ' readyText=' + hasReadyText +
                            ' modCards=' + hasModCards +
                            ' modTabs=' + hasModTabs +
                            ' bodyLength=' + text.length;
                        if (window.__mbfToolsObserverLastSummary !== summary) {
                            window.__mbfToolsObserverLastSummary = summary;
                            log(summary);
                        }
                        if (hasReadyText || hasModCards || hasModTabs) {
                            log('Ready state detected. Notifying Android.');
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
