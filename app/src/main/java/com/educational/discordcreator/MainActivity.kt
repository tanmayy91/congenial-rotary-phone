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
 * Discord Account Creator — Educational / Research Demo
 *
 * Demonstrates: WebView automation, JavaScript bridge injection, React-controlled
 * form interaction, token interception, and Groq AI-assisted name generation.
 * FOR EDUCATIONAL PURPOSES ONLY.
 */
@Suppress("SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    // ── UI refs ───────────────────────────────────────────────────────────────
    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnGo: Button
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
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
    private lateinit var btnAccessibility: Button
    private lateinit var btnClickRegister: Button

    // ── State ─────────────────────────────────────────────────────────────────
    private var isRunning = false
    private var accountList: List<AccountInfo> = emptyList()
    private var currentIndex = 0
    /** Prevents re-injecting form fill after CAPTCHA has been shown for this account */
    private var currentAccountFillDone = false
    /** Retry counter per account index — max 2 retries before skipping */
    private val retryCount = mutableMapOf<Int, Int>()

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
                retryCount.remove(currentIndex)
                AccountManager.saveAccount(filesDir, acc)
                log("[ SAVED ] Written to acc.txt and tokens.txt")
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
                val delay = AntiBanManager.getHumanDelay()
                webView.postDelayed({
                    log("   Clicking Register button...")
                    CaptchaSolver.clickRegisterButton(webView)
                }, delay)
                log("   If CAPTCHA appears: tap Auto CAPTCHA or solve manually, then click Reg.")
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

        /** Called by JS when the CAPTCHA appears on screen */
        @JavascriptInterface
        fun onCaptchaDetected(pageTitle: String, pageUrl: String) {
            runOnUiThread {
                log("[ CAPTCHA ] Challenge detected")
                CaptchaSolver.simulateHumanMouseMovement(webView)
                CaptchaSolver.tryAutoSolve(webView)
                CaptchaSolver.watchForCaptchaSolvedAndSubmit(webView)

                val groqKey = etGroqKey.text.toString().trim()
                if (groqKey.isNotBlank()) {
                    log("   Asking Groq for a hint...")
                    Thread {
                        val hint = GroqClient.captchaHint(groqKey, pageTitle, pageUrl)
                        runOnUiThread { log("   Hint: $hint") }
                    }.start()
                } else {
                    log("   Solve the CAPTCHA manually, then tap Click Reg. or it auto-submits.")
                }
            }
        }

        /** Called by JS when Discord returns a 429 rate-limit response */
        @JavascriptInterface
        fun onRateLimited(retryAfterMs: Int) {
            runOnUiThread {
                val wait = if (retryAfterMs > 0) retryAfterMs.toLong()
                           else AntiBanManager.getRateLimitDelay()
                log("[ RATE ] Rate-limited — waiting ${wait / 1000}s before retry...")
                webView.postDelayed({
                    if (isRunning) {
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
        webView.loadUrl("https://discord.com/register")
        log("[ NAV ] discord.com/register loaded — ready")
        log("──────────────────────────────")
    }

    // ── View binding ──────────────────────────────────────────────────────────
    private fun bindViews() {
        webView          = findViewById(R.id.webView)
        etUrl            = findViewById(R.id.etUrl)
        btnGo            = findViewById(R.id.btnGo)
        tvLog            = findViewById(R.id.tvLog)
        tvStatus         = findViewById(R.id.tvStatus)
        scrollLog        = findViewById(R.id.scrollLog)
        etGmail          = findViewById(R.id.etGmail)
        etPassword       = findViewById(R.id.etPassword)
        etCount          = findViewById(R.id.etCount)
        etGroqKey        = findViewById(R.id.etGroqKey)
        btnStart         = findViewById(R.id.btnStart)
        btnTestGroq      = findViewById(R.id.btnTestGroq)
        btnClearLog      = findViewById(R.id.btnClearLog)
        btnDownloadAcc   = findViewById(R.id.btnDownloadAcc)
        btnDownloadTokens = findViewById(R.id.btnDownloadTokens)
        btnOpenFile      = findViewById(R.id.btnOpenFile)
        btnSolveCaptcha  = findViewById(R.id.btnSolveCaptcha)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnClickRegister = findViewById(R.id.btnClickRegister)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled        = true
            domStorageEnabled        = true
            databaseEnabled          = true
            setSupportMultipleWindows(false)
            // Start with a random desktop UA; rotated again per account in createNextAccount()
            userAgentString          = AntiBanManager.getRandomUserAgent()
            mixedContentMode         = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Request and render the desktop version of every site
            useWideViewPort          = true
            loadWithOverviewMode     = true
        }

        webView.addJavascriptInterface(DiscordBridge(), "DiscordBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url ?: return

                etUrl.setText(url)

                log("[ PAGE ] ${url.take(70)}")

                if (url.contains("discord.com")) {
                    AntiBanManager.injectAntiDetection(webView)
                    injectTokenHarvester()
                }

                if (url.contains("discord.com/verify") ||
                    url.contains("discord.com/channels") ||
                    url.contains("discord.com/@me")) {
                    log("[ EMAIL ] Verification page detected — polling for token...")
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
            ) = log("[ ERROR ] WebView: $description")
        }
    }

    private fun setupButtons() {
        // Handle back navigation via WebView history using the modern API
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        btnStart.setOnClickListener {
            if (isRunning) stopCreation() else startCreation()
        }

        btnGo.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isNotBlank()) {
                val finalUrl = if (url.startsWith("http")) url else "https://$url"
                webView.loadUrl(finalUrl)
                log("[ NAV ] $finalUrl")
            }
        }
        etUrl.setOnEditorActionListener { _, _, _ -> btnGo.performClick(); true }

        btnTestGroq.setOnClickListener { testGroqKey() }

        btnClearLog.setOnClickListener { tvLog.text = "[Log cleared]\n" }

        btnDownloadAcc.setOnClickListener { shareFile(AccountManager.getAccFile(filesDir)) }
        btnDownloadTokens.setOnClickListener { shareFile(AccountManager.getTokensFile(filesDir)) }
        btnOpenFile.setOnClickListener { showFilesDialog() }

        // Free CAPTCHA solvers — no API key required
        btnSolveCaptcha.setOnClickListener {
            log("[ CAPTCHA ] Auto-solve attempt...")
            CaptchaSolver.tryAutoSolve(webView)
            CaptchaSolver.watchForCaptchaSolvedAndSubmit(webView)
        }
        btnAccessibility.setOnClickListener {
            log("[ ACCESS ] Opening hCaptcha Accessibility...")
            log("   Enter your email, confirm it, then go back to Discord.")
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

        currentIndex = 0
        isRunning    = true
        AccountManager.clearFiles(filesDir)

        btnStart.text = getString(R.string.btn_stop)
        btnStart.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_light)
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
                    if (usernames.isNotEmpty()) {
                        log("   Groq provided ${usernames.size} username(s)")
                    } else {
                        log("   Groq unavailable — using local names")
                    }
                    accountList = AccountManager.buildAccountList(
                        gmail, password, count, usernames, displayNames
                    )
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
        btnStart.text = getString(R.string.btn_start)
        btnStart.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#57F287"))
        tvStatus.text = "Stopped"
        log("[ STOP ] ${currentIndex}/${accountList.size} done")
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
            tvStatus.text = "Done: ${currentIndex}/${accountList.size}"
            log("══════════════════════════════")
            log("[ DONE ] $currentIndex account(s) processed.")
            log("   Use the download buttons to save results.")
            log("══════════════════════════════")
            enableDownloadButtons()
            return
        }

        // Reset fill-done flag for this fresh account attempt
        currentAccountFillDone = false

        // Clear all browser state so each account has a clean session
        clearWebViewSession()

        // Rotate user agent for each account to vary the fingerprint
        webView.settings.userAgentString = AntiBanManager.getRandomUserAgent()

        val acc = accountList[currentIndex]
        tvStatus.text = "Account ${currentIndex + 1}/${accountList.size}"
        log("")
        log("[${currentIndex + 1}/${accountList.size}] Starting account")
        log("   Email       : ${acc.email}")
        log("   Display name: ${acc.displayName}")
        log("   Username    : ${acc.username}")
        log("   DOB         : ${acc.birthMonth}/${acc.birthDay}/${acc.birthYear}")
        webView.loadUrl("https://discord.com/register")
    }

    private fun advanceToNextAccount() {
        retryCount.remove(currentIndex)
        currentIndex++
        val delay = AntiBanManager.getAccountDelay()
        log("   Waiting ${delay / 1000}s before next account...")
        webView.postDelayed({ createNextAccount() }, delay)
    }

    /** Handle a failed account: retry up to 2 times, then skip. */
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
            retryCount.remove(currentIndex)
            advanceToNextAccount()
        }
    }

    private fun enableDownloadButtons() {
        val accFile = AccountManager.getAccFile(filesDir)
        btnDownloadAcc.isEnabled    = accFile.exists() && accFile.length() > 0
        btnDownloadTokens.isEnabled = AccountManager.getTokensFile(filesDir).let { it.exists() && it.length() > 0 }
    }

    // ── JavaScript injection — Token Harvester ────────────────────────────────
    /**
     * Injected on every Discord page. Intercepts:
     *  1. window.fetch  → captures token from /register or /login API responses;
     *                     also detects 429 rate-limit responses
     *  2. XMLHttpRequest → same, as fallback
     *  3. localStorage  → polls + write-hook (catches post-verification tokens)
     *  4. cookie scan   → catches tokens stored as document.cookie
     *  5. hCaptcha      → notifies Android when CAPTCHA appears
     */
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
    /* Token-like: long string, no spaces, no JSON braces/brackets, not a URL */
    return typeof v === 'string' && v.length > 50
        && v.indexOf(' ') === -1 && v.indexOf('{') === -1 && v.indexOf('}') === -1
        && v.indexOf('[') === -1 && v.indexOf('http') !== 0;
  }

  /* 1. fetch interception ─────────────────────────────────────────────────── */
  var _origFetch = window.fetch;
  window.fetch = function(input, init) {
    var url = (typeof input === 'string') ? input : ((input && input.url) || '');
    var promise = _origFetch.apply(this, arguments);
    promise.then(function(resp) {
      /* 429 rate-limit detection */
      if (resp.status === 429) {
        var raRaw = resp.headers.get('retry-after') || '0';
        var raVal = parseInt(raRaw, 10);
        var retryAfter = isNaN(raVal) ? 0 : raVal * 1000;
        try { window.DiscordBridge.onRateLimited(retryAfter); } catch(e) {}
      }
      var hit = url.indexOf('/auth/register') !== -1 ||
                url.indexOf('/auth/login')    !== -1 ||
                url.indexOf('/auth/verify')   !== -1;
      if (hit && resp.status === 200) {
        resp.clone().json().then(function(d) {
          if (d && d.token) report(window.__currentEmail || '', d.token);
          /* Some responses nest token inside user object */
          if (d && d.user_settings && d.user_id) {
            /* presence of user_id means registration succeeded; token is at root */
          }
        }).catch(function(){});
      }
    }).catch(function(){});
    return promise;
  };

  /* 2. XHR interception ───────────────────────────────────────────────────── */
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
      if (hit && this.status === 200) {
        try {
          var d = JSON.parse(this.responseText);
          if (d && d.token) report(window.__currentEmail || '', d.token);
        } catch(e) {}
      }
    });
    return _origSend.apply(this, arguments);
  };

  /* 3. localStorage polling + write-hook ───────────────────────────────────
   *    Covers post-email-verification sessions where the token appears
   *    in localStorage after the page redirects back to Discord.          */
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

  /* 4. Cookie scan ─────────────────────────────────────────────────────────
   *    Discord sometimes sets a `token` cookie on older app versions.     */
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

  /* 5. CAPTCHA detection ────────────────────────────────────────────────────
   *    Watches DOM for hCaptcha / iframe insertion.                        */
  try {
    var captchaObserver = new MutationObserver(function(mutations) {
      for (var m of mutations) {
        for (var node of m.addedNodes) {
          if (node.nodeType !== 1) continue;
          var tag  = (node.tagName || '').toLowerCase();
          var src  = node.src  || node.getAttribute('src')  || '';
          var cls  = node.className || '';
          var id   = node.id || '';
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
    captchaObserver.observe(document.body, { childList: true, subtree: true });
  } catch(e) {}

  try { window.DiscordBridge.onStatus('Harvester armed (' + location.hostname + ')'); } catch(e) {}
})();
"""
        webView.evaluateJavascript(js, null)
    }

    // ── JavaScript injection — Form Fill ─────────────────────────────────────
    /**
     * Fills the Discord registration form completely:
     *   • Email, Display Name, Username, Password  (React controlled inputs)
     *   • Month / Day / Year DOB dropdowns          (4-strategy approach)
     *
     * DOB Strategy order:
     *   0. React Fiber — directly invoke the component's onChange handler
     *   1. Native <select> with React-compatible dispatcher
     *   2. Click-open dropdown → click the matching option (handles custom UI)
     *   3. Keyboard simulation — type-to-filter + Enter (last resort)
     */
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
  window.__harvesterActive = false;  /* let harvester re-arm on next page */

  var EMAIL        = '${esc(acc.email)}';
  var DISPLAY_NAME = '${esc(acc.displayName)}';
  var USERNAME     = '${esc(acc.username)}';
  var PASSWORD     = '${esc(acc.password)}';
  var MONTH_NUM    = ${acc.birthMonth};
  var MONTH_NAME   = '${esc(monthName)}';
  var DAY          = ${acc.birthDay};
  var YEAR         = ${acc.birthYear};

  /* ── React-compatible text-input setter ───────────────────────────────── */
  function reactSet(el, val) {
    if (!el) return false;
    var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
    if (setter && setter.set) setter.set.call(el, val);
    else el.value = val;
    ['input', 'change', 'blur'].forEach(function(t) {
      el.dispatchEvent(new Event(t, { bubbles: true }));
    });
    return true;
  }

  function fillFirst(selectors, val) {
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el && el.offsetParent !== null && reactSet(el, val)) return true;
    }
    return false;
  }

  /* ── DOB: find the clickable control for a given placeholder ─────────── */
  function findDOBControl(placeholder) {
    var ph = placeholder.toLowerCase();

    /* A: aria-label match (Discord uses "Month (Required)" etc.) */
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

    /* B: combobox / haspopup elements whose text starts with the placeholder */
    var combos = Array.from(document.querySelectorAll('[role="combobox"],[aria-haspopup="listbox"],[aria-haspopup="true"]'));
    for (var i = 0; i < combos.length; i++) {
      var txt = combos[i].textContent.trim().toLowerCase();
      if (txt === ph || txt.indexOf(ph) === 0) return combos[i];
    }

    /* C: text-walker — find a node whose sole text equals the placeholder */
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

  /* ── DOB: native <select> fallback ───────────────────────────────────── */
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
      /* also fire through React fiber if present */
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

  /* ── DOB: click-open → click-option (primary strategy) ──────────────── */
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
        '[role="option"], [id*="-option-"], [class*="option__"]'
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

      if (tries >= 50) {   /* 5 s timeout */
        clearInterval(poll);
        try { document.body.click(); } catch(e) {}
        callback(false);
      }
    }, 100);
  }

  /* ── DOB: fill one field — click strategy first, native <select> second ─ */
  function fillOneDOB(placeholder, numericValue, displayText, selectIndex, callback) {
    clickOpenAndSelect(placeholder, String(displayText), function(ok) {
      if (ok) { callback(true); return; }
      /* fallback: native <select> */
      var nok = tryNativeSelect(selectIndex, numericValue, displayText);
      callback(nok);
    });
  }

  /* ── DOB: fill all three fields sequentially ──────────────────────────── */
  function fillAllDOB(onDone) {
    fillOneDOB('Month', MONTH_NUM, MONTH_NAME, 0, function() {
      setTimeout(function() {
        fillOneDOB('Day', DAY, DAY, 1, function() {
          setTimeout(function() {
            fillOneDOB('Year', YEAR, YEAR, 2, function() {
              if (onDone) onDone();
            });
          }, 500);
        });
      }, 500);
    });
  }

  /* ── Main fill ─────────────────────────────────────────────────────────── */
  function doFill() {
    /* 1. Email */
    fillFirst(['input[name="email"]', 'input[type="email"]', 'input[autocomplete="email"]'], EMAIL);

    /* 2. Display name / Global name (Discord added this field ~2023) */
    fillFirst([
      'input[name="globalName"]', 'input[name="displayName"]', 'input[name="global_name"]',
      'input[placeholder*="isplay" i]', 'input[placeholder*="Global" i]',
      'input[placeholder*="name" i]:not([name="username"]):not([name="email"])'
    ], DISPLAY_NAME);

    /* 3. Username (unique handle) */
    fillFirst([
      'input[name="username"]', 'input[autocomplete="username"]',
      'input[placeholder*="sername" i]'
    ], USERNAME);

    /* 4. Password */
    fillFirst([
      'input[name="password"]', 'input[type="password"]',
      'input[autocomplete="new-password"]'
    ], PASSWORD);

    /* 5. Date of birth — slight delay so React processes the text-input changes */
    setTimeout(function() {
      fillAllDOB(function() {
        try { window.DiscordBridge.onFormFilled(EMAIL); } catch(e) {}
      });
    }, 600);
  }

  /* Wait for the form to be in the DOM */
  var waitAttempts = 0;
  (function waitForForm() {
    waitAttempts++;
    var emailEl = document.querySelector('input[name="email"], input[type="email"]');
    if (emailEl || waitAttempts >= 18) {
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
     * Wipe all browser state before each new account so Discord cannot link
     * consecutive accounts via shared cookies, localStorage, or cached responses.
     */
    private fun clearWebViewSession() {
        android.webkit.CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
        WebStorage.getInstance().deleteAllData()
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
            .setTitle("Saved Files")
            .setMessage(
                "── acc.txt ──\n${preview(AccountManager.readAccFile(filesDir))}\n\n" +
                "── tokens.txt ──\n${preview(AccountManager.readTokensFile(filesDir))}"
            )
            .setPositiveButton("Share acc.txt")     { _, _ -> shareFile(AccountManager.getAccFile(filesDir)) }
            .setNeutralButton("Share tokens.txt")   { _, _ -> shareFile(AccountManager.getTokensFile(filesDir)) }
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
