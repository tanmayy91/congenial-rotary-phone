package com.educational.discordcreator

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * ExternalBrowserCreator — Chrome / External-browser guided account creation (v3)
 *
 * Opens the real Chrome browser (or any installed browser) for each account.
 * Since we cannot inject JavaScript into an external browser, this mode is
 * semi-manual: the app prepares each account, opens Chrome at Discord's register
 * page, copies credentials to the clipboard, and then passively monitors the
 * clipboard for a Discord auth token.
 *
 * Flow per account:
 *  1. Display account info in the log
 *  2. Copy email address to the device clipboard
 *  3. Open Chrome at https://discord.com/register (CustomTabsIntent → plain Intent fallback)
 *  4. User fills the form; if CAPTCHA appears, they solve it manually
 *  5. On a successful registration Discord briefly shows the token in the page —
 *     the user can copy it, or use a third-party clipboard tool.
 *     Meanwhile the app polls the clipboard every 1 s for 5 minutes.
 *  6. If a token-like string is detected in the clipboard → auto-saved.
 *  7. User can manually open Gmail to verify, or tap "Copy Password" / "Copy Username".
 *  8. "Enter Token" button allows a manual paste if clipboard detection missed it.
 *
 * All UI callbacks fire on the main thread.
 */
class ExternalBrowserCreator(private val context: Activity) {

    interface Listener {
        fun onLog(msg: String)
        /** Display current account data in the UI (email, password, username, etc.) */
        fun onAccountStarted(acc: AccountInfo, index: Int, total: Int)
        /** Token was found (clipboard or manual) */
        fun onTokenFound(acc: AccountInfo, token: String)
        /** Account skipped / failed */
        fun onAccountSkipped(acc: AccountInfo, reason: String)
        /** All accounts processed */
        fun onDone(success: Int, failed: Int)
        /** Prompt user to enter / paste token manually for current account */
        fun onRequestManualToken(acc: AccountInfo, callback: (String) -> Unit)
    }

    private val handler  = Handler(Looper.getMainLooper())
    private val clipboard by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    @Volatile private var stopped = false
    @Volatile private var awaitingToken = false

    private var currentAcc: AccountInfo? = null
    private var currentListener: Listener? = null
    private var successCount = 0
    private var failCount    = 0
    private var accounts: List<AccountInfo> = emptyList()
    private var currentIndex = 0
    private var filesDir: java.io.File? = null

    /** Start processing accounts */
    fun start(
        accounts: List<AccountInfo>,
        filesDir: java.io.File,
        listener: Listener
    ) {
        stopped       = false
        successCount  = 0
        failCount     = 0
        currentIndex  = 0
        this.accounts = accounts
        this.filesDir = filesDir
        this.currentListener = listener
        processNextAccount()
    }

    fun stop() {
        stopped       = false   // intentional: reset so can be restarted
        awaitingToken = false
        currentListener = null
        stopClipboardWatcher()
    }

    // ── Account iteration ──────────────────────────────────────────────────────

    private fun processNextAccount() {
        if (stopped || currentIndex >= accounts.size) {
            currentListener?.onDone(successCount, failCount)
            return
        }

        val acc = accounts[currentIndex]
        currentAcc = acc

        currentListener?.onAccountStarted(acc, currentIndex, accounts.size)
        currentListener?.onLog("")
        currentListener?.onLog("[ CHROME ${currentIndex + 1}/${accounts.size} ] ${acc.email}")
        currentListener?.onLog("   Username : ${acc.username}")
        currentListener?.onLog("   Password : ${acc.password}")
        currentListener?.onLog("   DOB      : ${acc.birthMonth}/${acc.birthDay}/${acc.birthYear}")
        currentListener?.onLog("   Action   : Open Chrome → fill form → return here")

        // Copy email to clipboard so the user can paste it
        setClipboard(acc.email)
        currentListener?.onLog("   ✓ Email copied to clipboard")

        // Open Chrome at Discord register
        openDiscordRegister()

        // Start watching clipboard for a Discord token
        startClipboardWatcher(acc)
    }

    // ── Chrome / browser launcher ──────────────────────────────────────────────

    fun openDiscordRegister() {
        openUrl("https://discord.com/register")
        currentListener?.onLog("   Chrome opened → https://discord.com/register")
        currentListener?.onLog("   Paste your email (already copied), fill the form, solve CAPTCHA if shown")
    }

