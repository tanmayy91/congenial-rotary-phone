package com.educational.discordcreator

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight Groq REST API client (no third-party HTTP library required).
 * All calls are blocking – always invoke from a background thread.
 */
object GroqClient {

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "llama-3.3-70b-versatile"

    data class Result(val text: String, val error: String = "")

    // ── Low-level chat call ───────────────────────────────────────────────────
    fun chat(
        apiKey: String,
        systemPrompt: String = "",
        userPrompt: String,
        maxTokens: Int = 300,
        temperature: Double = 0.9
    ): Result {
        if (apiKey.isBlank()) return Result("", "API key is empty")
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }

            val messages = JSONArray().apply {
                if (systemPrompt.isNotBlank()) {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                }
                put(JSONObject().put("role", "user").put("content", userPrompt))
            }

            val body = JSONObject()
                .put("model", MODEL)
                .put("messages", messages)
                .put("max_tokens", maxTokens)
                .put("temperature", temperature)
                .toString()

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            val stream = if (code == 200) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val raw = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

            if (code != 200) return Result("", "HTTP $code: $raw")

            val content = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            Result(content)
        } catch (e: Exception) {
            Result("", e.message ?: "Unknown error")
        } finally {
            conn.disconnect()
        }
    }

    // ── Verify key is valid with a minimal test call ──────────────────────────
    fun testKey(apiKey: String): Result =
        chat(apiKey, userPrompt = "Reply with exactly: OK", maxTokens = 5, temperature = 0.0)

    // ── Generate unique Discord usernames (lowercase_word_num) ────────────────
    fun generateUsernames(apiKey: String, count: Int): List<String> {
        val result = chat(
            apiKey,
            systemPrompt = "You are a Discord username generator. Output ONLY the requested items, one per line, nothing else.",
            userPrompt = """Generate exactly $count unique Discord usernames.
Rules:
- Lowercase letters, digits and underscores ONLY
- Length 4-20 characters
- Gamer/internet culture style
- Each on its own line, no numbering, no extra text""",
            maxTokens = count * 25
        )
        if (result.error.isNotBlank()) return emptyList()
        return result.text.lines()
            .map { it.trim().lowercase().replace(Regex("[^a-z0-9_]"), "") }
            .filter { it.length in 4..20 }
            .distinct()
            .take(count)
    }

    // ── Generate human-looking display names (Global Name on Discord) ─────────
    fun generateDisplayNames(apiKey: String, count: Int): List<String> {
        val result = chat(
            apiKey,
            systemPrompt = "You are a Discord display name generator. Output ONLY the requested items, one per line, nothing else.",
            userPrompt = """Generate exactly $count unique Discord display names.
Rules:
- Max 32 characters each
- Mix of styles: some camelCase, some with a single emoji prefix, some plain words
- Human-looking, creative
- Each on its own line, no numbering, no extra text""",
            maxTokens = count * 20
        )
        if (result.error.isNotBlank()) return emptyList()
        return result.text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length <= 32 }
            .distinct()
            .take(count)
    }

    // ── CAPTCHA solving hint ──────────────────────────────────────────────────
    fun captchaHint(apiKey: String, pageTitle: String, pageUrl: String): String {
        val result = chat(
            apiKey,
            userPrompt = """I am on a Discord registration page (URL: $pageUrl, title: "$pageTitle") inside an Android WebView.
An hCaptcha or similar CAPTCHA is blocking me. Give me a single short tip (1-2 sentences) on how to solve it manually. Be direct.""",
            maxTokens = 80,
            temperature = 0.3
        )
        return if (result.error.isBlank()) result.text
        else "Solve the CAPTCHA shown on screen, then click Continue."
    }
}
