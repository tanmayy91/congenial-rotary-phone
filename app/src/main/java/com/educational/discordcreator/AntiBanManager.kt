package com.educational.discordcreator

import android.webkit.WebView

/**
 * Anti-ban and anti-detection utilities for the educational WebView automation demo.
 *
 * Provides:
 *  • A pool of realistic Chrome/Firefox/Edge user-agent strings rotated per account
 *  • Human-like randomised delay helpers (page load, typing, account gap, etc.)
 *  • Per-account [FingerprintProfile] generation — each account gets a unique, internally-
 *    consistent set of browser attributes (screen, GPU, UA brand, locale, hardware, etc.)
 *  • A 27-signal JavaScript fingerprint-spoof injector driven by the profile that hides the
 *    WebView's automation signals
 */

// ── Per-account browser fingerprint snapshot ──────────────────────────────────────────────────────
/**
 * A complete, per-account snapshot of all browser attributes used for fingerprint spoofing.
 * Generate one via [AntiBanManager.generateProfile] and pass it to
 * [AntiBanManager.injectAntiDetection] to inject the corresponding JS overrides.
 */
data class FingerprintProfile(
    // ── Navigator / browser ──────────────────────────────────────────────────
    val userAgent: String,
    val platform: String,           // "Win32" or "MacIntel"
    val language: String,           // primary language tag, e.g. "en-US"
    val languages: List<String>,    // full Accept-Language list
    val doNotTrack: String?,        // null (no preference) or "1" (opted-out)
    val chromeMajorVersion: Int,    // 0 for Firefox; used for userAgentData / brands
    val isFirefox: Boolean,
    val isEdge: Boolean,
    val isMac: Boolean,
    // ── Display ──────────────────────────────────────────────────────────────
    val screenWidth: Int,
    val screenHeight: Int,
    val screenAvailHeight: Int,     // screenHeight minus OS taskbar
    val colorDepth: Int,            // 24 (standard) or 30 (HDR / Retina)
    val pixelRatio: Int,            // 1 (Windows) or 2 (Mac Retina)
    // ── Hardware ─────────────────────────────────────────────────────────────
    val hardwareConcurrency: Int,   // logical CPU cores
    val deviceMemory: Int,          // GB RAM visible to the page
    // ── Graphics (WebGL) ─────────────────────────────────────────────────────
    val webGlVendor: String,
    val webGlRenderer: String,
    // ── Canvas / audio noise seeds (unique per account) ──────────────────────
    val canvasNoiseR: Int,          // 1–5  pixel delta applied to red channel
    val canvasNoiseG: Int,          // 1–5  pixel delta applied to green channel
    val audioNoiseScale: Double,    // tiny multiplier ~1e-5 for frequency-data jitter
    // ── Locale / timezone ────────────────────────────────────────────────────
    val timezone: String,           // IANA timezone id, e.g. "America/New_York"
    val timezoneOffset: Int         // getTimezoneOffset() value: UTC-5 → 300
)

// ─────────────────────────────────────────────────────────────────────────────────────────────────
object AntiBanManager {

    // ── UA metadata table ─────────────────────────────────────────────────────
    private data class UAEntry(
        val ua: String,
        val chromeMajor: Int,   // 0 for Firefox
        val isMac: Boolean,
        val isEdge: Boolean = false,
        val isFirefox: Boolean = false
    )

    private val UA_ENTRIES = listOf(
        // Chrome 124 – Windows 10
        UAEntry("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36", 124, false),
        // Chrome 123 – Windows 10
        UAEntry("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36", 123, false),
        // Chrome 122 – Windows 10
        UAEntry("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36", 122, false),
        // Chrome 121 – Windows 10
        UAEntry("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36", 121, false),
        // Chrome 124 – macOS Sonoma
        UAEntry("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36", 124, true),
        // Chrome 123 – macOS Ventura
        UAEntry("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_2_1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36", 123, true),
        // Chrome 122 – macOS
        UAEntry("Mozilla/5.0 (Macintosh; Intel Mac OS X 13_6_4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36", 122, true),
        // Firefox 125 – Windows 10
        UAEntry("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0", 0, false, isFirefox = true),
        // Firefox 124 – Windows 10
        UAEntry("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0", 0, false, isFirefox = true),
        // Firefox 125 – macOS
        UAEntry("Mozilla/5.0 (Macintosh; Intel Mac OS X 14.4; rv:125.0) Gecko/20100101 Firefox/125.0", 0, true, isFirefox = true),
        // Edge 124 – Windows 10
        UAEntry("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0", 124, false, isEdge = true),
        // Edge 123 – Windows 10
        UAEntry("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36 Edg/123.0.0.0", 123, false, isEdge = true)
    )

