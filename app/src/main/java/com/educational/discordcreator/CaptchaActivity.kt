package com.educational.discordcreator

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.*
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * CaptchaActivity — minimal CAPTCHA-only WebView (v3)
 *
 * Launched by MainActivity when the req-based creator receives a captcha_key
 * response from Discord's /auth/register endpoint.
 *
 * Flow:
 *  1. Receives account data + sitekey via Intent
 *  2. Loads discord.com/register with full anti-detect injection
 *  3. Silently pre-fills the form + submits to surface hCaptcha
 *  4. Watches for the h-captcha-response textarea to be populated
 *  5. Returns the token via setResult → closes itself
 *
 * If the user presses Back or times out, returns RESULT_CANCELED.
 */
class CaptchaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SITE_KEY  = "site_key"
        const val EXTRA_SERVICE   = "service"
        const val EXTRA_RQ_TOKEN  = "rq_token"
        const val EXTRA_EMAIL     = "email"
        const val EXTRA_PASSWORD  = "password"
        const val EXTRA_USERNAME  = "username"
        const val EXTRA_DISP_NAME = "display_name"
        const val EXTRA_BIRTH_M   = "birth_m"
        const val EXTRA_BIRTH_D   = "birth_d"
        const val EXTRA_BIRTH_Y   = "birth_y"
        const val RESULT_TOKEN    = "captcha_token"
        private const val TIMEOUT_MS = 300_000L // 5 minutes
    }

    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var btnCancel: Button

    private var tokenReturned = false
    private val profile by lazy { AntiBanManager.generateProfile() }

    // Timeout watchdog
    private val timeoutRunnable = Runnable {
        if (!tokenReturned) {
            tvStatus.text = "⏰ Timed out — returning to app"
            webView.postDelayed({ finish() }, 2000)
        }
    }

    inner class CaptchaBridge {
        @JavascriptInterface
        fun onTokenReady(token: String) {
            if (token.length < 20 || tokenReturned) return
            tokenReturned = true
            runOnUiThread {
                webView.removeCallbacks(timeoutRunnable)
                tvStatus.text = "✓ CAPTCHA solved — returning token"
                val data = Intent().putExtra(RESULT_TOKEN, token)
                setResult(RESULT_OK, data)
                webView.postDelayed({ finish() }, 600)
            }
        }

        @JavascriptInterface
        fun onStatus(msg: String) {
            runOnUiThread { tvStatus.text = msg.take(80) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_captcha)

        webView  = findViewById(R.id.captchaWebView)
        tvStatus = findViewById(R.id.tvCaptchaStatus)
        btnCancel = findViewById(R.id.btnCaptchaCancel)

        btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        tvStatus.text = "Loading CAPTCHA…"
        setupWebView()

        // Inject hCaptcha accessibility cookies before loading page
        LocalHCaptchaSolver.refreshAccessibilityCookies()
        webView.loadUrl("https://discord.com/register")

        // Start timeout watchdog
        webView.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled   = true
            domStorageEnabled   = true
            databaseEnabled     = true
            userAgentString     = profile.userAgent
            mixedContentMode    = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort     = true
            loadWithOverviewMode = true
            cacheMode           = WebSettings.LOAD_DEFAULT
        }

        webView.addJavascriptInterface(CaptchaBridge(), "CaptchaBridge")

        // Pre-script injection — fires before any page JS
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                AntiBanManager.getDocumentStartScript(),
                setOf("*")
            )
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (!tokenReturned) {
                    tvStatus.text = if (newProgress >= 100) "Waiting for CAPTCHA…" else "Loading $newProgress%"
                }
            }
            override fun onJsAlert(v: WebView?, u: String?, m: String?, r: JsResult?): Boolean {
                r?.confirm(); return true
            }
            override fun onJsConfirm(v: WebView?, u: String?, m: String?, r: JsResult?): Boolean {
                r?.confirm(); return true
            }
            override fun onConsoleMessage(cm: ConsoleMessage?): Boolean = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains("discord.com") == true) {
                    AntiBanManager.injectAntiDetection(webView, profile)
                    injectCaptchaFiller()
                    injectTokenWatcher()
                }
            }
        }
    }

    /** Pre-fills and submits the form silently to trigger hCaptcha */
    private fun injectCaptchaFiller() {
        val months = listOf("January","February","March","April","May","June",
            "July","August","September","October","November","December")
        val bm = intent.getIntExtra(EXTRA_BIRTH_M, 6)
        val bd = intent.getIntExtra(EXTRA_BIRTH_D, 15)
        val by = intent.getIntExtra(EXTRA_BIRTH_Y, 2000)
        val email    = intent.getStringExtra(EXTRA_EMAIL)    ?: ""
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val dispName = intent.getStringExtra(EXTRA_DISP_NAME) ?: ""
        fun esc(s: String) = s.replace("\\","\\\\").replace("'","\\'")

        val js = """
(function() {
  var EMAIL = '${esc(email)}';
  var PASS  = '${esc(password)}';
  var USER  = '${esc(username)}';
  var DISP  = '${esc(dispName)}';
  var MONTH_NUM = $bm;
  var MONTH_NAME = '${esc(months[bm - 1])}';
  var DAY   = $bd;
  var YEAR  = $by;

  function reactSet(el, val) {
    if (!el) return;
    var nv = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
    if (nv && nv.set) nv.set.call(el, val); else el.value = val;
    ['input','change','blur'].forEach(function(t){
      el.dispatchEvent(new Event(t,{bubbles:true}));
    });
  }
  function fill(sels, val) {
    for (var i=0;i<sels.length;i++){
      var e=document.querySelector(sels[i]);
      if(e&&e.offsetParent!==null){reactSet(e,val);return true;}
    }
    return false;
  }
  function findDOBControl(ph){
    var byLabel=document.querySelector('[aria-label*="'+ph+'" i]');
    if(byLabel){for(var p=byLabel;p&&p!==document.body;p=p.parentElement){
      if(p.getAttribute('role')==='combobox'||p.getAttribute('aria-haspopup'))return p;
    }return byLabel;}
    var combos=Array.from(document.querySelectorAll('[role="combobox"],[aria-haspopup]'));
    for(var i=0;i<combos.length;i++){if(combos[i].textContent.trim().toLowerCase()===ph.toLowerCase())return combos[i];}
    return null;
  }
  function openAndPick(ph, text, cb){
    var ctrl=findDOBControl(ph);
    if(!ctrl){cb(false);return;}
    ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(ev){
      ctrl.dispatchEvent(new MouseEvent(ev,{bubbles:true,cancelable:true}));
    });
    var tries=0;
    var poll=setInterval(function(){
      tries++;
      var opts=Array.from(document.querySelectorAll('[role="option"],[id*="-option-"],[class*="option"]')).filter(function(o){return o.offsetParent!==null;});
      for(var k=0;k<opts.length;k++){
        if(opts[k].textContent.trim()===String(text)){
          clearInterval(poll);
          ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(ev){
            opts[k].dispatchEvent(new MouseEvent(ev,{bubbles:true,cancelable:true}));
          });
          setTimeout(function(){cb(true);},300);
          return;
        }
      }
      if(tries>=60){clearInterval(poll);cb(false);}
    },100);
  }

  function doWork() {
    fill(['input[name="email"]','input[type="email"]'], EMAIL);
    fill(['input[name="globalName"]','input[name="displayName"]','input[placeholder*="isplay" i]'], DISP);
    fill(['input[name="username"]','input[autocomplete="username"]'], USER);
    fill(['input[name="password"]','input[type="password"]'], PASS);

    setTimeout(function(){
      openAndPick('Month', MONTH_NAME, function(){
        setTimeout(function(){
          openAndPick('Day', DAY, function(){
            setTimeout(function(){
              openAndPick('Year', YEAR, function(){
                setTimeout(function(){
                  var btn=document.querySelector('button[type="submit"],button[class*="submit"],button[class*="primary"]');
                  if(btn&&!btn.disabled){
                    var r=btn.getBoundingClientRect();
                    var cx=r.left+r.width*0.5,cy=r.top+r.height*0.5;
                    ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(ev){
                      btn.dispatchEvent(new MouseEvent(ev,{bubbles:true,cancelable:true,clientX:cx,clientY:cy}));
                    });
                    try{window.CaptchaBridge.onStatus('Form submitted — waiting for CAPTCHA');}catch(e){}
                  }
                }, 600);
              });
            },500);
          });
        },500);
      });
    },700);
  }

  // Wait for form then fill
  var waitAttempts=0;
  (function wait(){
    waitAttempts++;
    if(document.querySelector('input[name="email"],input[type="email"]')||waitAttempts>=20){
      doWork();
    } else {
      setTimeout(wait,500);
    }
  })();
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /** Watches for the hCaptcha response token and returns it via the bridge */
    private fun injectTokenWatcher() {
        val js = """
(function() {
  if (window.__captchaWatcher) return;
  window.__captchaWatcher = true;

  var selectors = [
    '[name="h-captcha-response"]',
    'textarea[name="g-recaptcha-response"]',
    '[name="cf-turnstile-response"]',
    'input[name*="captcha-response"]',
    'input[name*="captcha_response"]'
  ];

  /* Also intercept fetch responses for the token */
  var _origFetch = window.fetch;
  window.fetch = function(input, init) {
    var promise = _origFetch.apply(this, arguments);
    promise.then(function(resp) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      if ((url.indexOf('/auth/register') !== -1) && (resp.status === 200 || resp.status === 201)) {
        resp.clone().json().then(function(d) {
          if (d && d.token && d.token.length > 20) {
            try { window.CaptchaBridge.onStatus('Token via fetch — success'); } catch(e) {}
          }
        }).catch(function(){});
      }
    }).catch(function(){});
    return promise;
  };

  var polls = 0;
  var pollId = setInterval(function() {
    polls++;
    if (polls > 600) { clearInterval(pollId); return; }

    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el && el.value && el.value.length > 20) {
        clearInterval(pollId);
        try { window.CaptchaBridge.onTokenReady(el.value); } catch(e) {}
        return;
      }
    }

    /* Check for hCaptcha widget with visible solved state */
    try {
      var frames = document.querySelectorAll('iframe[src*="hcaptcha"]');
      frames.forEach(function(fr) {
        try {
          var fdoc = fr.contentDocument || fr.contentWindow.document;
          var checked = fdoc.querySelector('.checkbox[aria-checked="true"], [data-hcaptcha-widget-id]');
          var resp = fdoc.querySelector('[name="h-captcha-response"]');
          if (resp && resp.value && resp.value.length > 20) {
            clearInterval(pollId);
            try { window.CaptchaBridge.onTokenReady(resp.value); } catch(e) {}
          }
        } catch(e) {}
      });
    } catch(e) {}

    if (polls % 20 === 0) {
      try { window.CaptchaBridge.onStatus('Waiting for CAPTCHA… (' + Math.floor(polls/2) + 's)'); } catch(e) {}
    }
  }, 500);
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun onDestroy() {
        webView.removeCallbacks(timeoutRunnable)
        webView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }
}
