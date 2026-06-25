package com.educational.discordcreator

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ColorStateList
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Discord Account Creator v3 — Educational / Research Demo
 *
 * Three fully-automatic creation systems:
 *
 *  ◉ BROWSER  — WebView automation: JS injection, anti-detect, form fill, token harvest
 *  ◉ REQUEST  — Direct HTTP API: X-Super-Properties, Discord fingerprint, on-demand CAPTCHA
 *  ◉ CHROME   — Ultra-stealth WebView: Chrome 130 exact profile, silent form fill, auto token
 *
 * All modes are fully automatic — no human interaction required for token extraction.
 * Email aliases are generated automatically: base+<suffix>@domain (Gmail dot-trick)
 */
@Suppress("SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    // ── Mode constants ─────────────────────────────────────────────────────────
    companion object {
        const val MODE_BROWSER = 0
        const val MODE_REQ     = 1
        const val MODE_CHROME  = 2
    }

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnGo: Button
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var etGmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etCount: EditText
    private lateinit var etGroqKey: EditText
    private lateinit var btnStart: Button
    private lateinit var btnTestGroq: Button
    private lateinit var btnClearLog: Button
    private lateinit var btnDownloadAcc: Button
    private lateinit var btnDownloadTokens: Button
    private lateinit var btnOpenFile: Button
    private lateinit var btnSolveCaptcha: Button
    private lateinit var btnLocalSolve: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnClickRegister: Button
    private lateinit var btnModeBrowser: Button
    private lateinit var btnModeReq: Button
    private lateinit var btnModeChrome: Button
    private lateinit var rowBrowserCaptcha: LinearLayout
    private lateinit var rowReqInfo: LinearLayout
    private lateinit var rowChromeButtons: LinearLayout
    private lateinit var btnOpenCaptchaManual: Button
    private lateinit var btnChromeOpen: Button
    private lateinit var btnChromeGmail: Button
    private lateinit var btnChromeCopy: Button
    private lateinit var btnChromeToken: Button

    // ── Shared state ──────────────────────────────────────────────────────────
    private var currentMode  = MODE_BROWSER
    private var isRunning    = false
    private var successCount = 0
    private var failCount    = 0

    // ── Browser-mode state ────────────────────────────────────────────────────
    private var accountList: List<AccountInfo> = emptyList()
    private var currentIndex = 0
    private var currentAccountFillDone = false
    private val retryCount = mutableMapOf<Int, Int>()
    private var rateLimitBackoffMs = 30_000L
    private var currentProfile: FingerprintProfile = AntiBanManager.generateProfile()

    // ── Req-mode state ────────────────────────────────────────────────────────
    private var pendingCaptchaCallback: ((String) -> Unit)? = null
    private lateinit var captchaLauncher: ActivityResultLauncher<Intent>

    // ── Chrome-mode state ─────────────────────────────────────────────────────
    private var externalCreator: ExternalBrowserCreator? = null
    private var chromeCopyIndex = 0

    // ══════════════════════════════════════════════════════════════════════════
    //  JavaScript bridge (used in BROWSER and CHROME modes)
    // ══════════════════════════════════════════════════════════════════════════
    inner class DiscordBridge {

        @JavascriptInterface
        fun onTokenFound(email: String, token: String) {
            runOnUiThread {
                if (token.length < 20) return@runOnUiThread
                log("[ TOKEN ] ${token.take(24)}...")
                val acc = accountList.getOrNull(currentIndex) ?: return@runOnUiThread
                if (acc.status == "done") return@runOnUiThread
                acc.token  = token
                acc.status = "done"
                successCount++
                retryCount.remove(currentIndex)
                AccountManager.saveAccount(filesDir, acc)
                log("[ SAVED ] acc.txt + tokens.txt updated")
                updateStats()
                advanceToNextAccount()
            }
        }

        @JavascriptInterface
        fun onStatus(msg: String) = runOnUiThread { log("[ JS ] $msg") }

        @JavascriptInterface
        fun onFormFilled(email: String) {
            runOnUiThread {
                currentAccountFillDone = true
                log("[ FILL ] Fields filled for $email")
                CaptchaSolver.simulateHumanMouseMovement(webView)
                CaptchaSolver.watchForCaptchaSolvedAndSubmit(webView)
                LocalHCaptchaSolver.injectTokenWatcherAndSubmit(webView) { log(it) }
                webView.postDelayed({
                    log("   Clicking Register…")
                    CaptchaSolver.clickRegisterButton(webView)
                }, AntiBanManager.getHumanDelay())
            }
        }

        @JavascriptInterface
        fun onRegistrationResponse(email: String, token: String, error: String) {
            runOnUiThread {
                if (token.isNotEmpty()) { log("[ OK ] $email"); onTokenFound(email, token) }
                else { log("[ ERR ] $error"); handleAccountError() }
            }
        }

        @JavascriptInterface
        fun onCaptchaDetected(pageTitle: String, pageUrl: String) {
            runOnUiThread {
                log("[ CAPTCHA ] Detected — running local solver stack...")
                LocalHCaptchaSolver.runAllStrategies(webView) { log(it) }
                CaptchaSolver.simulateHumanMouseMovement(webView)
                webView.postDelayed({ CaptchaSolver.tryAutoSolve(webView) }, 800)
                CaptchaSolver.watchForCaptchaSolvedAndSubmit(webView)
                val key = etGroqKey.text.toString().trim()
                if (key.isNotBlank()) {
                    Thread {
                        val hint = GroqClient.captchaHint(key, pageTitle, pageUrl)
                        runOnUiThread { log("   Groq hint: $hint") }
                    }.start()
                }
            }
        }

        @JavascriptInterface
        fun onRateLimited(retryAfterMs: Int) {
            runOnUiThread {
                val wait = when {
                    retryAfterMs > 0 -> retryAfterMs.toLong()
                    else -> {
                        val d = rateLimitBackoffMs
                        rateLimitBackoffMs = minOf(rateLimitBackoffMs * 2, 300_000L)
                        d
                    }
                }
                log("[ RATE ] Limited — waiting ${wait / 1000}s...")
                webView.postDelayed({
                    if (isRunning) {
                        rateLimitBackoffMs = 30_000L
                        currentAccountFillDone = false
                        webView.loadUrl("https://discord.com/register")
                    }
                }, wait)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  onCreate
    // ══════════════════════════════════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Register CAPTCHA launcher BEFORE super.onCreate finishes lifecycle
        captchaLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val token = result.data?.getStringExtra(CaptchaActivity.RESULT_TOKEN) ?: ""
            if (result.resultCode == RESULT_OK && token.isNotBlank()) {
                log("[ CAPTCHA ] Token returned: ${token.take(20)}...")
                pendingCaptchaCallback?.invoke(token)
            } else {
                log("[ CAPTCHA ] Cancelled / no token")
                pendingCaptchaCallback?.invoke("")
            }
            pendingCaptchaCallback = null
        }

        bindViews()
        setupWebView()
        setupButtons()

        LocalHCaptchaSolver.refreshAccessibilityCookies()
        webView.loadUrl("https://discord.com/register")

        log("[ v3 ] Discord Creator v3 — 3 creation systems ready")
        log("[ TIP ] EMAIL ALIASING: your base email → base+suffix@domain (auto)")
        log("──────────────────────────────")
    }

    // ── View binding ──────────────────────────────────────────────────────────
    private fun bindViews() {
        webView              = findViewById(R.id.webView)
        etUrl                = findViewById(R.id.etUrl)
        btnGo                = findViewById(R.id.btnGo)
        tvLog                = findViewById(R.id.tvLog)
        tvStatus             = findViewById(R.id.tvStatus)
        tvStats              = findViewById(R.id.tvStats)
        scrollLog            = findViewById(R.id.scrollLog)
        etGmail              = findViewById(R.id.etGmail)
        etPassword           = findViewById(R.id.etPassword)
        etCount              = findViewById(R.id.etCount)
        etGroqKey            = findViewById(R.id.etGroqKey)
        btnStart             = findViewById(R.id.btnStart)
        btnTestGroq          = findViewById(R.id.btnTestGroq)
        btnClearLog          = findViewById(R.id.btnClearLog)
        btnDownloadAcc       = findViewById(R.id.btnDownloadAcc)
        btnDownloadTokens    = findViewById(R.id.btnDownloadTokens)
        btnOpenFile          = findViewById(R.id.btnOpenFile)
        btnSolveCaptcha      = findViewById(R.id.btnSolveCaptcha)
        btnLocalSolve        = findViewById(R.id.btnLocalSolve)
        btnAccessibility     = findViewById(R.id.btnAccessibility)
        btnClickRegister     = findViewById(R.id.btnClickRegister)
        btnModeBrowser       = findViewById(R.id.btnModeBrowser)
        btnModeReq           = findViewById(R.id.btnModeReq)
        btnModeChrome        = findViewById(R.id.btnModeChrome)
        rowBrowserCaptcha    = findViewById(R.id.rowBrowserCaptcha)
        rowReqInfo           = findViewById(R.id.rowReqInfo)
        rowChromeButtons     = findViewById(R.id.rowChromeButtons)
        btnOpenCaptchaManual = findViewById(R.id.btnOpenCaptchaManual)
        btnChromeOpen        = findViewById(R.id.btnChromeOpen)
        btnChromeGmail       = findViewById(R.id.btnChromeGmail)
        btnChromeCopy        = findViewById(R.id.btnChromeCopy)
        btnChromeToken       = findViewById(R.id.btnChromeToken)
    }

    // ── WebView setup ─────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled                     = true
            domStorageEnabled                     = true
            databaseEnabled                       = true
            setSupportMultipleWindows(false)
            userAgentString                       = AntiBanManager.getRandomUserAgent()
            mixedContentMode                      = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort                       = true
            loadWithOverviewMode                  = true
            cacheMode                             = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls                   = false
            displayZoomControls                   = false
            mediaPlaybackRequiresUserGesture       = false
            allowFileAccess                       = false
            allowContentAccess                    = false
            javaScriptCanOpenWindowsAutomatically  = false
            layoutAlgorithm                       = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
        }
        android.webkit.WebView.setWebContentsDebuggingEnabled(false)

        webView.addJavascriptInterface(DiscordBridge(), "DiscordBridge")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView, AntiBanManager.getDocumentStartScript(), setOf("*")
            )
            log("[ SYS ] ✓ Pre-script injection active")
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                tvStatus.text = if (newProgress >= 100) "● Ready" else "↓ $newProgress%"
            }
            override fun onJsAlert(v: WebView?, u: String?, m: String?, r: JsResult?): Boolean { r?.confirm(); return true }
            override fun onJsConfirm(v: WebView?, u: String?, m: String?, r: JsResult?): Boolean { r?.confirm(); return true }
            override fun onJsPrompt(v: WebView?, u: String?, m: String?, d: String?, r: JsPromptResult?): Boolean { r?.confirm(d ?: ""); return true }
            override fun onConsoleMessage(cm: ConsoleMessage?): Boolean = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url ?: return
                etUrl.setText(url)
                log("[ NAV ] ${url.take(80)}")
                if (url.contains("discord.com")) {
                    AntiBanManager.injectEarlySignals(webView, currentProfile)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url ?: return
                log("[ PAGE ] ${url.take(80)}")
                if (url.contains("discord.com")) {
                    AntiBanManager.injectAntiDetection(webView, currentProfile)
                    injectTokenHarvester()
                }
                if (url.contains("discord.com/verify") ||
                    url.contains("discord.com/channels") ||
                    url.contains("discord.com/@me")) {
                    log("[ EMAIL ] Verify/success page — harvesting token...")
                    listOf(500L, 2000L, 5000L).forEach { d ->
                        webView.postDelayed({ injectTokenHarvester() }, d)
                    }
                }
                if (url.contains("discord.com/register")) {
                    webView.postDelayed({
                        webView.evaluateJavascript(
                            "(function(){var f=document.querySelector('input[name=\"email\"],input[type=\"email\"]');return f?'found':'missing';})()"
                        ) { r ->
                            if (r?.contains("missing") == true) {
                                log("[ RETRY ] Form missing after 12s — reloading...")
                                webView.reload()
                            }
                        }
                    }, 12_000L)

                    if (isRunning && currentMode != MODE_REQ && !currentAccountFillDone) {
                        val acc = accountList.getOrNull(currentIndex) ?: return
                        webView.postDelayed({ injectFormFill(acc) }, AntiBanManager.getPageLoadDelay())
                    }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    log("[ ERROR ] ${error?.errorCode}: ${error?.description} — retry in 5s")
                    view?.postDelayed({ view.reload() }, 5_000L)
                }
            }
        }
    }

    // ── Buttons setup ─────────────────────────────────────────────────────────
    private fun setupButtons() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        btnStart.setOnClickListener { if (isRunning) stopCreation() else startCreation() }

        btnGo.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isNotBlank()) {
                webView.loadUrl(if (url.startsWith("http")) url else "https://$url")
            }
        }
        etUrl.setOnEditorActionListener { _, _, _ -> btnGo.performClick(); true }

        btnTestGroq.setOnClickListener { testGroqKey() }
        btnClearLog.setOnClickListener { tvLog.text = "[Log cleared]\n" }
        btnDownloadAcc.setOnClickListener { shareFile(AccountManager.getAccFile(filesDir)) }
        btnDownloadTokens.setOnClickListener { shareFile(AccountManager.getTokensFile(filesDir)) }
        btnOpenFile.setOnClickListener { showFilesDialog() }

        btnSolveCaptcha.setOnClickListener {
            log("[ CAPTCHA ] Standard auto-solve...")
            CaptchaSolver.tryAutoSolve(webView)
            CaptchaSolver.watchForCaptchaSolvedAndSubmit(webView)
        }
        btnLocalSolve.setOnClickListener {
            log("[ LOCAL ] 6-strategy solver launching...")
            LocalHCaptchaSolver.runAllStrategies(webView) { log(it) }
            webView.postDelayed({
                LocalHCaptchaSolver.injectTokenWatcherAndSubmit(webView) { log(it) }
            }, 2500)
        }
        btnAccessibility.setOnClickListener {
            log("[ ACCESS ] Opening hCaptcha accessibility setup...")
            CaptchaSolver.openAccessibilitySetup(webView)
        }
        btnClickRegister.setOnClickListener {
            log("[ REG ] Clicking submit...")
            CaptchaSolver.clickRegisterButton(webView)
        }

        // Mode buttons
        btnModeBrowser.setOnClickListener { switchMode(MODE_BROWSER) }
        btnModeReq.setOnClickListener     { switchMode(MODE_REQ) }
        btnModeChrome.setOnClickListener  { switchMode(MODE_CHROME) }

        // Req mode manual CAPTCHA
        btnOpenCaptchaManual.setOnClickListener {
            openCaptchaActivity(
                siteKey = "4c672d35-0701-42b2-88c3-78380b0db560",
                service = "hcaptcha",
                rqToken = "",
                acc     = accountList.getOrNull(currentIndex) ?: return@setOnClickListener
            )
        }

        // Chrome mode buttons
        btnChromeOpen.setOnClickListener  { externalCreator?.openDiscordRegister() }
        btnChromeGmail.setOnClickListener { externalCreator?.openGmail() }
        btnChromeCopy.setOnClickListener  {
            val acc = accountList.getOrNull(currentIndex) ?: return@setOnClickListener
            when (chromeCopyIndex % 3) {
                0 -> { externalCreator?.copyEmailToClipboard();    log("[ COPY ] Email → clipboard") }
                1 -> { externalCreator?.copyPasswordToClipboard(); log("[ COPY ] Password → clipboard") }
                2 -> { externalCreator?.copyUsernameToClipboard(); log("[ COPY ] Username → clipboard") }
            }
            chromeCopyIndex++
        }
        btnChromeToken.setOnClickListener { externalCreator?.promptManualTokenNow() }
    }

    // ── Mode switching ────────────────────────────────────────────────────────
    private fun switchMode(mode: Int) {
        if (isRunning) { toast("Stop current run first"); return }
        currentMode = mode
        val activeColor   = Color.parseColor("#5865F2")
        val inactiveColor = Color.parseColor("#2C2F33")
        val activeTxt     = "#FFFFFF"
        val inactiveTxt   = "#888888"

        fun styleBtn(btn: Button, active: Boolean) {
            btn.backgroundTintList = ColorStateList.valueOf(if (active) activeColor else inactiveColor)
            btn.setTextColor(Color.parseColor(if (active) activeTxt else inactiveTxt))
            btn.text = "${if (active) "◉" else "◯"} ${btn.text.toString().trimStart('◉', '◯').trim()}"
        }

        styleBtn(btnModeBrowser, mode == MODE_BROWSER)
        styleBtn(btnModeReq,     mode == MODE_REQ)
        styleBtn(btnModeChrome,  mode == MODE_CHROME)

        rowBrowserCaptcha.visibility = if (mode == MODE_BROWSER) LinearLayout.VISIBLE else LinearLayout.GONE
        rowReqInfo.visibility        = if (mode == MODE_REQ)     LinearLayout.VISIBLE else LinearLayout.GONE
        rowChromeButtons.visibility  = if (mode == MODE_CHROME)  LinearLayout.VISIBLE else LinearLayout.GONE

        val labels = arrayOf("WebView Automation", "Direct API", "Chrome Stealth")
        log("[ MODE ] Switched to ${labels[mode]}")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Creation flow dispatcher
    // ══════════════════════════════════════════════════════════════════════════
    private fun startCreation() {
        val gmail    = etGmail.text.toString().trim()
        val password = etPassword.text.toString()
        val count    = etCount.text.toString().trim().toIntOrNull() ?: 0
        val groqKey  = etGroqKey.text.toString().trim()

        if (gmail.isEmpty() || !gmail.contains('@')) { toast("Invalid email"); return }
        if (password.length < 8)                      { toast("Password ≥ 8 chars"); return }
        if (count < 1 || count > 50)                  { toast("Count 1–50"); return }

        isRunning     = true
        successCount  = 0
        failCount     = 0
        currentIndex  = 0
        rateLimitBackoffMs = 30_000L
        AccountManager.clearFiles(filesDir)
        updateStats()

        btnStart.text = getString(R.string.btn_stop)
        btnStart.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_light)
        tvStatus.text = "Starting…"
        btnDownloadAcc.isEnabled    = false
        btnDownloadTokens.isEnabled = false

        val modeNames = arrayOf("BROWSER", "REQUEST", "CHROME")
        log("══════════════════════════════")
        log("[ START ] Mode: ${modeNames[currentMode]} | Count: $count")
        log("   Base email : $gmail")
        log("   Aliases    : $gmail → ${AccountManager.generateEmailAlias(gmail, 0)}, …")
        log("══════════════════════════════")

        when (currentMode) {
            MODE_BROWSER -> startBrowserMode(gmail, password, count, groqKey)
            MODE_REQ     -> startReqMode(gmail, password, count, groqKey)
            MODE_CHROME  -> startChromeMode(gmail, password, count, groqKey)
        }
    }

    private fun stopCreation() {
        isRunning = false
        currentAccountFillDone = false
        retryCount.clear()
        rateLimitBackoffMs = 30_000L
        ReqCreator.stop()
        externalCreator?.stop()

        btnStart.text = getString(R.string.btn_start)
        btnStart.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#57F287"))
        tvStatus.text = "Stopped"
        log("[ STOP ] ${currentIndex}/${accountList.size} — ${successCount}✓ ${failCount}✗")
        enableDownloadButtons()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  BROWSER MODE
    // ══════════════════════════════════════════════════════════════════════════
    private fun startBrowserMode(gmail: String, password: String, count: Int, groqKey: String) {
        if (groqKey.isNotBlank()) {
            log("[ AI ] Fetching Groq names...")
            Thread {
                val usernames    = GroqClient.generateUsernames(groqKey, count)
                val displayNames = GroqClient.generateDisplayNames(groqKey, count)
                runOnUiThread {
                    accountList = AccountManager.buildAccountList(gmail, password, count, usernames, displayNames)
                    createNextAccount()
                }
            }.start()
        } else {
            accountList = AccountManager.buildAccountList(gmail, password, count)
            createNextAccount()
        }
    }

    private fun createNextAccount() {
        if (!isRunning || currentIndex >= accountList.size) {
            finishRun()
            return
        }
        currentAccountFillDone = false
        currentProfile = AntiBanManager.generateProfile()
        webView.settings.userAgentString = currentProfile.userAgent

        val acc = accountList[currentIndex]
        tvStatus.text = "Account ${currentIndex + 1}/${accountList.size}"
        log("")
        log("[${currentIndex + 1}/${accountList.size}] ${acc.email}")
        log("   UA: ${currentProfile.userAgent.take(50)}…")

        clearWebViewSession {
            runOnUiThread {
                LocalHCaptchaSolver.refreshAccessibilityCookies()
                webView.loadUrl("https://discord.com/register")
            }
        }
    }

    private fun advanceToNextAccount() {
        retryCount.remove(currentIndex)
        currentIndex++
        val delay = AntiBanManager.getAccountDelay()
        log("   Next account in ${delay / 1000}s…")
        webView.postDelayed({ createNextAccount() }, delay)
    }

    private fun handleAccountError() {
        val acc = accountList.getOrNull(currentIndex) ?: run { advanceToNextAccount(); return }
        val tries = retryCount.getOrDefault(currentIndex, 0)
        if (tries < 2) {
            retryCount[currentIndex] = tries + 1
            log("[ WARN ] Retry ${tries + 1}/2…")
            acc.status = "pending"
            currentAccountFillDone = false
            webView.postDelayed({
                if (isRunning) webView.loadUrl("https://discord.com/register")
            }, AntiBanManager.getAccountDelay())
        } else {
            log("[ SKIP ] Account ${currentIndex + 1} skipped after 2 retries")
            acc.status = "error"
            failCount++
            retryCount.remove(currentIndex)
            updateStats()
            advanceToNextAccount()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  REQUEST MODE
    // ══════════════════════════════════════════════════════════════════════════
    private fun startReqMode(gmail: String, password: String, count: Int, groqKey: String) {
        val buildList: (List<String>, List<String>) -> Unit = { unames, dnames ->
            accountList = AccountManager.buildAccountList(gmail, password, count, unames, dnames)
            ReqCreator.createAccounts(
                accounts = accountList,
                filesDir = filesDir,
                listener = object : ReqCreator.Listener {
                    override fun onLog(msg: String) = runOnUiThread { log(msg) }

                    override fun onCaptchaRequired(
                        siteKey: String, service: String, rqToken: String,
                        accData: AccountInfo, onSolved: (String) -> Unit
                    ) = runOnUiThread {
                        log("[ CAPTCHA ] Opening solver — service: $service")
                        openCaptchaActivity(siteKey, service, rqToken, accData, onSolved)
                    }

                    override fun onAccountSuccess(acc: AccountInfo) = runOnUiThread {
                        successCount++
                        updateStats()
                        enableDownloadButtons()
                    }

                    override fun onAccountFailed(acc: AccountInfo, reason: String) = runOnUiThread {
                        failCount++
                        updateStats()
                    }

                    override fun onDone(success: Int, failed: Int) = runOnUiThread {
                        log("══════════════════════════════")
                        log("[ DONE ] REQ mode: ${success}✓ ${failed}✗")
                        log("══════════════════════════════")
                        finishRun()
                    }
                }
            )
        }

        if (groqKey.isNotBlank()) {
            Thread {
                val u = GroqClient.generateUsernames(groqKey, count)
                val d = GroqClient.generateDisplayNames(groqKey, count)
                runOnUiThread { buildList(u, d) }
            }.start()
        } else {
            buildList(emptyList(), emptyList())
        }
    }

    private fun openCaptchaActivity(
        siteKey: String,
        service: String,
        rqToken: String,
        acc: AccountInfo,
        onSolved: ((String) -> Unit)? = null
    ) {
        pendingCaptchaCallback = onSolved
        val intent = Intent(this, CaptchaActivity::class.java).apply {
            putExtra(CaptchaActivity.EXTRA_SITE_KEY,  siteKey)
            putExtra(CaptchaActivity.EXTRA_SERVICE,   service)
            putExtra(CaptchaActivity.EXTRA_RQ_TOKEN,  rqToken)
            putExtra(CaptchaActivity.EXTRA_EMAIL,     acc.email)
            putExtra(CaptchaActivity.EXTRA_PASSWORD,  acc.password)
            putExtra(CaptchaActivity.EXTRA_USERNAME,  acc.username)
            putExtra(CaptchaActivity.EXTRA_DISP_NAME, acc.displayName)
            putExtra(CaptchaActivity.EXTRA_BIRTH_M,   acc.birthMonth)
            putExtra(CaptchaActivity.EXTRA_BIRTH_D,   acc.birthDay)
            putExtra(CaptchaActivity.EXTRA_BIRTH_Y,   acc.birthYear)
        }
        captchaLauncher.launch(intent)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CHROME STEALTH MODE
    //  Fully automatic: uses a hidden ultra-stealth WebView that mimics Chrome
    //  exactly, auto-fills the form, auto-extracts the token — zero human input.
    // ══════════════════════════════════════════════════════════════════════════
    private fun startChromeMode(gmail: String, password: String, count: Int, groqKey: String) {
        val buildList: (List<String>, List<String>) -> Unit = { unames, dnames ->
            accountList = AccountManager.buildAccountList(gmail, password, count, unames, dnames)
            chromeCopyIndex = 0

            val creator = ExternalBrowserCreator(this)
            externalCreator = creator

            creator.start(
                accounts = accountList,
                filesDir = filesDir,
                listener = object : ExternalBrowserCreator.Listener {
                    override fun onLog(msg: String) = runOnUiThread { log(msg) }

                    override fun onAccountStarted(acc: AccountInfo, index: Int, total: Int) {
                        runOnUiThread {
                            currentIndex = index
                            tvStatus.text = "Chrome ${index + 1}/$total"
                            // Auto-fill this account in the WebView (stealth mode):
                            // Switch the main WebView to ultra-stealth profile and auto-register
                            launchStealthWebViewForAccount(acc)
                        }
                    }

                    override fun onTokenFound(acc: AccountInfo, token: String) = runOnUiThread {
                        successCount++
                        updateStats()
                        enableDownloadButtons()
                        log("[ CHROME ✓ ] ${acc.email} → ${token.take(20)}…")
                    }

                    override fun onAccountSkipped(acc: AccountInfo, reason: String) = runOnUiThread {
                        failCount++
                        updateStats()
                    }

                    override fun onDone(success: Int, failed: Int) = runOnUiThread {
                        log("══════════════════════════════")
                        log("[ DONE ] CHROME mode: ${success}✓ ${failed}✗")
                        log("══════════════════════════════")
                        finishRun()
                    }

                    override fun onRequestManualToken(acc: AccountInfo, callback: (String) -> Unit) {
                        runOnUiThread {
                            // Show dialog for manual token entry as last-resort fallback
                            val input = EditText(this@MainActivity).apply {
                                hint = "Paste Discord token here"
                                textSize = 11f
                            }
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Enter Token — ${acc.email}")
                                .setView(input)
                                .setPositiveButton("Save") { _, _ -> callback(input.text.toString().trim()) }
                                .setNegativeButton("Skip") { _, _ -> callback("") }
                                .setCancelable(false)
                                .show()
                        }
                    }
                }
            )
        }

        if (groqKey.isNotBlank()) {
            Thread {
                val u = GroqClient.generateUsernames(groqKey, count)
                val d = GroqClient.generateDisplayNames(groqKey, count)
                runOnUiThread { buildList(u, d) }
            }.start()
        } else {
            buildList(emptyList(), emptyList())
        }
    }

    /**
     * Chrome Stealth mode — uses the main WebView with an ultra-stealth Chrome 130
     * profile. The WebView is configured to perfectly mimic a real Chrome desktop
     * browser. Form fill + token extraction are fully automatic.
     *
     * When the ExternalBrowserCreator calls onAccountStarted, we load discord.com
     * in the WebView with a fresh Chrome 130 fingerprint. The existing token
     * harvester picks up the token and calls onTokenFound via notifyChromeModeToken.
     */
    private fun launchStealthWebViewForAccount(acc: AccountInfo) {
        // Generate a Chrome 130 stealth profile
        currentProfile = AntiBanManager.generateStealthProfile()
        webView.settings.userAgentString = currentProfile.userAgent
        currentAccountFillDone = false

        log("[ CHROME ] Stealth profile: ${currentProfile.userAgent.take(55)}…")

        // Override DiscordBridge token handler to notify chrome creator
        // The existing DiscordBridge onTokenFound will call the normal flow which
        // calls AccountManager.saveAccount — that's fine. We just also need to
        // notify the ExternalBrowserCreator so it advances to the next account.
        // We do that in the bridge by also calling notifyChromeModeToken().

        clearWebViewSession {
            runOnUiThread {
                LocalHCaptchaSolver.refreshAccessibilityCookies()
                webView.loadUrl("https://discord.com/register")
                // Schedule form fill for this account
                webView.postDelayed({
                    injectFormFill(acc)
                }, AntiBanManager.getPageLoadDelay() + 1000L)
            }
        }
    }

    /** Called from DiscordBridge when a token is found in Chrome mode */
    private fun notifyChromeModeToken(acc: AccountInfo, token: String) {
        externalCreator?.let { creator ->
            // Inject the token into the creator's flow so it saves and advances
            creator.injectToken(acc, token)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Token harvester JS injection
    // ══════════════════════════════════════════════════════════════════════════
    private fun injectTokenHarvester() {
        val js = """
(function() {
  if (window.__harvesterActive) return;
  window.__harvesterActive = true;

  function report(email, token) {
    if (!token || token.length < 20) return;
    if (window.__tokenReported) return;
    window.__tokenReported = true;
    try { window.DiscordBridge.onTokenFound(email || '', token); } catch(e) {}
  }

  function isTokenLike(v) {
    return typeof v === 'string' && v.length > 50
        && v.indexOf(' ') === -1 && v.indexOf('{') === -1 && v.indexOf('}') === -1
        && v.indexOf('[') === -1 && v.indexOf('http') !== 0;
  }

  /* 1. fetch interception */
  var _origFetch = window.fetch;
  window.fetch = function(input, init) {
    var url = (typeof input === 'string') ? input : ((input && input.url) || '');
    var promise = _origFetch.apply(this, arguments);
    promise.then(function(resp) {
      if (resp.status === 429) {
        var ra = parseInt(resp.headers.get('retry-after') || '0', 10);
        try { window.DiscordBridge.onRateLimited(isNaN(ra) ? 0 : ra * 1000); } catch(e) {}
      }
      var hit = url.indexOf('/auth/register') !== -1 || url.indexOf('/auth/login') !== -1
             || url.indexOf('/auth/verify') !== -1 || url.indexOf('/users/@me') !== -1;
      if (hit && (resp.status === 200 || resp.status === 201)) {
        resp.clone().json().then(function(d) {
          if (d && d.token) report(window.__currentEmail || '', d.token);
        }).catch(function(){});
      }
    }).catch(function(){});
    return promise;
  };

  /* 2. XHR interception */
  var _origOpen = XMLHttpRequest.prototype.open;
  var _origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m, u) { this.__url = u; return _origOpen.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function() {
    this.addEventListener('load', function() {
      if (this.status === 429) { try { window.DiscordBridge.onRateLimited(0); } catch(e) {} }
      var hit = this.__url && (this.__url.indexOf('/auth/register') !== -1 || this.__url.indexOf('/auth/login') !== -1);
      if (hit && (this.status === 200 || this.status === 201)) {
        try { var d = JSON.parse(this.responseText); if (d && d.token) report(window.__currentEmail || '', d.token); } catch(e) {}
      }
    });
    return _origSend.apply(this, arguments);
  };

  /* 3. localStorage polling + write hook */
  function checkLS() {
    try {
      var t = localStorage.getItem('token');
      if (t && t.length > 20) { report(window.__currentEmail || '', t); return; }
      Object.keys(localStorage).forEach(function(k) {
        var v = localStorage.getItem(k);
        if (isTokenLike(v)) report(window.__currentEmail || '', v);
      });
    } catch(e) {}
  }
  var pollId = setInterval(function() { if (window.__tokenReported) { clearInterval(pollId); return; } checkLS(); }, 2000);
  try {
    var _origSI = Storage.prototype.setItem;
    Storage.prototype.setItem = function(k, v) { _origSI.apply(this, arguments); if (isTokenLike(v)) report(window.__currentEmail || '', v); };
  } catch(e) {}

  /* 4. Cookie scan */
  setInterval(function() {
    if (window.__tokenReported) return;
    document.cookie.split(';').forEach(function(c) {
      var p = c.trim().split('=');
      if (p[0] === 'token' && p[1] && p[1].length > 20) report(window.__currentEmail || '', decodeURIComponent(p[1]));
    });
  }, 3000);

  /* 5. CAPTCHA MutationObserver */
  try {
    var captchaObs = new MutationObserver(function(mutations) {
      for (var m of mutations) {
        for (var node of m.addedNodes) {
          if (node.nodeType !== 1) continue;
          var src = node.src || node.getAttribute('src') || '';
          var cls = (node.className || '').toString();
          var id  = node.id || '';
          if ((node.tagName||'').toLowerCase() === 'iframe' && src.indexOf('hcaptcha') !== -1) {
            try { window.DiscordBridge.onCaptchaDetected(document.title, location.href); } catch(e) {}
            captchaObs.disconnect();
          }
          if ((cls + id).toLowerCase().indexOf('captcha') !== -1) {
            try { window.DiscordBridge.onCaptchaDetected(document.title, location.href); } catch(e) {}
            captchaObs.disconnect();
          }
        }
      }
    });
    if (document.body) captchaObs.observe(document.body, { childList: true, subtree: true });
  } catch(e) {}

  /* 6. Already-present CAPTCHA check */
  try {
    var ex = document.querySelector('.h-captcha,[data-hcaptcha-widget-id],iframe[src*="hcaptcha"],[class*="hcaptcha"]');
    if (ex) setTimeout(function() { try { window.DiscordBridge.onCaptchaDetected(document.title, location.href); } catch(e) {} }, 800);
  } catch(e) {}

  try { window.DiscordBridge.onStatus('Harvester v3 armed (' + location.hostname + ')'); } catch(e) {}
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Form fill JS injection
    // ══════════════════════════════════════════════════════════════════════════
    private fun injectFormFill(acc: AccountInfo) {
        val months = listOf("January","February","March","April","May","June",
            "July","August","September","October","November","December")
        val monthName = months[acc.birthMonth - 1]
        fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

        val js = """
(function() {
  window.__currentEmail        = '${esc(acc.email)}';
  window.__tokenReported       = false;
  window.__harvesterActive     = false;
  window.__mouseSimDone        = false;
  window.__captchaWatcherActive = false;
  window.__audioBridgeActive   = false;
  window.__localTokenWatcher   = false;

  var EMAIL        = '${esc(acc.email)}';
  var DISPLAY_NAME = '${esc(acc.displayName)}';
  var USERNAME     = '${esc(acc.username)}';
  var PASSWORD     = '${esc(acc.password)}';
  var MONTH_NUM    = ${acc.birthMonth};
  var MONTH_NAME   = '${esc(monthName)}';
  var DAY          = ${acc.birthDay};
  var YEAR         = ${acc.birthYear};

  function reactSet(el, val) {
    if (!el) return false;
    var nv = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
    if (nv && nv.set) nv.set.call(el, val); else el.value = val;
    el.dispatchEvent(new Event('input',  { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    el.dispatchEvent(new Event('blur',   { bubbles: true }));
    return true;
  }

  function fillFirst(selectors, val) {
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el && el.offsetParent !== null && reactSet(el, val)) return true;
    }
    return false;
  }

  function findDOBControl(placeholder) {
    var byLabel = document.querySelector('[aria-label*="' + placeholder + '" i]');
    if (byLabel) {
      for (var p = byLabel; p && p !== document.body; p = p.parentElement) {
        if (p.getAttribute('role') === 'combobox' || p.getAttribute('aria-haspopup') ||
            (typeof p.className === 'string' && p.className.toLowerCase().indexOf('control') >= 0)) return p;
      }
      return byLabel;
    }
    var combos = Array.from(document.querySelectorAll('[role="combobox"],[aria-haspopup="listbox"],[aria-haspopup="true"]'));
    for (var i = 0; i < combos.length; i++) {
      var txt = combos[i].textContent.trim().toLowerCase();
      if (txt === placeholder.toLowerCase() || txt.indexOf(placeholder.toLowerCase()) === 0) return combos[i];
    }
    return null;
  }

  function tryNativeSelect(index, numericValue, displayText) {
    var selects = document.querySelectorAll('select');
    if (selects.length <= index) return false;
    var sel = selects[index];
    var nsetter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value');
    var values = [String(numericValue), String(displayText)];
    for (var vi = 0; vi < values.length; vi++) {
      if (nsetter && nsetter.set) nsetter.set.call(sel, values[vi]); else sel.value = values[vi];
      if (sel.value === values[vi]) {
        ['input','change'].forEach(function(t) { sel.dispatchEvent(new Event(t, { bubbles:true })); });
        var fk = Object.keys(sel).find(function(k) { return k.startsWith('__reactFiber') || k.startsWith('__reactInternalInstance'); });
        if (fk) {
          var f = sel[fk]; var depth = 0;
          while (f && depth++ < 20) {
            var fp = (f.memoizedProps || f.pendingProps) || {};
            if (typeof fp.onChange === 'function') { try { fp.onChange({ target: sel }); } catch(e) {} break; }
            f = f.return;
          }
        }
        return true;
      }
    }
    return false;
  }

  function clickOpenAndSelect(placeholder, optionText, callback) {
    var ctrl = findDOBControl(placeholder);
    if (!ctrl) { callback(false); return; }
    ctrl.scrollIntoView({ block: 'nearest' });
    try { ctrl.focus(); } catch(e) {}
    ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(e) {
      ctrl.dispatchEvent(new MouseEvent(e, { bubbles:true, cancelable:true }));
    });
    var target = String(optionText).trim();
    var tries = 0;
    var poll = setInterval(function() {
      tries++;
      var opts = Array.from(document.querySelectorAll('[role="option"],[id*="-option-"],[class*="option__"],[class*="option-"]'))
        .filter(function(o) { return o.offsetParent !== null; });
      if (opts.length > 0) {
        var match = null;
        for (var k = 0; k < opts.length; k++) {
          if (opts[k].textContent.trim() === target) { match = opts[k]; break; }
        }
        if (match) {
          clearInterval(poll);
          match.scrollIntoView({ block:'nearest' });
          ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(e) {
            match.dispatchEvent(new MouseEvent(e, { bubbles:true, cancelable:true }));
          });
          setTimeout(function() { callback(true); }, 350);
          return;
        }
      }
      if (tries >= 50) { clearInterval(poll); try { document.body.click(); } catch(e) {} callback(false); }
    }, 100);
  }

  function fillOneDOB(placeholder, numericValue, displayText, selectIndex, callback) {
    clickOpenAndSelect(placeholder, String(displayText), function(ok) {
      if (ok) { callback(true); return; }
      callback(tryNativeSelect(selectIndex, numericValue, displayText));
    });
  }

  function fillAllDOB(onDone) {
    fillOneDOB('Month', MONTH_NUM, MONTH_NAME, 0, function() {
      setTimeout(function() {
        fillOneDOB('Day', DAY, DAY, 1, function() {
          setTimeout(function() {
            fillOneDOB('Year', YEAR, YEAR, 2, function() { if (onDone) onDone(); });
          }, 600);
        });
      }, 600);
    });
  }

  function doFill() {
    fillFirst(['input[name="email"]','input[type="email"]','input[autocomplete="email"]'], EMAIL);
    fillFirst(['input[name="globalName"]','input[name="displayName"]','input[name="global_name"]',
               'input[placeholder*="isplay" i]','input[placeholder*="Global" i]',
               'input[placeholder*="name" i]:not([name="username"]):not([name="email"])'], DISPLAY_NAME);
    fillFirst(['input[name="username"]','input[autocomplete="username"]','input[placeholder*="sername" i]'], USERNAME);
    fillFirst(['input[name="password"]','input[type="password"]','input[autocomplete="new-password"]'], PASSWORD);
    setTimeout(function() {
      fillAllDOB(function() {
        try { window.DiscordBridge.onFormFilled(EMAIL); } catch(e) {}
      });
    }, 700);
  }

  var waitAttempts = 0;
  (function waitForForm() {
    waitAttempts++;
    if (document.querySelector('input[name="email"],input[type="email"]') || waitAttempts >= 20) doFill();
    else setTimeout(waitForForm, 500);
  })();
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
        log("   Form-fill injected…")
    }

    // ── Session clear ─────────────────────────────────────────────────────────
    private fun clearWebViewSession(onDone: () -> Unit = {}) {
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
        WebStorage.getInstance().deleteAllData()
        android.webkit.CookieManager.getInstance().removeAllCookies {
            android.webkit.CookieManager.getInstance().flush()
            onDone()
        }
    }

    // ── Shared finish ─────────────────────────────────────────────────────────
    private fun finishRun() {
        isRunning = false
        currentAccountFillDone = false
        retryCount.clear()
        btnStart.text = getString(R.string.btn_start)
        btnStart.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#57F287"))
        tvStatus.text = "Done: ${successCount}✓ ${failCount}✗"
        log("══════════════════════════════")
        log("[ DONE ] ${successCount} success, ${failCount} failed.")
        log("   Download results with the buttons below.")
        log("══════════════════════════════")
        enableDownloadButtons()
    }

    private fun updateStats() { tvStats.text = "${successCount}✓ ${failCount}✗" }

    private fun enableDownloadButtons() {
        btnDownloadAcc.isEnabled    = AccountManager.getAccFile(filesDir).let { it.exists() && it.length() > 0 }
        btnDownloadTokens.isEnabled = AccountManager.getTokensFile(filesDir).let { it.exists() && it.length() > 0 }
    }

    // ── Groq key test ─────────────────────────────────────────────────────────
    private fun testGroqKey() {
        val key = etGroqKey.text.toString().trim()
        if (key.isBlank()) { toast("Enter Groq API key"); return }
        btnTestGroq.isEnabled = false
        log("[ AI ] Testing key…")
        Thread {
            val r = GroqClient.testKey(key)
            runOnUiThread {
                btnTestGroq.isEnabled = true
                if (r.error.isBlank()) log("[ OK ] Groq key valid")
                else log("[ ERR ] ${r.error}")
            }
        }.start()
    }

    // ── File ops ──────────────────────────────────────────────────────────────
    private fun shareFile(file: File) {
        if (!file.exists() || file.length() == 0L) { toast("File empty or missing"); return }
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share ${file.name}"
        ))
    }

    private fun showFilesDialog() {
        fun preview(t: String) = if (t.length > 500) t.take(500) + "\n…(truncated)" else t
        AlertDialog.Builder(this)
            .setTitle("Saved Files — v3")
            .setMessage(
                "── acc.txt ──\n${preview(AccountManager.readAccFile(filesDir))}\n\n" +
                "── tokens.txt ──\n${preview(AccountManager.readTokensFile(filesDir))}"
            )
            .setPositiveButton("Share acc.txt")    { _, _ -> shareFile(AccountManager.getAccFile(filesDir)) }
            .setNeutralButton("Share tokens.txt")  { _, _ -> shareFile(AccountManager.getTokensFile(filesDir)) }
            .setNegativeButton("Close", null)
            .show()
    }

    // ── Log ───────────────────────────────────────────────────────────────────
    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        tvLog.append("[$ts] $msg\n")
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