    fun openGmail() {
        currentListener?.onLog("   Opening Gmail for email verification...")
        val gmailPackage = "com.google.android.gm"
        val launch = try {
            context.packageManager.getLaunchIntentForPackage(gmailPackage)
        } catch (_: Exception) { null }

        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        } else {
            // Fallback: open Gmail in browser
            openUrl("https://mail.google.com")
            currentListener?.onLog("   Gmail not found — opened mail.google.com in browser")
        }
    }

    fun openDiscordInbox(email: String) {
        currentListener?.onLog("   Opening Discord verification page...")
        openUrl("https://discord.com/login")
    }

    private fun openUrl(url: String) {
        try {
            // Try Chrome first
            val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            chromeIntent.setPackage("com.android.chrome")
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(chromeIntent)
                return
            } catch (_: Exception) {}
            // Fallback: any browser
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            currentListener?.onLog("   [ ERR ] Could not open browser: ${e.message}")
        }
    }

    // ── Clipboard helpers ──────────────────────────────────────────────────────

    fun copyEmailToClipboard() {
        currentAcc?.let {
            setClipboard(it.email)
            currentListener?.onLog("   ✓ Email copied: ${it.email}")
        }
    }

    fun copyPasswordToClipboard() {
        currentAcc?.let {
            setClipboard(it.password)
            currentListener?.onLog("   ✓ Password copied to clipboard")
        }
    }

    fun copyUsernameToClipboard() {
        currentAcc?.let {
            setClipboard(it.username)
            currentListener?.onLog("   ✓ Username copied: ${it.username}")
        }
    }

    private fun setClipboard(text: String) {
        try {
            val clip = android.content.ClipData.newPlainText("discord", text)
            clipboard.setPrimaryClip(clip)
        } catch (_: Exception) {}
    }

    private fun getClipboard(): String {
        return try {
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
        } catch (_: Exception) { "" }
    }

    // ── Clipboard token watcher ────────────────────────────────────────────────

    private var clipWatcherHandle: Runnable? = null
    private var clipWatcherPolls = 0

    private fun startClipboardWatcher(acc: AccountInfo) {
        awaitingToken = true
        clipWatcherPolls = 0
        stopClipboardWatcher()

        val watcher = object : Runnable {
            override fun run() {
                if (stopped || !awaitingToken) return
                clipWatcherPolls++

                val clip = getClipboard()
                if (isDiscordToken(clip)) {
                    awaitingToken = false
                    stopClipboardWatcher()
                    currentListener?.onLog("   ✓ Discord token detected in clipboard!")
                    handleTokenFound(acc, clip)
                    return
                }

                // Timeout after 5 minutes (300 polls × 1s)
                if (clipWatcherPolls >= 300) {
                    awaitingToken = false
                    stopClipboardWatcher()
                    currentListener?.onLog("   ⚠ No token detected in 5 min — requesting manual entry")
                    promptManualToken(acc)
                    return
                }

                if (clipWatcherPolls % 30 == 0) {
                    currentListener?.onLog("   ⏳ Waiting for token… (${clipWatcherPolls}s)")
                }

                handler.postDelayed(this, 1_000)
            }
        }
        clipWatcherHandle = watcher
        handler.postDelayed(watcher, 2_000)
    }

    private fun stopClipboardWatcher() {
        clipWatcherHandle?.let { handler.removeCallbacks(it) }
        clipWatcherHandle = null
    }

    /** Called when user taps "Enter Token" button manually */
    fun promptManualTokenNow() {
        val acc = currentAcc ?: return
        stopClipboardWatcher()
        awaitingToken = false
        promptManualToken(acc)
    }

    /** Skip current account */
    fun skipCurrentAccount() {
        val acc = currentAcc ?: return
        stopClipboardWatcher()
        awaitingToken = false
        acc.status = "error"
        failCount++
        currentListener?.onAccountSkipped(acc, "Skipped by user")
        currentIndex++
        handler.postDelayed({ processNextAccount() }, 2_000)
    }

    private fun promptManualToken(acc: AccountInfo) {
        currentListener?.onRequestManualToken(acc) { token ->
            if (token.isNotBlank() && isDiscordToken(token)) {
                handleTokenFound(acc, token)
            } else {
                currentListener?.onLog("   Skipping — token blank or invalid")
                acc.status = "error"
                failCount++
                currentListener?.onAccountSkipped(acc, "No token provided")
                currentIndex++
                handler.postDelayed({ processNextAccount() }, 1_500)
            }
        }
    }

    private fun handleTokenFound(acc: AccountInfo, token: String) {
        acc.token  = token.trim()
        acc.status = "done"
        filesDir?.let { AccountManager.saveAccount(it, acc) }
        successCount++
        currentListener?.onLog("   [ OK ] Token saved: ${token.take(24)}...")
        currentListener?.onTokenFound(acc, token)
        currentIndex++
        val delay = (5_000L..12_000L).random()
        currentListener?.onLog("   Next account in ${delay / 1000}s...")
        handler.postDelayed({ if (!stopped) processNextAccount() }, delay)
    }

    /**
     * Called by MainActivity when it detects a token automatically (Chrome stealth mode).
     * Bypasses the clipboard watcher and immediately saves + advances.
     */
    fun injectToken(acc: AccountInfo, token: String) {
        if (!isDiscordToken(token)) return
        stopClipboardWatcher()
        awaitingToken = false
        handler.post { handleTokenFound(acc, token) }
    }

    // ── Token detection ────────────────────────────────────────────────────────

    /**
     * A Discord auth token looks like:
     *   <base64_user_id>.<timestamp_base64>.<hmac>
     * or in the older format:
     *   MTA4…   (a long base64-like string ≥ 59 chars with dots)
     *
     * We use a conservative heuristic to avoid false positives.
     */
    private fun isDiscordToken(s: String): Boolean {
        val t = s.trim()
        if (t.length < 58) return false
        if (t.contains(' ') || t.contains('\n')) return false
        if (t.contains('{') || t.contains('[')) return false
        if (t.startsWith("http")) return false
        // New-format token: two dots, three base64 segments
        val parts = t.split('.')
        if (parts.size == 3 &&
            parts[0].length >= 20 &&
            parts[1].length >= 6 &&
            parts[2].length >= 27) {
            return t.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' }
        }
        // Old-format: long base64-like string
        if (t.length >= 70 && t.all { c -> c.isLetterOrDigit() || c in "._-" }) {
            return true
        }
        return false
    }
}
