package com.educational.discordcreator

import android.webkit.WebView

/**
 * CaptchaSolver — v2 Enhanced CAPTCHA bypass strategies
 *
 * Strategy 0 — Human mouse movement simulation (prerequisite for all)
 * Strategy 1 — Auto-click hCaptcha widget with realistic pointer events
 * Strategy 2 — hCaptcha accessibility page bypass (one-time email setup)
 * Strategy 3 — Register/Submit button click with full pointer event chain
 * Strategy 4 — Completion watcher: poll for solved token → auto-submit
 * Strategy 5 — React fiber DOB + form state sync after CAPTCHA solved
 */
object CaptchaSolver {

    fun simulateHumanMouseMovement(webView: WebView) {
        val js = """
(function() {
  if (window.__mouseSimDone) return;
  window.__mouseSimDone = true;
  var steps = 45;
  var startX = Math.floor(Math.random() * 300) + 100;
  var startY = Math.floor(Math.random() * 200) + 100;
  var endX   = Math.floor(Math.random() * 300) + 400;
  var endY   = Math.floor(Math.random() * 200) + 300;
  /* Bezier control point for curved movement */
  var cpX    = (startX + endX) / 2 + (Math.random() - 0.5) * 200;
  var cpY    = (startY + endY) / 2 + (Math.random() - 0.5) * 200;
  var i = 0;
  var id = setInterval(function() {
    if (i >= steps) { clearInterval(id); return; }
    var t   = i / steps;
    var mt  = 1 - t;
    /* Quadratic bezier: P = mt^2*P0 + 2*mt*t*P1 + t^2*P2 */
    var cx  = mt*mt*startX + 2*mt*t*cpX + t*t*endX + (Math.random() - 0.5) * 4;
    var cy  = mt*mt*startY + 2*mt*t*cpY + t*t*endY + (Math.random() - 0.5) * 4;
    document.dispatchEvent(new MouseEvent('mousemove', {
      bubbles: true, cancelable: true, clientX: Math.round(cx), clientY: Math.round(cy)
    }));
    i++;
  }, 35 + Math.floor(Math.random() * 25));
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }

    fun tryAutoSolve(webView: WebView) {
        simulateHumanMouseMovement(webView)
        webView.postDelayed({
            val js = """
(function() {
  var selectors = [
    '.h-captcha',
    '[data-hcaptcha-widget-id]',
    'iframe[src*="hcaptcha"]',
    'iframe[src*="newassets.hcaptcha"]',
    'iframe[src*="captcha"]',
    '[class*="hcaptcha"]',
    '[id*="hcaptcha"]',
    '[class*="captcha"]',
    '[id*="captcha"]',
    'div[data-sitekey]',
    '.g-recaptcha',
    'div[data-callback]'
  ];
  var clicked = false;

  function realisticClick(el) {
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    setTimeout(function() {
      var rect = el.getBoundingClientRect();
      var cx = rect.left + rect.width  * (0.35 + Math.random() * 0.3);
      var cy = rect.top  + rect.height * (0.35 + Math.random() * 0.3);
      var evOpts = { bubbles: true, cancelable: true, clientX: cx, clientY: cy };
      ['pointerover','pointerenter','mouseover','mouseenter','mousemove',
       'pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(ev) {
        el.dispatchEvent(new MouseEvent(ev, evOpts));
      });
    }, 150 + Math.floor(Math.random() * 200));
  }

  for (var i = 0; i < selectors.length; i++) {
    var el = document.querySelector(selectors[i]);
    if (el) { realisticClick(el); clicked = true; break; }
  }

  /* Try reaching same-origin hCaptcha iframes */
  try {
    var frames = document.querySelectorAll('iframe[src*="hcaptcha"], iframe[src*="captcha"]');
    frames.forEach(function(fr) {
      try {
        var fdoc = fr.contentDocument || fr.contentWindow.document;
        var cb = fdoc.querySelector('#checkbox, [id*="checkbox"], .checkbox, [class*="checkbox"]');
        if (cb) { cb.click(); clicked = true; }
      } catch(e) {}
    });
  } catch(e) {}

  try {
    window.DiscordBridge.onStatus('Auto-solve: ' + (clicked ? 'widget clicked' : 'no widget found'));
  } catch(e) {}
})();
""".trimIndent()
            webView.evaluateJavascript(js, null)
        }, 1200L)
    }

    fun openAccessibilitySetup(webView: WebView) {
        webView.loadUrl("https://www.hcaptcha.com/accessibility")
    }

    fun clickRegisterButton(webView: WebView) {
        val js = """
(function() {
  var submitSelectors = [
    'button[type="submit"]',
    'button[class*="submit"]',
    'button[class*="register"]',
    'button[class*="continue"]',
    'button[class*="auth"]',
    'input[type="submit"]',
    'button[class*="primary"]',
    'button[class*="cta"]',
    'button[class*="grow"]'
  ];
  var submitTexts = ['register','continue','create account','sign up','create','next','confirm','proceed','done'];
  var clicked = false;

  function realisticClick(el) {
    el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    var rect = el.getBoundingClientRect();
    var cx = rect.left + rect.width  * (0.4 + Math.random() * 0.2);
    var cy = rect.top  + rect.height * (0.4 + Math.random() * 0.2);
    var opts = { bubbles: true, cancelable: true, clientX: cx, clientY: cy };
    ['pointerover','pointerenter','mouseover','mousemove','mouseenter',
     'pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(ev) {
      el.dispatchEvent(new MouseEvent(ev, opts));
    });
  }

  for (var i = 0; i < submitSelectors.length && !clicked; i++) {
    var el = document.querySelector(submitSelectors[i]);
    if (el && el.offsetParent !== null && !el.disabled) {
      realisticClick(el);
      clicked = true;
    }
  }

  if (!clicked) {
    var buttons = Array.from(document.querySelectorAll('button, input[type="submit"]'));
    for (var j = 0; j < buttons.length && !clicked; j++) {
      var txt = (buttons[j].textContent || buttons[j].value || '').trim().toLowerCase();
      for (var k = 0; k < submitTexts.length; k++) {
        if (txt.indexOf(submitTexts[k]) !== -1
            && buttons[j].offsetParent !== null
            && !buttons[j].disabled) {
          realisticClick(buttons[j]);
          clicked = true;
          break;
        }
      }
    }
  }

  try {
    window.DiscordBridge.onStatus('Register button: ' + (clicked ? 'clicked' : 'not found'));
  } catch(e) {}
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }

