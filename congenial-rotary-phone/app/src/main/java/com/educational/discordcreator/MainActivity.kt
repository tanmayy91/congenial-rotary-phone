package com.educational.discordcreator

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Discord Account Creator v2 — Educational / Research Demo
 *
 * v2 additions:
 *  • Per-account stored FingerprintProfile — same fingerprint used throughout entire session
 *  • LocalHCaptchaSolver integration — 6-strategy local captcha bypass, no API key needed
 *  • Success/Fail stats counter displayed in UI
 *  • Exponential backoff on rate-limit errors
 *  • Improved token harvesting (captures redirect tokens + localStorage mutations)
 *  • 30-signal anti-detection (up from 27), including Intl, font-list and MediaDevices spoofing
 *  • Additional user-agents (Chrome 125–127, Edge 125–127, Firefox 126–127)
 */
@Suppress("SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

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

    // ── State ─────────────────────────────────────────────────────────────────
    private var isRunning = false
    private var accountList: List<AccountInfo> = emptyList()
    private var currentIndex = 0
    private var currentAccountFillDone = false
    private val retryCount = mutableMapOf<Int, Int>()
    private var successCount = 0
    private var failCount = 0
    private var rateLimitBackoffMs = 30_000L

    /** Per-account fingerprint — generated once, reused for all injections of that account */
    private var currentProfile: FingerprintProfile = AntiBanManager.generateProfile()

    // ── JavaScript bridge ─────────────────────────────────────────────────────
    inner class DiscordBridge {

        @JavascriptInterface
        fun onTokenFound(email: String, token: String) {
            runOnUiThread {
                if (token.length < 20) return@runOnUiThread
                log("[ TOKEN ] ${token.take(24)}...")
                val acc = accountList.getOrNull(currentIndex) ?: return@runOnUiThread
                if (acc.status == "done") return@runOnUiThread
                acc.token = token
                acc.status = "done"
                successCount++
                retryCount.remove(currentIndex)
                AccountManager.saveAccount(filesDir, acc)
                log("[ SAVED ] Written to acc.txt and tokens.txt")
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
                log("[ FILL ] All fields filled for $email")
                CaptchaSolver.simulateHumanMouseMovement(webView)
                CaptchaSolver.watchForCaptchaSolvedAndSubmit(webView)
                LocalHCaptchaSolver.injectTokenWatcherAndSubmit(webView) { msg -> log(msg) }
                val delay = AntiBanManager.getHumanDelay()
                webView.postDelayed({
                    log("   Clicking Register button...")
                    CaptchaSolver.clickRegisterButton(webView)
                }, delay)
                log("   If CAPTCHA appears: tap Local Solver or Auto CAPTCHA, then Click Reg.")
            }
        }

        @JavascriptInterface
        fun onRegistrationResponse(email: String, token: String, error: String) {
            runOnUiThread {
                if (token.isNotEmpty()) {
                    log("[ OK ] Registration success for $email")
                    onTokenFound(email, token)
                } else {
                    log("[ ERR ] Registration failed: $error")
                    handleAccountError()
                }
            }
        }

        @JavascriptInterface
        fun onCaptchaDetected(pageTitle: String, pageUrl: String) {
            runOnUiThread {
                log("[ CAPTCHA ] Challenge detected on page")
                // Run full local solver stack first
                LocalHCaptchaSolver.runAllStrategies(webView) { msg -> log(msg) }
                CaptchaSolver.simulateHumanMouseMovement(webView)
                webView.postDelayed({ CaptchaSolver.tryAutoSolve(webView) }, 800)
                CaptchaSolver.watchForCaptchaSolvedAndSubmit(webView)

                val groqKey = etGroqKey.text.toString().trim()
                if (groqKey.isNotBlank()) {
                    log("   Asking Groq for a hint...")
                    Thread {
                        val hint = GroqClient.captchaHint(groqKey, pageTitle, pageUrl)
                        runOnUiThread { log("   Hint: $hint") }
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
                        // Exponential backoff: double each time, cap at 5 min
                        val delay = rateLimitBackoffMs
                        rateLimitBackoffMs = minOf(rateLimitBackoffMs * 2, 300_000L)
                        delay
                    }
                }
                log("[ RATE ] Rate-limited — waiting ${wait / 1000}s (backoff)...")
                webView.postDelayed({
                    if (isRunning) {
                        rateLimitBackoffMs = 30_000L // reset after successful wait
                        log("   Retrying account ${currentIndex + 1}...")
                        currentAccountFillDone = false
                        webView.loadUrl("https://discord.com/register")
                    }
                }, wait)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupWebView()
        setupButtons()
        LocalHCaptchaSolver.refreshAccessibilityCookies()
        webView.loadUrl("https://discord.com/register")
        log("[ v2 ] Discord Creator v2 ready — early-signal injection active")
        log("[ TIP ] Tap 'Local Solver' when a CAPTCHA appears for advanced bypass")
        log("──────────────────────────────")
    }

    private fun bindViews() {
        webView           = findViewById(R.id.webView)
        etUrl             = findViewById(R.id.etUrl)
        btnGo             = findViewById(R.id.btnGo)
        tvLog             = findViewById(R.id.tvLog)
        tvStatus          = findViewById(R.id.tvStatus)
        tvStats           = findViewById(R.id.tvStats)
        scrollLog         = findViewById(R.id.scrollLog)
        etGmail           = findViewById(R.id.etGmail)
        etPassword        = findViewById(R.id.etPassword)
        etCount           = findViewById(R.id.etCount)
        etGroqKey         = findViewById(R.id.etGroqKey)
        btnStart          = findViewById(R.id.btnStart)
        btnTestGroq       = findViewById(R.id.btnTestGroq)
        btnClearLog       = findViewById(R.id.btnClearLog)
        btnDownloadAcc    = findViewById(R.id.btnDownloadAcc)
        btnDownloadTokens = findViewById(R.id.btnDownloadTokens)
        btnOpenFile       = findViewById(R.id.btnOpenFile)
        btnSolveCaptcha   = findViewById(R.id.btnSolveCaptcha)
        btnLocalSolve     = findViewById(R.id.btnLocalSolve)
        btnAccessibility  = findViewById(R.id.btnAccessibility)
        btnClickRegister  = findViewById(R.id.btnClickRegister)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled              = true
            domStorageEnabled              = true
            databaseEnabled                = true
            setSupportMultipleWindows(false)
            userAgentString                = AntiBanManager.getRandomUserAgent()
            mixedContentMode               = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort                = true
            loadWithOverviewMode           = true
            cacheMode                      = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls            = false
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess                = false
            allowContentAccess             = false
            javaScriptCanOpenWindowsAutomatically = false
        }
        android.webkit.WebView.setWebContentsDebuggingEnabled(false)

        webView.addJavascriptInterface(DiscordBridge(), "DiscordBridge")

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url ?: return
                etUrl.setText(url)
                log("[ NAV ] ${url.take(70)}")
                // Inject BEFORE Discord's React bundle executes — this is what makes the
                // registration form render correctly instead of a blank/branded stub.
                if (url.contains("discord.com")) {
                    AntiBanManager.injectEarlySignals(webView, currentProfile)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url ?: return
                log("[ PAGE ] ${url.take(70)}")

                if (url.contains("discord.com")) {
                    // Full 30-signal injection after DOM is ready
                    AntiBanManager.injectAntiDetection(webView, currentProfile)
                    injectTokenHarvester()
                }

                if (url.contains("discord.com/verify") ||
                    url.contains("discord.com/channels") ||
                    url.contains("discord.com/@me")) {
                    log("[ EMAIL ] Verification/success page — polling for token...")
                    webView.postDelayed({ injectTokenHarvester() }, 500)
                    webView.postDelayed({ injectTokenHarvester() }, 2000)
                    webView.postDelayed({ injectTokenHarvester() }, 5000)
                }

                if (isRunning && url.contains("discord.com/register") && !currentAccountFillDone) {
                    val acc = accountList.getOrNull(currentIndex) ?: return
                    val delay = AntiBanManager.getPageLoadDelay()
                    webView.postDelayed({ injectFormFill(acc) }, delay)
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                view: WebView?, errorCode: Int, description: String?, failingUrl: String?
            ) = log("[ ERROR ] WebView: $description ($errorCode)")
        }
    }

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
                val finalUrl = if (url.startsWith("http")) url else "https://$url"
                webView.loadUrl(finalUrl)
            }
        }
        etUrl.setOnEditorActionListener { _, _, _ -> btnGo.performClick(); true }

        btnTestGroq.setOnClickListener { testGroqKey() }
        btnClearLog.setOnClickListener { tvLog.text = "[Log cleared]\n" }

        btnDownloadAcc.setOnClickListener { shareFile(AccountManager.getAccFile(filesDir)) }
        btnDownloadTokens.setOnClickListener { shareFile(AccountManager.getTokensFile(filesDir)) }
        btnOpenFile.setOnClickListener { showFilesDialog() }

        btnSolveCaptcha.setOnClickListener {
            log("[ CAPTCHA ] Standard auto-solve attempt...")
            CaptchaSolver.tryAutoSolve(webView)
            CaptchaSolver.watchForCaptchaSolvedAndSubmit(webView)
        }

        btnLocalSolve.setOnClickListener {
            log("[ LOCAL ] Launching advanced local solver (6 strategies)...")
            LocalHCaptchaSolver.runAllStrategies(webView) { msg -> log(msg) }
            webView.postDelayed({
                LocalHCaptchaSolver.injectTokenWatcherAndSubmit(webView) { msg -> log(msg) }
            }, 2500)
        }

        btnAccessibility.setOnClickListener {
            log("[ ACCESS ] Opening hCaptcha Accessibility...")
            log("   Enter your email, confirm it, then return to Discord.")
            CaptchaSolver.openAccessibilitySetup(webView)
        }

        btnClickRegister.setOnClickListener {
            log("[ REG ] Clicking submit button...")
            CaptchaSolver.clickRegisterButton(webView)
        }
    }

    // ── Groq key test ─────────────────────────────────────────────────────────
    private fun testGroqKey() {
        val key = etGroqKey.text.toString().trim()
        if (key.isBlank()) { toast("Enter a Groq API key first"); return }
        btnTestGroq.isEnabled = false
        log("[ AI ] Testing Groq key...")
        Thread {
            val result = GroqClient.testKey(key)
            runOnUiThread {
                btnTestGroq.isEnabled = true
                if (result.error.isBlank()) {
                    log("[ OK ] Groq key valid — AI name generation enabled")
                } else {
                    log("[ ERR ] Groq test failed: ${result.error}")
                }
            }
        }.start()
    }

    // ── Account creation flow ─────────────────────────────────────────────────
    private fun startCreation() {
        val gmail    = etGmail.text.toString().trim()
        val password = etPassword.text.toString()
        val count    = etCount.text.toString().trim().toIntOrNull() ?: 0
        val groqKey  = etGroqKey.text.toString().trim()

        if (gmail.isEmpty() || !gmail.contains('@')) { toast("Invalid Gmail address"); return }
        if (password.length < 8)                      { toast("Password must be ≥ 8 chars"); return }
        if (count < 1 || count > 50)                  { toast("Count must be 1–50"); return }

        currentIndex          = 0
        isRunning             = true
        successCount          = 0
        failCount             = 0
        rateLimitBackoffMs    = 30_000L
        AccountManager.clearFiles(filesDir)
        updateStats()

        btnStart.text = getString(R.string.btn_stop)
        btnStart.backgroundTintList =
            ContextCompat.getColorStateList(this, android.R.color.holo_red_light)
        tvStatus.text = "Starting..."
        btnDownloadAcc.isEnabled    = false
        btnDownloadTokens.isEnabled = false

        log("══════════════════════════════")
        log("[ START ] Creating $count account(s)")
        log("   Base email: $gmail")
        log("══════════════════════════════")

        if (groqKey.isNotBlank()) {
            log("[ AI ] Fetching names from Groq...")
            Thread {
                val usernames    = GroqClient.generateUsernames(groqKey, count)
                val displayNames = GroqClient.generateDisplayNames(groqKey, count)
                runOnUiThread {
                    if (usernames.isNotEmpty()) log("   Groq provided ${usernames.size} username(s)")
                    else log("   Groq unavailable — using local names")
                    accountList = AccountManager.buildAccountList(gmail, password, count, usernames, displayNames)
                    createNextAccount()
                }
            }.start()
        } else {
            accountList = AccountManager.buildAccountList(gmail, password, count)
            createNextAccount()
        }
    }

    private fun stopCreation() {
        isRunning = false
        currentAccountFillDone = false
        retryCount.clear()
        rateLimitBackoffMs = 30_000L
        btnStart.text = getString(R.string.btn_start)
        btnStart.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#57F287"))
        tvStatus.text = "Stopped"
        log("[ STOP ] Stopped at ${currentIndex}/${accountList.size}. ${successCount}✓ ${failCount}✗")
        enableDownloadButtons()
    }

    private fun createNextAccount() {
        if (!isRunning || currentIndex >= accountList.size) {
            isRunning = false
            currentAccountFillDone = false
            retryCount.clear()
            btnStart.text = getString(R.string.btn_start)
            btnStart.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#57F287"))
            tvStatus.text = "Done: ${successCount}✓ ${failCount}✗"
            log("══════════════════════════════")
            log("[ DONE ] Finished. ${successCount} success, ${failCount} failed.")
            log("   Use the download buttons to save results.")
            log("══════════════════════════════")
            enableDownloadButtons()
            return
        }

        currentAccountFillDone = false

        // Generate a fresh fingerprint profile for this account
        currentProfile = AntiBanManager.generateProfile()
        webView.settings.userAgentString = currentProfile.userAgent

        val acc = accountList[currentIndex]
        tvStatus.text = "Account ${currentIndex + 1}/${accountList.size}"
        log("")
        log("[${currentIndex + 1}/${accountList.size}] Starting account")
        log("   Email       : ${acc.email}")
        log("   Display name: ${acc.displayName}")
        log("   Username    : ${acc.username}")
        log("   DOB         : ${acc.birthMonth}/${acc.birthDay}/${acc.birthYear}")
        log("   UA          : ${currentProfile.userAgent.take(55)}...")

        // Clear session; load URL only AFTER cookies are fully removed and reinjected
        // so hCaptcha accessibility cookies are always present when the page starts.
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
        log("   Waiting ${delay / 1000}s before next account...")
        webView.postDelayed({ createNextAccount() }, delay)
    }

    private fun handleAccountError() {
        val acc = accountList.getOrNull(currentIndex) ?: run { advanceToNextAccount(); return }
        val tries = retryCount.getOrDefault(currentIndex, 0)
        if (tries < 2) {
            retryCount[currentIndex] = tries + 1
            log("[ WARN ] Account ${currentIndex + 1} failed — retry ${tries + 1}/2...")
            acc.status = "pending"
            currentAccountFillDone = false
            val delay = AntiBanManager.getAccountDelay()
            webView.postDelayed({
                if (isRunning) webView.loadUrl("https://discord.com/register")
            }, delay)
        } else {
            log("[ SKIP ] Account ${currentIndex + 1} skipped after 2 retries.")
            acc.status = "error"
            failCount++
            retryCount.remove(currentIndex)
            updateStats()
            advanceToNextAccount()
        }
    }

    private fun updateStats() {
        tvStats.text = "${successCount}✓ ${failCount}✗"
    }

    private fun enableDownloadButtons() {
        val accFile = AccountManager.getAccFile(filesDir)
        btnDownloadAcc.isEnabled    = accFile.exists() && accFile.length() > 0
        btnDownloadTokens.isEnabled =
            AccountManager.getTokensFile(filesDir).let { it.exists() && it.length() > 0 }
    }

    // ── JavaScript injection — Token Harvester ────────────────────────────────
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
        var raRaw = resp.headers.get('retry-after') || '0';
        var raVal = parseInt(raRaw, 10);
        var retryAfter = isNaN(raVal) ? 0 : raVal * 1000;
        try { window.DiscordBridge.onRateLimited(retryAfter); } catch(e) {}
      }
      var hit = url.indexOf('/auth/register') !== -1 ||
                url.indexOf('/auth/login')    !== -1 ||
                url.indexOf('/auth/verify')   !== -1 ||
                url.indexOf('/users/@me')     !== -1;
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
  XMLHttpRequest.prototype.open = function(m, u) {
    this.__url = u; return _origOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function() {
    this.addEventListener('load', function() {
      if (this.status === 429) {
        try { window.DiscordBridge.onRateLimited(0); } catch(e) {}
      }
      var hit = this.__url &&
        (this.__url.indexOf('/auth/register') !== -1 ||
         this.__url.indexOf('/auth/login')    !== -1);
      if (hit && (this.status === 200 || this.status === 201)) {
        try {
          var d = JSON.parse(this.responseText);
          if (d && d.token) report(window.__currentEmail || '', d.token);
        } catch(e) {}
      }
    });
    return _origSend.apply(this, arguments);
  };

  /* 3. localStorage polling + write-hook */
  function checkLocalStorage() {
    try {
      var t = localStorage.getItem('token');
      if (t && t.length > 20) { report(window.__currentEmail || '', t); return; }
      var keys = Object.keys(localStorage);
      for (var i = 0; i < keys.length; i++) {
        var v = localStorage.getItem(keys[i]);
        if (isTokenLike(v)) { report(window.__currentEmail || '', v); return; }
      }
    } catch(e) {}
  }

  var pollHandle = setInterval(function() {
    if (window.__tokenReported) { clearInterval(pollHandle); return; }
    checkLocalStorage();
  }, 2000);

  try {
    var _origSetItem = Storage.prototype.setItem;
    Storage.prototype.setItem = function(key, value) {
      _origSetItem.apply(this, arguments);
      if (isTokenLike(value)) report(window.__currentEmail || '', value);
    };
  } catch(e) {}

  /* 4. Cookie scan */
  try {
    var cookieHandle = setInterval(function() {
      if (window.__tokenReported) { clearInterval(cookieHandle); return; }
      var cookies = document.cookie.split(';');
      for (var c = 0; c < cookies.length; c++) {
        var parts = cookies[c].trim().split('=');
        if (parts[0] === 'token' && parts[1] && parts[1].length > 20) {
          report(window.__currentEmail || '', decodeURIComponent(parts[1]));
          clearInterval(cookieHandle);
          return;
        }
      }
    }, 3000);
  } catch(e) {}

  /* 5. CAPTCHA detection via MutationObserver */
  try {
    var captchaObserver = new MutationObserver(function(mutations) {
      for (var m of mutations) {
        for (var node of m.addedNodes) {
          if (node.nodeType !== 1) continue;
          var tag = (node.tagName || '').toLowerCase();
          var src = node.src || node.getAttribute('src') || '';
          var cls = (node.className || '').toString();
          var id  = node.id || '';
          if (tag === 'iframe' && (src.indexOf('hcaptcha') !== -1 || src.indexOf('captcha') !== -1)) {
            try { window.DiscordBridge.onCaptchaDetected(document.title, location.href); } catch(e) {}
            captchaObserver.disconnect();
          }
          if ((cls + id).toLowerCase().indexOf('captcha') !== -1) {
            try { window.DiscordBridge.onCaptchaDetected(document.title, location.href); } catch(e) {}
            captchaObserver.disconnect();
          }
        }
      }
    });
    if (document.body) {
      captchaObserver.observe(document.body, { childList: true, subtree: true });
    }
  } catch(e) {}

  /* 6. Check for already-present CAPTCHA on page load */
  try {
    var existingCaptcha = document.querySelector(
      '.h-captcha, [data-hcaptcha-widget-id], iframe[src*="hcaptcha"], [class*="hcaptcha"]'
    );
    if (existingCaptcha) {
      setTimeout(function() {
        try { window.DiscordBridge.onCaptchaDetected(document.title, location.href); } catch(e) {}
      }, 800);
    }
  } catch(e) {}

  try { window.DiscordBridge.onStatus('Harvester v2 armed (' + location.hostname + ')'); } catch(e) {}
})();
"""
        webView.evaluateJavascript(js, null)
    }

    // ── JavaScript injection — Form Fill ─────────────────────────────────────
    private fun injectFormFill(acc: AccountInfo) {
        val months = listOf(
            "January","February","March","April","May","June",
            "July","August","September","October","November","December"
        )
        val monthName = months[acc.birthMonth - 1]
        fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

        val js = """
