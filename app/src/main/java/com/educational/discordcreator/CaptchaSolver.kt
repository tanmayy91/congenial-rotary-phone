package com.educational.discordcreator

import android.webkit.WebView

/**
 * Free CAPTCHA solving utilities — no API key required.
 *
 * Strategy 1 — Mouse-trail + Auto-click: Simulate realistic mouse movement
 *   then click the hCaptcha widget container in the main page.
 *
 * Strategy 2 — Accessibility bypass: Navigate to hcaptcha.com/accessibility
 *   so the user completes a one-time email verification. hCaptcha then sets
 *   an `hc_accessibility` cookie in the WebView that silently passes future
 *   challenges on any site. Completely free, no API key.
 *
 * Strategy 3 — Submit button click: Find and programmatically click the
 *   Register/Continue button via multiple selector strategies.
 *
 * Strategy 4 — Completion watcher: Poll for the hidden h-captcha-response
 *   textarea that hCaptcha fills when the challenge is solved, then
 *   auto-click the submit button.
 *
 * Strategy 5 — Inject human mouse movement: Dispatch a realistic sequence
 *   of MouseMove events before any interaction to satisfy mouse-activity
 *   detectors used by hCaptcha's risk engine.
 */
object CaptchaSolver {

    // ── Strategy 0: Simulate human mouse movement (prerequisite) ─────────────
    /**
     * Inject a JS arc of ~30 mousemove events across the page before clicking.
     * hCaptcha's risk engine scores sessions with zero mouse movement as bots.
     */
    fun simulateHumanMouseMovement(webView: WebView) {
        val js = """
(function() {
  if (window.__mouseSimDone) return;
  window.__mouseSimDone = true;
  var steps = 30;
  var startX = Math.floor(Math.random() * 300) + 100;
  var startY = Math.floor(Math.random() * 200) + 100;
  var endX   = Math.floor(Math.random() * 300) + 400;
  var endY   = Math.floor(Math.random() * 200) + 300;
  var i = 0;
  var id = setInterval(function() {
    if (i >= steps) { clearInterval(id); return; }
    var t   = i / steps;
    var cx  = startX + (endX - startX) * t + (Math.random() - 0.5) * 10;
    var cy  = startY + (endY - startY) * t + (Math.random() - 0.5) * 10;
    document.dispatchEvent(new MouseEvent('mousemove', {
      bubbles: true, cancelable: true, clientX: cx, clientY: cy
    }));
    i++;
  }, 40 + Math.floor(Math.random() * 20));
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ── Strategy 1: Auto-click the hCaptcha widget ───────────────────────────
    /**
     * Inject JS that first simulates mouse movement, then tries to click the
     * hCaptcha checkbox area. Works when Discord renders the widget in a way
     * that a synthetic click on the outer container triggers the challenge or
     * passes it.
     */
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
  for (var i = 0; i < selectors.length; i++) {
    var el = document.querySelector(selectors[i]);
    if (el) {
      el.scrollIntoView({ block: 'center', behavior: 'smooth' });
      setTimeout(function(target) {
        return function() {
          var rect = target.getBoundingClientRect();
          var cx = rect.left + rect.width  * (0.35 + Math.random() * 0.3);
          var cy = rect.top  + rect.height * (0.35 + Math.random() * 0.3);
          var evOpts = { bubbles: true, cancelable: true, clientX: cx, clientY: cy };
          ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(ev) {
            target.dispatchEvent(new MouseEvent(ev, evOpts));
          });
        };
      }(el), 200 + Math.floor(Math.random() * 300));
      clicked = true;
      break;
    }
  }

  /* also try reaching inside same-origin hCaptcha iframes for the checkbox.
   * Note: cross-origin iframes will throw a SecurityError (silently caught). */
  try {
    var frames = document.querySelectorAll('iframe[src*="hcaptcha"], iframe[src*="captcha"]');
    frames.forEach(function(fr) {
      try {
        var fdoc = fr.contentDocument || fr.contentWindow.document;
        var cb = fdoc.querySelector('#checkbox, [id*="checkbox"], .checkbox');
        if (cb) { cb.click(); clicked = true; }
      } catch(e) { /* SecurityError expected for cross-origin iframes */ }
    });
  } catch(e) {}

  try {
    window.DiscordBridge.onStatus(
      'Auto-CAPTCHA attempt: ' + (clicked ? 'widget clicked' : 'no widget found'));
  } catch(e) {}
})();
""".trimIndent()
            webView.evaluateJavascript(js, null)
        }, 1200L)
    }

    // ── Strategy 2: hCaptcha accessibility bypass ────────────────────────────
    /**
     * Navigate to the hCaptcha accessibility page.
     * The user enters their email once; hCaptcha sets an `hc_accessibility`
     * cookie in the WebView. After that, all hCaptcha challenges on any site
     * (including Discord) are silently bypassed — no API key needed.
     */
    fun openAccessibilitySetup(webView: WebView) {
        webView.loadUrl("https://www.hcaptcha.com/accessibility")
    }

    // ── Strategy 3: Click the Register / Submit button ───────────────────────
    /**
     * Inject JS that finds and clicks the form's submit button using multiple
     * selector strategies: [type="submit"], known class fragments, and button
     * text matching.  Simulates a realistic pointer-down → click sequence.
     */
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
    'button[class*="cta"]'
  ];
  var submitTexts = ['register','continue','create account','sign up','create','next','confirm','proceed'];
  var clicked = false;

  function realisticClick(el) {
    var rect = el.getBoundingClientRect();
    var cx = rect.left + rect.width  * (0.4 + Math.random() * 0.2);
    var cy = rect.top  + rect.height * (0.4 + Math.random() * 0.2);
    var opts = { bubbles: true, cancelable: true, clientX: cx, clientY: cy };
    ['pointerover','pointerenter','mouseover','mousemove','mouseenter',
     'pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(ev) {
      el.dispatchEvent(new MouseEvent(ev, opts));
    });
  }

  /* 1. Try CSS selectors first */
  for (var i = 0; i < submitSelectors.length && !clicked; i++) {
    var el = document.querySelector(submitSelectors[i]);
    if (el && el.offsetParent !== null && !el.disabled) {
      realisticClick(el);
      clicked = true;
    }
  }

  /* 2. Scan all visible buttons by text content */
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
    window.DiscordBridge.onStatus(
      'Register button: ' + (clicked ? 'clicked ✓' : 'not found'));
  } catch(e) {}
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ── Strategy 4: Watch for CAPTCHA completion and auto-submit ─────────────
    /**
     * Inject a JS poller that watches the hidden `h-captcha-response` textarea
     * (or equivalent for reCAPTCHA / Turnstile / audio challenge). When hCaptcha
     * fills this textarea with the solver token, we automatically click the submit
     * button with realistic pointer events. Times out after 120 seconds.
     */
    fun watchForCaptchaSolvedAndSubmit(webView: WebView) {
        val js = """
(function() {
  if (window.__captchaWatcherActive) return;
  window.__captchaWatcherActive = true;

  var pollCount = 0;
  var maxPolls  = 240; /* 120 seconds at 500 ms intervals */
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

  var pollId = setInterval(function() {
    pollCount++;
    if (pollCount >= maxPolls) {
      clearInterval(pollId);
      window.__captchaWatcherActive = false;
      return;
    }

    /* Look for any CAPTCHA response token textarea */
    var tokenSelectors = [
      '[name="h-captcha-response"]',
      'textarea[name="g-recaptcha-response"]',
      '[name="cf-turnstile-response"]',
      'textarea[id*="hcaptcha"]',
      'input[name*="captcha-response"]'
    ];
    var tokenEls = tokenSelectors.map(function(s) { return document.querySelector(s); });

    for (var i = 0; i < tokenEls.length; i++) {
      if (tokenEls[i] && tokenEls[i].value && tokenEls[i].value.length > 20) {
        clearInterval(pollId);
        window.__captchaWatcherActive = false;
        try {
          window.DiscordBridge.onStatus('CAPTCHA solved — auto-clicking submit…');
        } catch(e) {}

        setTimeout(function() {
          var buttons = Array.from(
            document.querySelectorAll('button[type="submit"], button, input[type="submit"]')
          );
          for (var j = 0; j < buttons.length; j++) {
            var txt = (buttons[j].textContent || buttons[j].value || '').trim().toLowerCase();
            var isSubmit   = buttons[j].type === 'submit';
            var textMatch  = submitTexts.some(function(t) { return txt.indexOf(t) !== -1; });
            var isVisible  = buttons[j].offsetParent !== null;
            var isEnabled  = !buttons[j].disabled;
            if ((isSubmit || textMatch) && isVisible && isEnabled) {
              realisticClick(buttons[j]);
              break;
            }
          }
        }, 400 + Math.floor(Math.random() * 400));
        return;
      }
    }
  }, 500);
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }
}

