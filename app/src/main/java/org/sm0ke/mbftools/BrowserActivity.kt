package org.sm0ke.mbftools

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import org.json.JSONArray
import java.io.File

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
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingRecommendedPackUpload: PendingRecommendedPackUpload? = null
    private var recommendedPackInstallFingerprint: String? = null
    private var recommendedPackUploadAttempt = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recommendedPackUploadRetryRunnable: Runnable? = null
    private val fileChooserLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                handleFileChooserResult(result.resultCode, result.data)
            }

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

                    override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                    ): Boolean {
                        AppLog.info(
                                "Browser",
                                "WebView requested file chooser. pendingRecommendedPackUpload=${pendingRecommendedPackUpload != null} acceptTypes=${fileChooserParams?.acceptTypes?.joinToString().orEmpty()} mode=${fileChooserParams?.mode ?: -1}"
                        )
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = filePathCallback

                        if (filePathCallback == null) {
                            AppLog.warn(
                                    "Browser",
                                    "WebView file chooser callback was null."
                            )
                            return false
                        }

                        pendingRecommendedPackUpload?.let { pendingUpload ->
                            AppLog.info(
                                    "Browser",
                                    "Supplying staged recommended-pack file to WebView chooser: file=${pendingUpload.file.name} bytes=${pendingUpload.file.length()} uri=${pendingUpload.uri}"
                            )
                            filePathCallback.onReceiveValue(arrayOf(pendingUpload.uri))
                            cancelRecommendedPackUploadRetry()
                            markRecommendedPackPromptedAfterConfirmedInstall(
                                    pendingUpload.fingerprint,
                                    "native file chooser handoff"
                            )
                            Toast.makeText(
                                            this@BrowserActivity,
                                            R.string.recommended_pack_install_started,
                                            Toast.LENGTH_LONG
                                    )
                                    .show()
                            fileChooserCallback = null
                            pendingRecommendedPackUpload = null
                            return true
                        }

                        val chooserIntent =
                                try {
                                    fileChooserParams?.createIntent()
                                            ?: Intent(Intent.ACTION_GET_CONTENT)
                                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                                    .setType("*/*")
                                } catch (error: Throwable) {
                                    AppLog.warn(
                                            "Browser",
                                            "Failed to create WebView chooser intent: ${error.message ?: error.javaClass.simpleName}"
                                    )
                                    Intent(Intent.ACTION_GET_CONTENT)
                                            .addCategory(Intent.CATEGORY_OPENABLE)
                                            .setType("*/*")
                                }

                        return try {
                            fileChooserLauncher.launch(chooserIntent)
                            true
                        } catch (error: Throwable) {
                            AppLog.error(
                                    "Browser",
                                    "Failed to launch WebView chooser intent: ${error.message ?: error.javaClass.simpleName}"
                            )
                            fileChooserCallback = null
                            false
                        }
                    }
                }
        webView.webViewClient =
                object : WebViewClient() {
                    override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                    ) {
                        super.onPageStarted(view, url, favicon)
                        hasInjectedRecommendedPackObserver = false
                        AppLog.info(
                                "Browser",
                                "Page started loading: ${url ?: "<unknown>"}"
                        )
                    }

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
        cancelRecommendedPackUploadRetry()
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
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
                    shouldAutoPromptRecommendedPack = false
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
        recommendedPackInstallFingerprint = pack.fingerprint
        if (!pack.bundledQmodBase64.isNullOrBlank() && !pack.bundledQmodFileName.isNullOrBlank()) {
            uploadRecommendedPackBundle(pack)
            return
        }

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
                                        .put("aliases", JSONArray().apply {
                                            put(mod.displayName)
                                            if (!mod.id.equals(mod.displayName, ignoreCase = true)) {
                                                put(mod.id)
                                            }
                                        })
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
                    const reportSummary = (queued, total) => {
                        try {
                            if (bridge && typeof bridge.onRecommendedPackInstallSummary === 'function') {
                                bridge.onRecommendedPackInstallSummary(String(queued), String(total));
                            }
                        } catch (_) {}
                    };
                    const labels = (mod) => {
                        const raw = Array.isArray(mod.aliases) ? mod.aliases : [mod.name, mod.id];
                        return Array.from(new Set(raw.map(normalize).filter(Boolean)));
                    };
                    const findAddModsTab = () => {
                        return Array.from(document.querySelectorAll('.tab-header'))
                            .find(node => normalize(node.textContent) === 'add mods');
                    };
                    const clickAddModsTab = async () => {
                        for (let attempt = 0; attempt < 10; attempt++) {
                            const tab = findAddModsTab();
                            if (tab) {
                                if (!tab.classList.contains('selected')) {
                                    tab.click();
                                    log('Opened Add Mods tab on attempt ' + (attempt + 1) + '.');
                                } else {
                                    log('Add Mods tab already active on attempt ' + (attempt + 1) + '.');
                                }
                                await wait(350);
                                if (document.querySelector('.modRepoCard') || document.querySelector('button.installMod')) {
                                    return true;
                                }
                            } else {
                                log('Add Mods tab was not ready on attempt ' + (attempt + 1) + '.');
                            }
                            await wait(300);
                        }
                        log('Could not find Add Mods tab before starting installs.');
                        return false;
                    };
                    const findInstallButton = (card) => {
                        const direct = card.querySelector('button.installMod');
                        if (direct) {
                            return direct;
                        }
                        return Array.from(card.querySelectorAll('button'))
                            .find(button => {
                                const label = normalize(button.textContent);
                                return label.includes('install') || label.includes('update');
                            }) || null;
                    };
                    const cardMatches = (card, mod) => {
                        const haystack = normalize(card.innerText);
                        return labels(mod).some(label => haystack.includes(label));
                    };
                    log('Install script started for ' + mods.length + ' recommended mods.');
                    (async function() {
                        try {
                            await clickAddModsTab();
                            let successCount = 0;
                            async function install(mod) {
                                for (let attempt = 0; attempt < 40; attempt++) {
                                    if (attempt % 5 === 0) {
                                        await clickAddModsTab();
                                    }
                                    const cards = Array.from(document.querySelectorAll('.modRepoCard'));
                                    if (cards.length === 0) {
                                        log('No mod cards visible yet for ' + mod.name + ' on attempt ' + (attempt + 1) + '.');
                                        await wait(500);
                                        continue;
                                    }
                                    const card = cards.find(node => cardMatches(node, mod));
                                    if (!card) {
                                        log('Could not find card for ' + mod.name + ' on attempt ' + (attempt + 1) + '.');
                                        await wait(500);
                                        continue;
                                    }
                                    card.scrollIntoView({ block: 'center', inline: 'nearest' });
                                    const button = findInstallButton(card);
                                    if (button) {
                                        button.click();
                                        log('Queued mod install for ' + mod.name + ' on attempt ' + (attempt + 1) + '.');
                                        await wait(400);
                                        return true;
                                    }
                                    log('Found card for ' + mod.name + ' but no install button was available on attempt ' + (attempt + 1) + '.');
                                    await wait(500);
                                }
                                log('Could not queue mod install for ' + mod.name + ' after 40 attempts.');
                                return false;
                            }
                            for (const mod of mods) {
                                if (await install(mod)) {
                                    successCount += 1;
                                }
                            }
                            log('Install script finished. queued=' + successCount + ' total=' + mods.length + '.');
                            reportSummary(successCount, mods.length);
                        } catch (error) {
                            log('Install script crashed: ' + (error && error.message ? error.message : error));
                            reportSummary(0, mods.length);
                        }
                    })();
                })($modsJson);
                """
                        .trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun uploadRecommendedPackBundle(pack: RecommendedModPack) {
        val bundleName = pack.bundledQmodFileName ?: return
        val bundleBase64 = pack.bundledQmodBase64 ?: return
        val stagedUpload = stageRecommendedPackUpload(bundleName, bundleBase64)
        if (stagedUpload == null) {
            AppLog.error(
                    "Browser",
                    "Failed to stage recommended-pack bundle for fingerprint=${pack.fingerprint} file=$bundleName"
            )
            recommendedPackInstallFingerprint = null
            Toast.makeText(this, R.string.recommended_pack_install_failed, Toast.LENGTH_LONG).show()
            return
        }
        pendingRecommendedPackUpload = stagedUpload
        recommendedPackUploadAttempt = 0
        AppLog.info(
                "Browser",
                "Starting recommended-pack bundle upload for fingerprint=${pack.fingerprint} file=$bundleName bytes=${stagedUpload.file.length()} uri=${stagedUpload.uri}"
        )
        attemptRecommendedPackBundleUpload(pack, bundleName)
    }

    private fun attemptRecommendedPackBundleUpload(
            pack: RecommendedModPack,
            bundleName: String
    ) {
        val pendingUpload = pendingRecommendedPackUpload
        if (pendingUpload == null || pendingUpload.fingerprint != pack.fingerprint) {
            return
        }
        if (recommendedPackUploadAttempt >= MAX_RECOMMENDED_PACK_UPLOAD_ATTEMPTS) {
            AppLog.error(
                    "Browser",
                    "Recommended-pack upload never opened the MBF file chooser after $recommendedPackUploadAttempt attempts for fingerprint=${pack.fingerprint}"
            )
            pendingRecommendedPackUpload = null
            recommendedPackInstallFingerprint = null
            Toast.makeText(this, R.string.recommended_pack_install_failed, Toast.LENGTH_LONG).show()
            return
        }
        recommendedPackUploadAttempt += 1
        AppLog.info(
                "Browser",
                "Attempting recommended-pack bundle upload activation attempt=$recommendedPackUploadAttempt fingerprint=${pack.fingerprint} file=$bundleName"
        )
        val script =
                """
                (function(fileName, attemptNumber) {
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
                    const findAddModsTab = () => {
                        return Array.from(document.querySelectorAll('.tab-header'))
                            .find(node => normalize(node.textContent) === 'add mods');
                    };
                    const ensureAddModsTab = async () => {
                        for (let step = 0; step < 8; step++) {
                            const tab = findAddModsTab();
                            if (tab) {
                                if (!tab.classList.contains('selected')) {
                                    tab.click();
                                    log('Opened Add Mods tab while preparing bundled qmod upload on attempt ' + attemptNumber + '.');
                                }
                                await wait(250);
                                return true;
                            }
                            await wait(250);
                        }
                        log('Add Mods tab was not ready yet while preparing bundled qmod upload on attempt ' + attemptNumber + '.');
                        return false;
                    };
                    const clickUploadButton = () => {
                        const uploadButton = document.querySelector('#uploadButton');
                        if (uploadButton) {
                            uploadButton.scrollIntoView({ block: 'center', inline: 'nearest' });
                            uploadButton.click();
                            log('Clicked #uploadButton for bundled qmod ' + fileName + ' on attempt ' + attemptNumber + '.');
                            return true;
                        }
                        const directInput = document.querySelector('input#file[type=file]');
                        if (directInput) {
                            directInput.click();
                            log('Clicked hidden #file input for bundled qmod ' + fileName + ' on attempt ' + attemptNumber + '.');
                            return true;
                        }
                        log('Upload button is not available yet on attempt ' + attemptNumber + '.');
                        return false;
                    };
                    (async function() {
                        await ensureAddModsTab();
                        for (let step = 0; step < 4; step++) {
                            if (clickUploadButton()) {
                                return;
                            }
                            await wait(300);
                        }
                        log('Bundled qmod upload did not find #uploadButton on native attempt ' + attemptNumber + '.');
                    })();
                })(${org.json.JSONObject.quote(bundleName)}, $recommendedPackUploadAttempt);
                """
                        .trimIndent()
        webView.evaluateJavascript(script, null)
        cancelRecommendedPackUploadRetry()
        recommendedPackUploadRetryRunnable =
                Runnable {
                    attemptRecommendedPackBundleUpload(pack, bundleName)
                }
        mainHandler.postDelayed(
                recommendedPackUploadRetryRunnable!!,
                RECOMMENDED_PACK_UPLOAD_RETRY_DELAY_MS
        )
    }

    private fun stageRecommendedPackUpload(
            fileName: String,
            bundleBase64: String
    ): PendingRecommendedPackUpload? {
        return try {
            val bytes = Base64.decode(bundleBase64, Base64.DEFAULT)
            val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
            val file =
                    File(updatesDir, sanitizeFileName(fileName)).apply {
                        writeBytes(bytes)
                    }
            val uri =
                    FileProvider.getUriForFile(
                            this,
                            "${packageName}.fileprovider",
                            file
                    )
            AppLog.info(
                    "Browser",
                    "Staged recommended-pack upload file=${file.absolutePath} bytes=${file.length()} uri=$uri"
            )
            PendingRecommendedPackUpload(
                    fingerprint = recommendedPackInstallFingerprint ?: "",
                    file = file,
                    uri = uri
            )
        } catch (error: Throwable) {
            AppLog.error(
                    "Browser",
                    "Failed to stage recommended-pack upload file: ${error.message ?: error.javaClass.simpleName}"
            )
            null
        }
    }

    private fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        val callback = fileChooserCallback ?: return
        val uris =
                if (resultCode == Activity.RESULT_OK) {
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                } else {
                    null
                }
        AppLog.info(
                "Browser",
                "WebView chooser result received. resultCode=$resultCode uriCount=${uris?.size ?: 0} pendingRecommendedPackUpload=${pendingRecommendedPackUpload != null}"
        )
        callback.onReceiveValue(uris)
        fileChooserCallback = null
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    }

    private fun cancelRecommendedPackUploadRetry() {
        recommendedPackUploadRetryRunnable?.let(mainHandler::removeCallbacks)
        recommendedPackUploadRetryRunnable = null
    }

    private fun markRecommendedPackPromptedAfterConfirmedInstall(
            fingerprint: String,
            confirmationSource: String
    ) {
        if (fingerprint.isBlank()) {
            return
        }
        if (!AppPrefs.hasPromptedRecommendedPack(this, fingerprint)) {
            AppPrefs.markRecommendedPackPrompted(this, fingerprint)
        }
        recommendedPackInstallFingerprint = null
        AppLog.info(
                "Browser",
                "Confirmed recommended-pack install handoff via $confirmationSource for fingerprint=$fingerprint"
        )
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

        @JavascriptInterface
        fun onRecommendedPackInstallSummary(queuedCount: String?, totalCount: String?) {
            val queued = queuedCount?.toIntOrNull() ?: 0
            val total = totalCount?.toIntOrNull() ?: 0
            AppLog.info(
                    "Browser",
                    "Received recommended-pack install summary queued=$queued total=$total fingerprint=${recommendedPackInstallFingerprint ?: "<none>"}"
            )
            runOnUiThread {
                if (queued > 0) {
                    markRecommendedPackPromptedAfterConfirmedInstall(
                            recommendedPackInstallFingerprint.orEmpty(),
                            "MBF queued $queued of $total mods"
                    )
                    Toast.makeText(
                                    this@BrowserActivity,
                                    R.string.recommended_pack_install_started,
                                    Toast.LENGTH_LONG
                            )
                            .show()
                } else {
                    recommendedPackInstallFingerprint = null
                    Toast.makeText(
                                    this@BrowserActivity,
                                    R.string.recommended_pack_install_failed,
                                    Toast.LENGTH_LONG
                            )
                            .show()
                }
            }
        }
    }

    private data class PendingRecommendedPackUpload(
            val fingerprint: String,
            val file: File,
            val uri: Uri
    )

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_ENABLE_RECOMMENDED_MOD_PACK_PROMPT =
                "extra_enable_recommended_mod_pack_prompt"
        const val EXTRA_BEAT_SABER_VERSION_TAG = "extra_beat_saber_version_tag"
        const val EXTRA_CLOSE_TO_HOME_ON_EXIT = "extra_close_to_home_on_exit"

        private const val JS_BRIDGE_NAME = "MbfToolsBridge"
        private const val RECOMMENDED_PACK_READY_STATE = "ready"
        private const val MAX_RECOMMENDED_PACK_UPLOAD_ATTEMPTS = 10
        private const val RECOMMENDED_PACK_UPLOAD_RETRY_DELAY_MS = 1200L
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
                    const normalize = (value) => String(value || '')
                        .toLowerCase()
                        .replace(/[^a-z0-9]+/g, ' ')
                        .trim();
                    log('Observer installed for ' + window.location.href);
                    const notify = () => {
                        const text = document.body ? String(document.body.innerText || '') : '';
                        const hasModCards =
                            document.querySelector('.modRepoCard') !== null ||
                            document.querySelector('button.installMod') !== null ||
                            document.querySelector('#uploadButton') !== null;
                        const hasModTabs =
                            Array.from(document.querySelectorAll('.tab-header'))
                                .some(node => {
                                    const label = normalize(node.textContent);
                                    return label === 'your mods' || label === 'add mods';
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