(function() {
  window.__currentEmail  = '${esc(acc.email)}';
  window.__tokenReported = false;
  window.__harvesterActive = false;
  window.__mouseSimDone    = false;
  window.__captchaWatcherActive = false;
  window.__audioBridgeActive    = false;
  window.__localTokenWatcher    = false;

  var EMAIL        = '${esc(acc.email)}';
  var DISPLAY_NAME = '${esc(acc.displayName)}';
  var USERNAME     = '${esc(acc.username)}';
  var PASSWORD     = '${esc(acc.password)}';
  var MONTH_NUM    = ${acc.birthMonth};
  var MONTH_NAME   = '${esc(monthName)}';
  var DAY          = ${acc.birthDay};
  var YEAR         = ${acc.birthYear};

  /* React-compatible input setter */
  function reactSet(el, val) {
    if (!el) return false;
    var nativeInputValueSetter = Object.getOwnPropertyDescriptor(
      window.HTMLInputElement.prototype, 'value'
    );
    if (nativeInputValueSetter && nativeInputValueSetter.set) {
      nativeInputValueSetter.set.call(el, val);
    } else {
      el.value = val;
    }
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
    var ph = placeholder.toLowerCase();
    var byLabel = document.querySelector('[aria-label*="' + placeholder + '" i]');
    if (byLabel) {
      for (var p = byLabel; p && p !== document.body; p = p.parentElement) {
        var role = p.getAttribute('role');
        var hpp  = p.getAttribute('aria-haspopup');
        var cls  = typeof p.className === 'string' ? p.className.toLowerCase() : '';
        if (role === 'combobox' || hpp || cls.indexOf('control') >= 0) return p;
      }
      return byLabel.parentElement || byLabel;
    }
    var combos = Array.from(document.querySelectorAll('[role="combobox"],[aria-haspopup="listbox"],[aria-haspopup="true"]'));
    for (var i = 0; i < combos.length; i++) {
      var txt = combos[i].textContent.trim().toLowerCase();
      if (txt === ph || txt.indexOf(ph) === 0) return combos[i];
    }
    var tw = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
    while (tw.nextNode()) {
      if (tw.currentNode.textContent.trim().toLowerCase() !== ph) continue;
      var p2 = tw.currentNode.parentElement;
      for (var j = 0; j < 8 && p2; j++, p2 = p2.parentElement) {
        var cls2 = typeof p2.className === 'string' ? p2.className.toLowerCase() : '';
        if (p2.getAttribute('aria-haspopup') || p2.getAttribute('role') === 'combobox' ||
            cls2.indexOf('control') >= 0 || cls2.indexOf('container') >= 0) return p2;
      }
      return tw.currentNode.parentElement;
    }
    return null;
  }

  function tryNativeSelect(index, numericValue, displayText) {
    var selects = document.querySelectorAll('select');
    if (selects.length <= index) return false;
    var sel = selects[index];
    var nsetter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value');
    var valuesToTry = [String(numericValue), String(displayText)];
    var ok = false;
    for (var vi = 0; vi < valuesToTry.length; vi++) {
      if (nsetter && nsetter.set) nsetter.set.call(sel, valuesToTry[vi]);
      else sel.value = valuesToTry[vi];
      if (sel.value === valuesToTry[vi]) { ok = true; break; }
    }
    if (ok) {
      ['input', 'change'].forEach(function(t) {
        sel.dispatchEvent(new Event(t, { bubbles: true }));
      });
      var fk = Object.keys(sel).find(function(k) {
        return k.startsWith('__reactFiber') || k.startsWith('__reactInternalInstance');
      });
      if (fk) {
        var f = sel[fk]; var depth = 0;
        while (f && depth++ < 20) {
          var fp = (f.memoizedProps || f.pendingProps) || {};
          if (typeof fp.onChange === 'function') {
            try { fp.onChange({ target: sel }); } catch(e) {} break;
          }
          f = f.return;
        }
      }
    }
    return ok;
  }

  function clickOpenAndSelect(placeholder, optionText, callback) {
    var ctrl = findDOBControl(placeholder);
    if (!ctrl) { callback(false); return; }
    ctrl.scrollIntoView({ block: 'nearest' });
    var evOpts = { bubbles: true, cancelable: true };
    try { ctrl.focus(); } catch(e) {}
    ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function(e) {
      ctrl.dispatchEvent(new MouseEvent(e, evOpts));
    });
    var target = String(optionText).trim();
    var tries = 0;
    var poll = setInterval(function() {
      tries++;
      var opts = Array.from(document.querySelectorAll(
        '[role="option"], [id*="-option-"], [class*="option__"], [class*="option-"]'
      )).filter(function(o) { return o.offsetParent !== null; });
      if (opts.length > 0) {
        var match = null;
        for (var k = 0; k < opts.length; k++) {
          if (opts[k].textContent.trim() === target) { match = opts[k]; break; }
        }
        if (match) {
          clearInterval(poll);
          match.scrollIntoView({ block: 'nearest' });
          ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function(e) {
            match.dispatchEvent(new MouseEvent(e, evOpts));
          });
          setTimeout(function() { callback(true); }, 350);
          return;
        }
      }
      if (tries >= 50) {
        clearInterval(poll);
        try { document.body.click(); } catch(e) {}
        callback(false);
      }
    }, 100);
  }

  function fillOneDOB(placeholder, numericValue, displayText, selectIndex, callback) {
    clickOpenAndSelect(placeholder, String(displayText), function(ok) {
      if (ok) { callback(true); return; }
      var nok = tryNativeSelect(selectIndex, numericValue, displayText);
      callback(nok);
    });
  }

  function fillAllDOB(onDone) {
    fillOneDOB('Month', MONTH_NUM, MONTH_NAME, 0, function() {
      setTimeout(function() {
        fillOneDOB('Day', DAY, DAY, 1, function() {
          setTimeout(function() {
            fillOneDOB('Year', YEAR, YEAR, 2, function() {
              if (onDone) onDone();
            });
          }, 600);
        });
      }, 600);
    });
  }

  function doFill() {
    fillFirst(['input[name="email"]', 'input[type="email"]', 'input[autocomplete="email"]'], EMAIL);
    fillFirst([
      'input[name="globalName"]', 'input[name="displayName"]', 'input[name="global_name"]',
      'input[placeholder*="isplay" i]', 'input[placeholder*="Global" i]',
      'input[placeholder*="name" i]:not([name="username"]):not([name="email"])'
    ], DISPLAY_NAME);
    fillFirst([
      'input[name="username"]', 'input[autocomplete="username"]',
      'input[placeholder*="sername" i]'
    ], USERNAME);
    fillFirst([
      'input[name="password"]', 'input[type="password"]',
      'input[autocomplete="new-password"]'
    ], PASSWORD);

    setTimeout(function() {
      fillAllDOB(function() {
        try { window.DiscordBridge.onFormFilled(EMAIL); } catch(e) {}
      });
    }, 700);
  }

  var waitAttempts = 0;
  (function waitForForm() {
    waitAttempts++;
    var emailEl = document.querySelector('input[name="email"], input[type="email"]');
    if (emailEl || waitAttempts >= 20) {
      doFill();
    } else {
      setTimeout(waitForForm, 500);
    }
  })();
})();
"""
        webView.evaluateJavascript(js, null)
        log("   Form-fill script injected...")
    }

    // ── Session isolation ─────────────────────────────────────────────────────
    /**
     * Clears the WebView session then runs [onDone] once cookies are actually removed.
     * Using the callback form of removeAllCookies avoids a race where we set hCaptcha
     * accessibility cookies before the old ones are fully wiped.
     */
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

    // ── File operations ───────────────────────────────────────────────────────
    private fun shareFile(file: File) {
        if (!file.exists() || file.length() == 0L) { toast("File is empty or missing"); return }
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share ${file.name}"
            )
        )
    }

    private fun showFilesDialog() {
        fun preview(text: String, limit: Int = 500) =
            if (text.length > limit) text.take(limit) + "\n…(truncated)" else text

        AlertDialog.Builder(this)
            .setTitle("Saved Files (v2)")
            .setMessage(
                "── acc.txt ──\n${preview(AccountManager.readAccFile(filesDir))}\n\n" +
                "── tokens.txt ──\n${preview(AccountManager.readTokensFile(filesDir))}"
            )
            .setPositiveButton("Share acc.txt")   { _, _ -> shareFile(AccountManager.getAccFile(filesDir)) }
            .setNeutralButton("Share tokens.txt") { _, _ -> shareFile(AccountManager.getTokensFile(filesDir)) }
            .setNegativeButton("Close", null)
            .show()
    }

    // ── Utility ───────────────────────────────────────────────────────────────
    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        tvLog.append("[$ts] $msg\n")
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