    /** Return a random user-agent string from the pool. */
    fun getRandomUserAgent(): String = UA_ENTRIES.random().ua

    // ── Timezone → getTimezoneOffset() lookup ────────────────────────────────
    private val TIMEZONE_OFFSETS = mapOf(
        "America/New_York"    to  300,
        "America/Chicago"     to  360,
        "America/Denver"      to  420,
        "America/Los_Angeles" to  480,
        "America/Phoenix"     to  420,
        "America/Toronto"     to  300,
        "Europe/London"       to    0,
        "Europe/Paris"        to  -60,
        "Europe/Berlin"       to  -60,
        "Europe/Amsterdam"    to  -60,
        "Asia/Tokyo"          to -540,
        "Asia/Seoul"          to -540,
        "Australia/Sydney"    to -600
    )

    // ── Profile generator ────────────────────────────────────────────────────
    /**
     * Generate a randomised but internally-consistent [FingerprintProfile].
     * Call once per account and store; pass the same profile to every
     * [injectAntiDetection] call for that account session.
     */
    fun generateProfile(): FingerprintProfile {
        val entry = UA_ENTRIES.random()

        // Screen resolution pool (width, height, availHeight)
        data class ScreenSpec(val w: Int, val h: Int, val avail: Int)
        val screens = listOf(
            ScreenSpec(1920, 1080, 1040),
            ScreenSpec(1920, 1200, 1160),
            ScreenSpec(2560, 1440, 1400),
            ScreenSpec(1366,  768,  728),
            ScreenSpec(1440,  900,  860),
            ScreenSpec(1536,  864,  824),
            ScreenSpec(2560, 1600, 1560),
            ScreenSpec(1280,  800,  760),
            ScreenSpec(1680, 1050, 1010)
        )
        val sc = screens.random()

        // GPU / WebGL renderer pool (platform-specific)
        val renderers: List<Pair<String, String>> = if (entry.isMac) listOf(
            "Google Inc. (Apple)"  to "ANGLE (Apple, Apple M1 Pro, OpenGL 4.1)",
            "Google Inc. (Apple)"  to "ANGLE (Apple, Apple M2, OpenGL 4.1)",
            "Google Inc. (Intel)"  to "ANGLE (Intel, Intel(R) Iris(R) Plus Graphics OpenGL Engine, OpenGL 4.1)"
        ) else listOf(
            "Google Inc. (Intel)"  to "ANGLE (Intel, Intel(R) UHD Graphics 620 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (Intel)"  to "ANGLE (Intel, Intel(R) UHD Graphics 630 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (Intel)"  to "ANGLE (Intel, Intel(R) Iris(R) Xe Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (NVIDIA)" to "ANGLE (NVIDIA, NVIDIA GeForce GTX 1060 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (NVIDIA)" to "ANGLE (NVIDIA, NVIDIA GeForce GTX 1650 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (AMD)"    to "ANGLE (AMD, Radeon RX 580 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (AMD)"    to "ANGLE (AMD, AMD Radeon RX 5700 XT Direct3D11 vs_5_0 ps_5_0, D3D11)"
        )
        val (glVendor, glRenderer) = renderers.random()

        // Timezone
        val (tz, tzOffset) = TIMEZONE_OFFSETS.entries.toList().random()

        // Language / Accept-Language sets
        val languageSets = listOf(
            "en-US" to listOf("en-US", "en"),
            "en-US" to listOf("en-US", "en-GB", "en"),
            "en-GB" to listOf("en-GB", "en-US", "en"),
            "en-CA" to listOf("en-CA", "en-US", "en"),
            "en-AU" to listOf("en-AU", "en-US", "en")
        )
        val (lang, langs) = languageSets.random()

        return FingerprintProfile(
            userAgent           = entry.ua,
            platform            = if (entry.isMac) "MacIntel" else "Win32",
            language            = lang,
            languages           = langs,
            doNotTrack          = if ((0..3).random() == 0) "1" else null,
            chromeMajorVersion  = entry.chromeMajor,
            isFirefox           = entry.isFirefox,
            isEdge              = entry.isEdge,
            isMac               = entry.isMac,
            screenWidth         = sc.w,
            screenHeight        = sc.h,
            screenAvailHeight   = sc.avail,
            colorDepth          = if (entry.isMac && (0..3).random() == 0) 30 else 24,
            pixelRatio          = if (entry.isMac) 2 else 1,
            hardwareConcurrency = listOf(4, 6, 8, 12, 16).random(),
            deviceMemory        = listOf(2, 4, 8).random(),
            webGlVendor         = glVendor,
            webGlRenderer       = glRenderer,
            canvasNoiseR        = (1..5).random(),
            canvasNoiseG        = (1..5).random(),
            audioNoiseScale     = (1..9).random() * 0.00001,
            timezone            = tz,
            timezoneOffset      = tzOffset
        )
    }

