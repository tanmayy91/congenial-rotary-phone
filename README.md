# Discord Account Creator — Educational Demo v2

> **FOR EDUCATIONAL AND RESEARCH PURPOSES ONLY.**
> Demonstrates WebView automation, JavaScript bridge injection,
> React form interaction, token interception, fingerprint spoofing,
> and advanced CAPTCHA bypass research techniques.

---

## What's New in v2

| Feature | v1 | v2 |
|---|---|---|
| Version | 1.0 | **2.0** |
| Anti-detection signals | 27 | **30** (+ Intl, font-list, MediaDevices) |
| Chrome user agents | 121–124 | **123–127** |
| Local hCaptcha solver | ❌ | **✅ 6 strategies, no API key** |
| Per-account fingerprint | Fresh per page | **Stored for entire session** |
| CAPTCHA watcher timeout | 120 s | **240 s** |
| Rate-limit backoff | Fixed | **Exponential backoff** |
| Stats display | ❌ | **✅ success / fail counter** |
| Mouse movement | Linear | **Bezier curve** |
| hCaptcha cookie bypass | Manual | **Auto-injected each session** |

---

## Architecture

```
MainActivity.kt          — Central controller, JS bridge, UI
AntiBanManager.kt        — 30-signal fingerprint spoofer (per-account profile)
CaptchaSolver.kt         — Standard CAPTCHA bypass strategies
LocalHCaptchaSolver.kt   — v2: 6-strategy local hCaptcha bypass engine
AccountManager.kt        — Account generation + file I/O
GroqClient.kt            — Groq AI (username / display name generation)
```

### Local hCaptcha Solver Strategies

| # | Strategy | Description |
|---|---|---|
| A | Cookie Injection | Injects `hc_accessibility` cookie — silently bypasses visual challenge |
| B | HSW Hook | Intercepts hCaptcha's proof-of-work solver module |
| C | PostMessage | Intercepts hCaptcha iframe postMessage protocol |
| D | Audio Bridge | Clicks audio challenge button, captures audio URL |
| E | Token Watcher | Polls for solved token → auto-submits form |
| F | Internal API | Probes hCaptcha's checksiteconfig endpoint for bypass paths |

---

## Build (GitHub Actions)

Push to any branch — `Build Android APK v2` workflow builds the APK automatically.

Download: **Actions → workflow run → Artifacts → `discord-creator-v2-debug-apk`**

---

## Local Build

```bash
cd congenial-rotary-phone
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17+, Android SDK (compileSdk 34, minSdk 24)

---

## Usage

1. Install APK on Android 7.0+ device
2. Enter Gmail, password (≥8 chars), count (1–50)
3. *(Optional)* Add Groq API key for AI-generated names
4. Tap **▶ Start**

### CAPTCHA Handling

| Button | What it does |
|---|---|
| **Local Solver** | All 6 local bypass strategies — fires automatically on CAPTCHA detection |
| **Auto CAPTCHA** | Clicks hCaptcha widget with realistic pointer events |
| **Accessibility** | One-time: sets permanent `hc_accessibility` cookie via hcaptcha.com |
| **Click Reg.** | Manually click the Register button |

### Output

- `acc.txt` — `email:password:token` per line
- `tokens.txt` — token per line
