package com.educational.discordcreator

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ReqCreator — Direct HTTP request-based Discord account creation engine (v3)
 *
 * Flow per account:
 *  1. Generate a fresh per-account FingerprintProfile
 *  2. Fetch Discord experiments fingerprint (X-Fingerprint header)
 *  3. POST /api/v9/auth/register
 *  4. On captcha_key response → fire onCaptchaRequired callback and block until
 *     the UI returns a solved token (CountDownLatch, 5-minute timeout)
 *  5. Retry register with captcha token
 *  6. On success → save account, fire onAccountSuccess
 *  7. On rate-limit → back off with server-provided delay
 *  8. Configurable per-account delay to avoid flooding
 *
 * All network I/O runs on a dedicated background thread.
 * All callbacks must be dispatched to the UI thread by the caller.
 */
object ReqCreator {

    interface Listener {
        /** Log line ready for display */
        fun onLog(msg: String)
        /** CAPTCHA is required — show solver UI; call onSolved(token) when done */
        fun onCaptchaRequired(
            siteKey: String,
            service: String,
            rqToken: String,
            accData: AccountInfo,
            onSolved: (String) -> Unit
        )
        /** Account created successfully */
        fun onAccountSuccess(acc: AccountInfo)
        /** Account failed permanently */
        fun onAccountFailed(acc: AccountInfo, reason: String)
        /** All accounts processed */
        fun onDone(success: Int, failed: Int)
    }

    @Volatile private var stopped = false
    @Volatile private var runningThread: Thread? = null

    fun stop() { stopped = true }

    fun isRunning(): Boolean = runningThread?.isAlive == true