    // ── Human-like delay helpers (milliseconds) ───────────────────────────────

    /** Delay before starting to fill the form after page load: 2.5 – 5.0 s */
    fun getPageLoadDelay(): Long = (2500L..5000L).random()

    /** Delay between typing individual characters: 60 – 180 ms */
    fun getTypingDelay(): Long = (60L..180L).random()

    /** Short pause between UI interactions: 800 – 2500 ms */
    fun getHumanDelay(): Long = (800L..2500L).random()

    /** Gap between completing one account and starting the next: 45 – 120 s */
    fun getAccountDelay(): Long = (45000L..120000L).random()

    /** Backoff after a rate-limit (429) response: 30 – 60 s */
    fun getRateLimitDelay(): Long = (30000L..60000L).random()

    // ── Fingerprint-spoof JS injector ─────────────────────────────────────────
    /**
     * Inject 27 JavaScript overrides that neutralise bot-detection signals.
     * All spoofed values are driven by [profile] so each account presents a unique
     * but internally-consistent fingerprint.
     *
     *  1.  navigator.webdriver          → false
     *  2.  navigator.plugins            → realistic 5-plugin PDF array
     *  3.  navigator.mimeTypes          → 2 PDF mime types matching plugins
     *  4.  navigator.languages/language → per-profile locale
     *  5.  navigator.platform           → Win32 or MacIntel
     *  6.  navigator.maxTouchPoints     → 0 (desktop, no touch)
     *  7.  navigator.doNotTrack         → per-profile (null or "1")
     *  8.  navigator.cookieEnabled      → true
     *  9.  Permissions API              → match real browser behaviour
     *  10. window.chrome runtime        → full chrome runtime object
     *  11. Canvas toDataURL             → per-profile micro-noise seed
     *  12. Canvas toBlob                → same noise (separate dirty flag)
     *  13. WebGL getParameter           → per-profile vendor/renderer strings
     *  14. WebGL getShaderPrecisionFormat → realistic precision values
     *  15. screen / window dimensions   → per-profile resolution
     *  16. screen.orientation           → landscape-primary / angle 0
     *  17. Delete automation globals    → phantom, nightmare, selenium, etc.
     *  18. navigator.hardwareConcurrency / deviceMemory → per-profile
     *  19. navigator.connection         → realistic 4G values
     *  20. AudioContext analyser noise  → per-profile scale
     *  21. performance.now()            → sub-ms jitter
     *  22. RTCPeerConnection            → strip ICE servers (no IP leak)
     *  23. navigator.getBattery         → fully charged
     *  24. navigator.vendor             → Google Inc.
     *  25. navigator.userAgentData      → per-profile brands / hints (Chromium only)
     *  26. Date.prototype.getTimezoneOffset → per-profile offset
     *  27. navigator.oscpu              → per-profile OS string (Firefox only)
     */
    fun injectAntiDetection(webView: WebView, profile: FingerprintProfile) {
        // ── Prepare Kotlin-side values for JS interpolation ──────────────────
        val langsJson   = profile.languages.joinToString(",") { "\"$it\"" }
        val dntLiteral  = if (profile.doNotTrack != null) "'${profile.doNotTrack}'" else "null"
        val audioNoise  = "%.8f".format(profile.audioNoiseScale)
        val chromeMaj   = profile.chromeMajorVersion.toString()
        val fullVer     = if (!profile.isFirefox && profile.chromeMajorVersion > 0)
            "${profile.chromeMajorVersion}.0.6367.82" else ""
        val uadPlatform = if (profile.isMac) "macOS" else "Windows"
        val uadPlatVer  = if (profile.isMac) "14.4.0" else "10.0.0"
        val uadArch     = if (profile.isMac) "arm" else "x86"
        // Brands list varies by browser type
        val brandsShort = when {
            profile.isEdge     -> """[{"brand":"Chromium","version":"$chromeMaj"},{"brand":"Microsoft Edge","version":"$chromeMaj"},{"brand":"Not-A.Brand","version":"99"}]"""
            !profile.isFirefox -> """[{"brand":"Chromium","version":"$chromeMaj"},{"brand":"Google Chrome","version":"$chromeMaj"},{"brand":"Not-A.Brand","version":"99"}]"""
            else               -> null
        }
        val brandsLong = when {
            profile.isEdge     -> """[{"brand":"Chromium","version":"$fullVer"},{"brand":"Microsoft Edge","version":"$fullVer"},{"brand":"Not-A.Brand","version":"99.0.0.0"}]"""
            !profile.isFirefox -> """[{"brand":"Chromium","version":"$fullVer"},{"brand":"Google Chrome","version":"$fullVer"},{"brand":"Not-A.Brand","version":"99.0.0.0"}]"""
            else               -> null
        }
        // Block 25: injected as a whole snippet so Firefox gets nothing
        val uadBlock = if (brandsShort != null && brandsLong != null) """
  /* 25. navigator.userAgentData — Chromium brand hints */
  try {
    if (!navigator.userAgentData) {
      Object.defineProperty(navigator, 'userAgentData', {
        get: function() {
          return {
            brands: $brandsShort,
            mobile: false,
            platform: '$uadPlatform',
            getHighEntropyValues: function() {
              return Promise.resolve({
                architecture: '$uadArch', bitness: '64', model: '',
                platform: '$uadPlatform', platformVersion: '$uadPlatVer',
                uaFullVersion: '$fullVer',
                fullVersionList: $brandsLong
              });
            }
          };
        }, configurable: true
      });
    }
  } catch(e) {}""" else "  /* 25. navigator.userAgentData — skipped for Firefox */"

        // Block 27: oscpu is Firefox-only
        val oscpuBlock = when {
            profile.isFirefox && !profile.isMac ->
                "  /* 27. navigator.oscpu (Firefox/Windows) */\n  try { Object.defineProperty(navigator, 'oscpu', { get: function(){ return 'Windows NT 10.0; Win64; x64'; }, configurable:true }); } catch(e) {}"
            profile.isFirefox && profile.isMac  ->
                "  /* 27. navigator.oscpu (Firefox/macOS) */\n  try { Object.defineProperty(navigator, 'oscpu', { get: function(){ return 'Intel Mac OS X 14.4'; }, configurable:true }); } catch(e) {}"
            else -> "  /* 27. navigator.oscpu — not present in Chrome/Edge */"
        }

        // Compute concrete innerHeight: screen minus ~63px browser chrome (Win) or 83px (Mac)
        val innerH = profile.screenAvailHeight - if (profile.isMac) 83 else 63

        val js = """
(function() {
  if (window.__antiDetectApplied) return;
  window.__antiDetectApplied = true;

  /* 1. navigator.webdriver */
  try {
    Object.defineProperty(navigator, 'webdriver', { get: function(){ return false; }, configurable: true });
  } catch(e) {}

  /* 2. navigator.plugins — 5-entry PDF plugin array */
  try {
    var fakePl = [
      { name: 'PDF Viewer',                filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
      { name: 'Chrome PDF Viewer',         filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
      { name: 'Chromium PDF Viewer',       filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
      { name: 'Microsoft Edge PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
      { name: 'WebKit built-in PDF',       filename: 'internal-pdf-viewer', description: 'Portable Document Format' }
    ];
    Object.defineProperty(navigator, 'plugins', {
      get: function() {
        var arr = Object.create(PluginArray.prototype);
        arr.length = fakePl.length;
        fakePl.forEach(function(p, i) { arr[i] = p; });
        arr.item      = function(i) { return fakePl[i] || null; };
        arr.namedItem = function(n) { return fakePl.find(function(p){ return p.name === n; }) || null; };
        arr.refresh   = function() {};
        return arr;
      }, configurable: true
    });
  } catch(e) {}

  /* 3. navigator.mimeTypes — matches the plugin list above */
  try {
    var fakeMT = [
      { type: 'application/pdf', suffixes: 'pdf', description: 'Portable Document Format' },
      { type: 'text/pdf',        suffixes: 'pdf', description: 'Portable Document Format' }
    ];
    Object.defineProperty(navigator, 'mimeTypes', {
      get: function() {
        var arr = Object.create(MimeTypeArray.prototype);
        arr.length = fakeMT.length;
        fakeMT.forEach(function(m, i) { arr[i] = m; });
        arr.item      = function(i) { return fakeMT[i] || null; };
        arr.namedItem = function(n) { return fakeMT.find(function(m){ return m.type === n; }) || null; };
        return arr;
      }, configurable: true
    });
  } catch(e) {}

  /* 4. navigator.languages / language */
  try {
    Object.defineProperty(navigator, 'languages', { get: function(){ return [$langsJson]; }, configurable: true });
    Object.defineProperty(navigator, 'language',  { get: function(){ return '${profile.language}'; }, configurable: true });
  } catch(e) {}

  /* 5. navigator.platform */
  try {
    Object.defineProperty(navigator, 'platform', { get: function(){ return '${profile.platform}'; }, configurable: true });
  } catch(e) {}

  /* 6. navigator.maxTouchPoints — 0 = desktop (no touch screen) */
  try {
    Object.defineProperty(navigator, 'maxTouchPoints', { get: function(){ return 0; }, configurable: true });
  } catch(e) {}

  /* 7. navigator.doNotTrack */
  try {
    Object.defineProperty(navigator, 'doNotTrack', { get: function(){ return $dntLiteral; }, configurable: true });
  } catch(e) {}

  /* 8. navigator.cookieEnabled */
  try {
    Object.defineProperty(navigator, 'cookieEnabled', { get: function(){ return true; }, configurable: true });
  } catch(e) {}

  /* 9. Permissions API */
  try {
    var _origQuery = navigator.permissions.query.bind(navigator.permissions);
    navigator.permissions.query = function(params) {
      if (params.name === 'notifications') return Promise.resolve({ state: Notification.permission, onchange: null });
      return _origQuery(params);
    };
  } catch(e) {}

  /* 10. window.chrome runtime object */
  try {
    if (!window.chrome || !window.chrome.runtime) {
      var ts = Date.now() / 1000;
      window.chrome = {
        app: { isInstalled: false,
               InstallState: { DISABLED:'disabled', INSTALLED:'installed', NOT_INSTALLED:'not_installed' },
               RunningState: { CANNOT_RUN:'cannot_run', READY_TO_RUN:'ready_to_run', RUNNING:'running' } },
        csi: function() { return { startE: ts, onloadT: ts + 0.4, pageT: 400, tran: 15 }; },
        loadTimes: function() {
          return {
            commitLoadTime: ts - 0.3, connectionInfo: 'h2',
            finishDocumentLoadTime: ts - 0.05, finishLoadTime: ts,
            firstPaintAfterLoadTime: 0, firstPaintTime: ts - 0.2,
            navigationType: 'Other', npnNegotiatedProtocol: 'h2',
            requestTime: ts - 0.5, startLoadTime: ts - 0.5,
            wasAlternateProtocolAvailable: false, wasFetchedViaSpdy: true, wasNpnNegotiated: true
          };
        },
        runtime: {
          OnInstalledReason: { CHROME_UPDATE:'chrome_update', INSTALL:'install', SHARED_MODULE_UPDATE:'shared_module_update', UPDATE:'update' },
          OnRestartRequiredReason: { APP_UPDATE:'app_update', OS_UPDATE:'os_update', PERIODIC:'periodic' },
          PlatformArch: { ARM:'arm', ARM64:'arm64', MIPS:'mips', MIPS64:'mips64', X86_32:'x86-32', X86_64:'x86-64' },
          PlatformNaclArch: { ARM:'arm', MIPS:'mips', MIPS64:'mips64', X86_32:'x86-32', X86_64:'x86-64' },
          PlatformOs: { ANDROID:'android', CROS:'cros', LINUX:'linux', MAC:'mac', OPENBSD:'openbsd', WIN:'win' },
          RequestUpdateCheckStatus: { NO_UPDATE:'no_update', THROTTLED:'throttled', UPDATE_AVAILABLE:'update_available' }
        }
      };
    }
  } catch(e) {}

  /* 11. Canvas toDataURL — per-account noise seed */
  try {
    var _nR = ${profile.canvasNoiseR}, _nG = ${profile.canvasNoiseG};
    var _origTDU = HTMLCanvasElement.prototype.toDataURL;
    HTMLCanvasElement.prototype.toDataURL = function(type) {
      if (this.width > 0 && this.height > 0) {
        var ctx = this.getContext && this.getContext('2d');
        if (ctx && !this.__noiseDoneTDU) {
          this.__noiseDoneTDU = true;
          var id = ctx.getImageData(0, 0, 1, 1);
          id.data[0] = Math.min(255, id.data[0] + _nR);
          id.data[1] = Math.min(255, id.data[1] + _nG);
          ctx.putImageData(id, 0, 0);
        }
      }
      return _origTDU.apply(this, arguments);
    };
  } catch(e) {}

  /* 12. Canvas toBlob — same noise seed, separate dirty flag */
  try {
    var _origTB = HTMLCanvasElement.prototype.toBlob;
    if (_origTB) {
      HTMLCanvasElement.prototype.toBlob = function(callback, type, quality) {
        if (this.width > 0 && this.height > 0) {
          var ctx = this.getContext && this.getContext('2d');
          if (ctx && !this.__noiseDoneTB) {
            this.__noiseDoneTB = true;
            var id = ctx.getImageData(0, 0, 1, 1);
            id.data[0] = Math.min(255, id.data[0] + _nR);
            id.data[1] = Math.min(255, id.data[1] + _nG);
            ctx.putImageData(id, 0, 0);
          }
        }
        return _origTB.apply(this, arguments);
      };
    }
  } catch(e) {}

  /* 13. WebGL getParameter — per-account GPU vendor/renderer */
  try {
    var _glV = '${profile.webGlVendor}', _glR = '${profile.webGlRenderer}';
    var _patchWebGL = function(ctx) {
      var _origGP = ctx.getParameter.bind(ctx);
      ctx.getParameter = function(p) {
        if (p === 37445) return _glV;
        if (p === 37446) return _glR;
        return _origGP(p);
      };
    };
    var _origGetCtx = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function(type) {
      var ctx = _origGetCtx.apply(this, arguments);
      if (ctx && (type === 'webgl' || type === 'webgl2' || type === 'experimental-webgl')) _patchWebGL(ctx);
      return ctx;
    };
  } catch(e) {}

  /* 14. WebGL getShaderPrecisionFormat — realistic high-precision values */
  try {
    var _origGetCtx2 = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = (function(_prev) {
      return function(type) {
        var ctx = _prev.apply(this, arguments);
        if (ctx && (type === 'webgl' || type === 'webgl2' || type === 'experimental-webgl')) {
          var _origGSPF = ctx.getShaderPrecisionFormat && ctx.getShaderPrecisionFormat.bind(ctx);
          if (_origGSPF) {
            ctx.getShaderPrecisionFormat = function(shType, prType) {
              var r = _origGSPF(shType, prType);
              return r || { rangeMin: 127, rangeMax: 127, precision: 23 };
            };
          }
        }
        return ctx;
      };
    })(HTMLCanvasElement.prototype.getContext);
  } catch(e) {}

  /* 15. screen / window dimensions — per-account resolution */
  try {
    var _sw = ${profile.screenWidth}, _sh = ${profile.screenHeight},
        _sa = ${profile.screenAvailHeight}, _cd = ${profile.colorDepth},
        _pr = ${profile.pixelRatio};
    var sp = { width:_sw, height:_sh, availWidth:_sw, availHeight:_sa, colorDepth:_cd, pixelDepth:_cd };
    Object.keys(sp).forEach(function(k) {
      try { Object.defineProperty(screen, k, { get: function(){ return sp[k]; }, configurable:true }); } catch(ee) {}
    });
    Object.defineProperty(window, 'outerWidth',       { get: function(){ return _sw; },     configurable:true });
    Object.defineProperty(window, 'outerHeight',      { get: function(){ return _sh; },     configurable:true });
    Object.defineProperty(window, 'innerWidth',       { get: function(){ return _sw; },     configurable:true });
    Object.defineProperty(window, 'innerHeight',      { get: function(){ return $innerH; }, configurable:true });
    Object.defineProperty(window, 'devicePixelRatio', { get: function(){ return _pr; },     configurable:true });
  } catch(e) {}

  /* 16. screen.orientation — landscape-primary */
  try {
    if (window.screen && window.screen.orientation) {
      Object.defineProperty(screen.orientation, 'type',  { get: function(){ return 'landscape-primary'; }, configurable:true });
      Object.defineProperty(screen.orientation, 'angle', { get: function(){ return 0; }, configurable:true });
    }
  } catch(e) {}

  /* 17. Delete automation globals */
  try {
    ['callPhantom','_phantom','phantom','__nightmare','domAutomation','domAutomationController',
     '_Selenium_IDE_Recorder','__webdriver_script_fn','__driver_evaluate','__webdriver_evaluate',
     '__selenium_evaluate','__fxdriver_evaluate','__driver_unwrapped','__webdriver_unwrapped',
     '__selenium_unwrapped','__fxdriver_unwrapped'].forEach(function(k) {
      try { delete window[k]; } catch(ee) {}
    });
  } catch(e) {}

  /* 18. navigator.hardwareConcurrency / deviceMemory — per-account */
  try {
    Object.defineProperty(navigator, 'hardwareConcurrency', { get: function(){ return ${profile.hardwareConcurrency}; }, configurable:true });
    Object.defineProperty(navigator, 'deviceMemory',        { get: function(){ return ${profile.deviceMemory}; }, configurable:true });
  } catch(e) {}

  /* 19. navigator.connection — realistic 4G values */
  try {
    if (navigator.connection) {
      Object.defineProperty(navigator.connection, 'rtt',           { get: function(){ return 50;   }, configurable:true });
      Object.defineProperty(navigator.connection, 'downlink',      { get: function(){ return 10;   }, configurable:true });
      Object.defineProperty(navigator.connection, 'effectiveType', { get: function(){ return '4g'; }, configurable:true });
      Object.defineProperty(navigator.connection, 'saveData',      { get: function(){ return false; }, configurable:true });
    }
  } catch(e) {}

  /* 20. AudioContext analyser — per-account frequency-data noise */
  try {
    var _ACtx = window.AudioContext || window.webkitAudioContext;
    if (_ACtx) {
      var _origCA = _ACtx.prototype.createAnalyser;
      _ACtx.prototype.createAnalyser = function() {
        var an = _origCA.apply(this, arguments);
        var _origF32 = an.getFloatFrequencyData.bind(an);
        an.getFloatFrequencyData = function(array) {
          _origF32(array);
          for (var i = 0; i < array.length; i++) array[i] += (Math.random() - 0.5) * $audioNoise;
        };
        return an;
      };
    }
  } catch(e) {}

  /* 21. performance.now() — sub-ms jitter defeats timing fingerprints */
  try {
    var _origPN = performance.now.bind(performance);
    performance.now = function() { return _origPN() + (Math.random() * 0.02); };
  } catch(e) {}

  /* 22. RTCPeerConnection — strip ICE servers (prevent local-IP leak) */
  try {
    if (window.RTCPeerConnection) {
      var _origRTC = window.RTCPeerConnection;
      var _pRTC = function(cfg, con) {
        if (cfg && cfg.iceServers) cfg.iceServers = [];
        return new _origRTC(cfg, con);
      };
      _pRTC.prototype = _origRTC.prototype;
      window.RTCPeerConnection = _pRTC;
    }
  } catch(e) {}

  /* 23. navigator.getBattery — fully charged */
  try {
    if (navigator.getBattery) {
      navigator.getBattery = function() {
        return Promise.resolve({
          charging: true, chargingTime: 0, dischargingTime: Infinity, level: 1.0,
          addEventListener: function(){}, removeEventListener: function(){}
        });
      };
    }
  } catch(e) {}

  /* 24. navigator.vendor */
  try {
    Object.defineProperty(navigator, 'vendor', { get: function(){ return 'Google Inc.'; }, configurable:true });
  } catch(e) {}

$uadBlock

  /* 26. Date.prototype.getTimezoneOffset — per-account timezone */
  try {
    var _tzo = ${profile.timezoneOffset};
    Date.prototype.getTimezoneOffset = function() { return _tzo; };
  } catch(e) {}

$oscpuBlock
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Convenience overload: generate a fresh [FingerprintProfile] and inject it immediately.
     * Use this for manual/non-account navigations (the "Go" button).
     */
    fun injectAntiDetection(webView: WebView) = injectAntiDetection(webView, generateProfile())
}
