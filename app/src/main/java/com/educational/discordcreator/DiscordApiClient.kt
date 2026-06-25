package com.educational.discordcreator

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * DiscordApiClient — low-level HTTP layer for the Discord v9 API
 *
 * Responsibilities:
 *  • Build authentic-looking X-Super-Properties headers from a FingerprintProfile
 *  • Dynamically fetch Discord's latest client build number
 *  • Obtain the Discord experiments fingerprint (X-Fingerprint header)
 *  • Execute the /auth/register POST and parse every possible response shape
 *
 * All methods are blocking — always call from a background thread.
 */
object DiscordApiClient {

    private const val API_BASE = "https://discord.com/api/v9"

    data class RegisterResult(
        val token: String = "",
        val captchaSiteKey: String = "",
        val captchaService: String = "",
        val captchaRqToken: String = "",
        val retryAfterMs: Long = 0L,
        val error: String = "",
        val httpCode: Int = 0
    )

    // ── Build number cache ─────────────────────────────────────────────────────
    @Volatile private var cachedBuildNumber: Int = 362419

    fun getCurrentBuildNumber(): Int = cachedBuildNumber

    /**
     * Attempt to scrape Discord's current client build number from their deployed JS.
     * Falls back gracefully to the hardcoded value if network fails.
     */
    fun fetchAndCacheBuildNumber() {
        try {
            val pageConn = URL("https://discord.com/login").openConnection() as HttpURLConnection
            pageConn.connectTimeout = 12_000
            pageConn.readTimeout   = 15_000
            pageConn.setRequestProperty("User-Agent", AntiBanManager.getRandomUserAgent())
            pageConn.setRequestProperty("Accept", "text/html")
            val html = pageConn.inputStream.bufferedReader().readText()
            pageConn.disconnect()

            // Find the last chunk JS file referenced in the HTML
            val matches = Regex("""assets/(\w+)\.js""").findAll(html).toList()
            if (matches.isNotEmpty()) {
                // Try the last 3 script references — build number is usually in a late chunk
                for (m in matches.takeLast(3)) {
                    val jsUrl = "https://discord.com/assets/${m.groupValues[1]}.js"
                    try {
                        val jsConn = URL(jsUrl).openConnection() as HttpURLConnection
                        jsConn.connectTimeout = 10_000
                        jsConn.readTimeout   = 20_000
                        jsConn.setRequestProperty("User-Agent", AntiBanManager.getRandomUserAgent())
                        val js = jsConn.inputStream.bufferedReader().readLimited(600_000)
                        jsConn.disconnect()
                        val bn = Regex("""buildNumber[":,\s]+?(\d{5,7})""").find(js)
                        if (bn != null) {
                            val num = bn.groupValues[1].toIntOrNull()
                            if (num != null && num > 100_000) {
                                cachedBuildNumber = num
                                return
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        // Keep fallback value
    }

    // ── Super-Properties ───────────────────────────────────────────────────────

    fun buildSuperProperties(profile: FingerprintProfile): String {
        val browser = when {
            profile.isFirefox -> "Firefox"
            profile.isEdge    -> "Edge"
            else              -> "Chrome"
        }
        val os = if (profile.isMac) "Mac OS X" else "Windows"
        val osVersion = if (profile.isMac) "10.15.7" else "10"
        val browserVersion = when {
            profile.isFirefox                       -> "127.0"
            profile.chromeMajorVersion > 0           -> "${profile.chromeMajorVersion}.0.0.0"
            else                                     -> "127.0.0.0"
        }

        val json = JSONObject()
            .put("os", os)
            .put("browser", browser)
            .put("device", "")
            .put("system_locale", profile.language)
            .put("browser_user_agent", profile.userAgent)
            .put("browser_version", browserVersion)
            .put("os_version", osVersion)
            .put("referrer", "")
            .put("referring_domain", "")
            .put("referrer_current", "")
            .put("referring_domain_current", "")
            .put("release_channel", "stable")
            .put("client_build_number", cachedBuildNumber)
            .put("client_event_source", JSONObject.NULL)

        return Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    // ── Fingerprint ────────────────────────────────────────────────────────────

    /**
     * Calls /experiments to retrieve Discord's server-side fingerprint.
     * This is used as the X-Fingerprint header on subsequent API calls and
     * signals to Discord that the client has been "seen" before registering.
     */
    fun getDiscordFingerprint(profile: FingerprintProfile): String {
        return try {
            val conn = URL("$API_BASE/experiments").openConnection() as HttpURLConnection
            applyStandardHeaders(conn, profile)
            conn.connectTimeout = 12_000
            conn.readTimeout    = 12_000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                JSONObject(body).optString("fingerprint", "")
            } else {
                conn.disconnect()
                ""
            }
        } catch (_: Exception) { "" }
    }

    // ── Register ───────────────────────────────────────────────────────────────

    fun register(
        profile: FingerprintProfile,
        acc: AccountInfo,
        captchaToken: String = "",
        captchaService: String = "hcaptcha",
        captchaRqToken: String = "",
        fingerprint: String = ""
    ): RegisterResult {
        return try {
            val conn = URL("$API_BASE/auth/register").openConnection() as HttpURLConnection
            applyStandardHeaders(conn, profile, fingerprint)
            conn.requestMethod = "POST"
            conn.doOutput      = true

            val dob = "%04d-%02d-%02d".format(acc.birthYear, acc.birthMonth, acc.birthDay)
            val payload = JSONObject()
                .put("username", acc.username)
                .put("email", acc.email)
                .put("password", acc.password)
                .put("date_of_birth", dob)
                .put("consent", true)
                .put("gift_code_sku_id", JSONObject.NULL)
                .put("promotional_email_opt_in", false)

            if (captchaToken.isNotBlank()) {
                payload.put("captcha_key", captchaToken)
                if (captchaRqToken.isNotBlank()) payload.put("captcha_rqtoken", captchaRqToken)
                else payload.put("captcha_rqtoken", JSONObject.NULL)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

            val code   = conn.responseCode
            val stream = if (code < 400) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val body   = stream.bufferedReader().readText()
            conn.disconnect()

            parseRegisterResponse(code, body)
        } catch (e: Exception) {
            RegisterResult(error = e.message ?: "Network error", httpCode = -1)
        }
    }

    // ── Response parser ────────────────────────────────────────────────────────

    private fun parseRegisterResponse(code: Int, body: String): RegisterResult {
        return try {
            val json = JSONObject(body)
            when {
                json.has("token") -> {
                    RegisterResult(token = json.getString("token"), httpCode = code)
                }
                json.has("captcha_key") -> {
                    val siteKey   = json.optString("captcha_sitekey", "4c672d35-0701-42b2-88c3-78380b0db560")
                    val service   = json.optString("captcha_service", "hcaptcha")
                    val rqToken   = json.optString("captcha_rqtoken", "")
                    RegisterResult(captchaSiteKey = siteKey, captchaService = service,
                        captchaRqToken = rqToken, httpCode = code)
                }
                code == 429 -> {
                    val retryAfter = json.optDouble("retry_after", 30.0)
                    RegisterResult(retryAfterMs = (retryAfter * 1000).toLong(),
                        httpCode = code, error = "Rate limited")
                }
                else -> {
                    // Try to extract a human-readable error message
                    val errMsg = when {
                        json.has("message") -> json.getString("message")
                        json.has("errors")  -> flattenErrors(json.optJSONObject("errors"))
                        else                -> body.take(200)
                    }
                    RegisterResult(error = errMsg, httpCode = code)
                }
            }
        } catch (e: Exception) {
            RegisterResult(error = "Parse error: ${e.message} — $body".take(250), httpCode = code)
        }
    }

    private fun flattenErrors(errors: JSONObject?): String {
        if (errors == null) return "unknown"
        val sb = StringBuilder()
        errors.keys().forEach { key ->
            try {
                val obj = errors.optJSONObject(key)
                if (obj != null) {
                    val arr = obj.optJSONArray("_errors")
                    if (arr != null && arr.length() > 0) {
                        sb.append("$key: ${arr.getJSONObject(0).optString("message")}  ")
                    }
                }
            } catch (_: Exception) {}
        }
        return sb.toString().trim().take(200)
    }

    // ── Header builder ─────────────────────────────────────────────────────────

    private fun applyStandardHeaders(
        conn: HttpURLConnection,
        profile: FingerprintProfile,
        fingerprint: String = ""
    ) {
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", profile.userAgent)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Language",
            "${profile.language},${profile.language.substringBefore('-')};q=0.9,en;q=0.8")
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate, br")
        conn.setRequestProperty("Origin", "https://discord.com")
        conn.setRequestProperty("Referer", "https://discord.com/register")
        conn.setRequestProperty("Sec-Ch-Ua",
            "\"Chromium\";v=\"${profile.chromeMajorVersion}\", " +
            "\"Google Chrome\";v=\"${profile.chromeMajorVersion}\", " +
            "\"Not-A.Brand\";v=\"8\"")
        conn.setRequestProperty("Sec-Ch-Ua-Mobile", "?0")
        conn.setRequestProperty("Sec-Ch-Ua-Platform", if (profile.isMac) "\"macOS\"" else "\"Windows\"")
        conn.setRequestProperty("Sec-Fetch-Dest", "empty")
        conn.setRequestProperty("Sec-Fetch-Mode", "cors")
        conn.setRequestProperty("Sec-Fetch-Site", "same-origin")
        conn.setRequestProperty("X-Super-Properties", buildSuperProperties(profile))
        conn.setRequestProperty("X-Discord-Locale", profile.language)
        conn.setRequestProperty("X-Discord-Timezone", profile.timezone)
        conn.setRequestProperty("X-Debug-Options", "bugReporterEnabled")
        conn.connectTimeout = 20_000
        conn.readTimeout    = 30_000
        if (fingerprint.isNotBlank()) {
            conn.setRequestProperty("X-Fingerprint", fingerprint)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun java.io.BufferedReader.readLimited(maxChars: Int): String {
        val sb = StringBuilder(minOf(maxChars, 65536))
        var total = 0
        val buf = CharArray(8192)
        var n: Int
        while (read(buf).also { n = it } != -1) {
            sb.append(buf, 0, n)
            total += n
            if (total >= maxChars) break
        }
        return sb.toString()
    }
}