    fun createAccounts(
        accounts: List<AccountInfo>,
        filesDir: File,
        listener: Listener
    ) {
        stopped = false
        val t = Thread {
            var successCount = 0
            var failCount    = 0

            // Warm up build number on first run
            listener.onLog("[ REQ ] Fetching Discord client build number...")
            DiscordApiClient.fetchAndCacheBuildNumber()
            listener.onLog("[ REQ ] Build #${DiscordApiClient.getCurrentBuildNumber()} ready")

            for ((index, acc) in accounts.withIndex()) {
                if (stopped) break

                val profile = AntiBanManager.generateProfile()
                listener.onLog("")
                listener.onLog("[ REQ ${index + 1}/${accounts.size} ] ${acc.email}")
                listener.onLog("   UA : ${profile.userAgent.take(55)}...")
                listener.onLog("   TZ : ${profile.timezone}")

                val ok = createOne(acc, profile, filesDir, listener)
                if (ok) successCount++ else failCount++

                if (!stopped && index < accounts.size - 1) {
                    val delay = (10_000L..30_000L).random()
                    listener.onLog("   Cooldown ${delay / 1000}s before next account...")
                    safeSleep(delay)
                }
            }

            if (!stopped) listener.onDone(successCount, failCount)
        }
        runningThread = t
        t.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun createOne(
        acc: AccountInfo,
        profile: FingerprintProfile,
        filesDir: File,
        listener: Listener
    ): Boolean {

        // Step 1 — fingerprint
        listener.onLog("   Fetching fingerprint...")
        val fingerprint = DiscordApiClient.getDiscordFingerprint(profile)
        if (fingerprint.isNotBlank()) {
            listener.onLog("   FP  : ${fingerprint.take(24)}...")
        } else {
            listener.onLog("   FP  : (unavailable — proceeding without)")
        }

        // Step 2 — first register attempt
        var attempt = 0
        while (attempt < 3 && !stopped) {
            attempt++
            listener.onLog("   Attempt $attempt → POST /auth/register")
            val result = DiscordApiClient.register(profile, acc, fingerprint = fingerprint)
            listener.onLog("   HTTP ${result.httpCode}")

            when {
                // ── Success ──────────────────────────────────────────────────
                result.token.isNotBlank() -> {
                    return handleSuccess(acc, result.token, filesDir, listener)
                }

                // ── CAPTCHA required ─────────────────────────────────────────
                result.captchaSiteKey.isNotBlank() -> {
                    listener.onLog("   CAPTCHA required (${result.captchaService})")
                    listener.onLog("   Sitekey: ${result.captchaSiteKey.take(18)}...")

                    var solvedToken = ""
                    val latch = CountDownLatch(1)

                    listener.onCaptchaRequired(
                        result.captchaSiteKey,
                        result.captchaService,
                        result.captchaRqToken,
                        acc
                    ) { token ->
                        solvedToken = token
                        latch.countDown()
                    }

                    val timedOut = !latch.await(300, TimeUnit.SECONDS)
                    if (timedOut || solvedToken.isBlank()) {
                        listener.onLog("   CAPTCHA not solved in time — skipping")
                        acc.status = "error"
                        listener.onAccountFailed(acc, "CAPTCHA timeout")
                        return false
                    }

                    listener.onLog("   CAPTCHA solved! Token: ${solvedToken.take(20)}...")
                    listener.onLog("   Retrying register with captcha token...")

                    val r2 = DiscordApiClient.register(
                        profile, acc,
                        captchaToken   = solvedToken,
                        captchaService = result.captchaService,
                        captchaRqToken = result.captchaRqToken,
                        fingerprint    = fingerprint
                    )
                    listener.onLog("   HTTP ${r2.httpCode}")

                    return when {
                        r2.token.isNotBlank() ->
                            handleSuccess(acc, r2.token, filesDir, listener)
                        r2.captchaSiteKey.isNotBlank() -> {
                            listener.onLog("   CAPTCHA still required after solve — giving up")
                            acc.status = "error"
                            listener.onAccountFailed(acc, "Double CAPTCHA")
                            false
                        }
                        r2.retryAfterMs > 0 -> {
                            listener.onLog("   Rate limited after CAPTCHA solve — waiting ${r2.retryAfterMs / 1000}s")
                            safeSleep(minOf(r2.retryAfterMs, 300_000L))
                            // One more attempt after rate-limit
                            val r3 = DiscordApiClient.register(
                                profile, acc,
                                captchaToken   = solvedToken,
                                captchaService = result.captchaService,
                                captchaRqToken = result.captchaRqToken,
                                fingerprint    = fingerprint
                            )
                            if (r3.token.isNotBlank()) handleSuccess(acc, r3.token, filesDir, listener)
                            else {
                                acc.status = "error"
                                listener.onAccountFailed(acc, r3.error.take(120))
                                false
                            }
                        }
                        else -> {
                            listener.onLog("   [ ERR ] ${r2.error.take(150)}")
                            acc.status = "error"
                            listener.onAccountFailed(acc, r2.error.take(120))
                            false
                        }
                    }
                }

                // ── Rate limited ──────────────────────────────────────────────
                result.retryAfterMs > 0 -> {
                    val wait = minOf(result.retryAfterMs, 300_000L)
                    listener.onLog("   Rate limited — waiting ${wait / 1000}s...")
                    safeSleep(wait)
                    // attempt++ loop will retry
                }

                // ── Server error — brief backoff then retry ────────────────────
                result.httpCode in 500..599 -> {
                    listener.onLog("   Server error ${result.httpCode} — waiting 10s...")
                    safeSleep(10_000L)
                }

                // ── Other error ───────────────────────────────────────────────
                else -> {
                    listener.onLog("   [ ERR ] HTTP ${result.httpCode}: ${result.error.take(140)}")
                    if (attempt < 3) {
                        safeSleep(5_000L)
                    } else {
                        acc.status = "error"
                        listener.onAccountFailed(acc, result.error.take(120))
                        return false
                    }
                }
            }
        }

        acc.status = "error"
        listener.onAccountFailed(acc, "Max retries exceeded")
        return false
    }

    private fun handleSuccess(
        acc: AccountInfo,
        token: String,
        filesDir: File,
        listener: Listener
    ): Boolean {
        acc.token  = token
        acc.status = "done"
        AccountManager.saveAccount(filesDir, acc)
        listener.onLog("   [ OK ] Token: ${token.take(24)}...")
        listener.onLog("   Saved to acc.txt + tokens.txt")
        listener.onAccountSuccess(acc)
        return true
    }

    private fun safeSleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { stopped = true }
    }
}