    fun watchForCaptchaSolvedAndSubmit(webView: WebView) {
        val js = """
(function() {
  if (window.__captchaWatcherActive) return;
  window.__captchaWatcherActive = true;

  var pollCount = 0;
  var maxPolls  = 480; /* 4 minutes at 500 ms intervals */
  var submitTexts = ['register','continue','create account','sign up','create','next','confirm','proceed'];

  function realisticClick(el) {
    var rect = el.getBoundingClientRect();
    var cx = rect.left + rect.width  * (0.4 + Math.random() * 0.2);
    var cy = rect.top  + rect.height * (0.4 + Math.random() * 0.2);
    var opts = { bubbles: true, cancelable: true, clientX: cx, clientY: cy };
    ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(ev) {
      el.dispatchEvent(new MouseEvent(ev, opts));
    });
  }

  var tokenSelectors = [
    '[name="h-captcha-response"]',
    'textarea[name="g-recaptcha-response"]',
    '[name="cf-turnstile-response"]',
    'textarea[id*="hcaptcha"]',
    'input[name*="captcha-response"]',
    'input[name*="captcha_response"]',
    'textarea[name*="captcha"]'
  ];

  var pollId = setInterval(function() {
    pollCount++;
    if (pollCount >= maxPolls) {
      clearInterval(pollId);
      window.__captchaWatcherActive = false;
      return;
    }

    for (var i = 0; i < tokenSelectors.length; i++) {
      var el = document.querySelector(tokenSelectors[i]);
      if (el && el.value && el.value.length > 20) {
        clearInterval(pollId);
        window.__captchaWatcherActive = false;
        try { window.DiscordBridge.onStatus('CAPTCHA solved — auto-clicking submit...'); } catch(e) {}

        setTimeout(function() {
          var buttons = Array.from(
            document.querySelectorAll('button[type="submit"], button, input[type="submit"]')
          );
          for (var j = 0; j < buttons.length; j++) {
            var txt = (buttons[j].textContent || buttons[j].value || '').trim().toLowerCase();
            var isSubmit  = buttons[j].type === 'submit';
            var textMatch = submitTexts.some(function(t) { return txt.indexOf(t) !== -1; });
            var isVisible = buttons[j].offsetParent !== null;
            var isEnabled = !buttons[j].disabled;
            if ((isSubmit || textMatch) && isVisible && isEnabled) {
              realisticClick(buttons[j]);
              break;
            }
          }
        }, 400 + Math.floor(Math.random() * 500));
        return;
      }
    }
  }, 500);
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }
}
