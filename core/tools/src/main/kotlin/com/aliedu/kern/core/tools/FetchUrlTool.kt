package com.aliedu.kern.core.tools

import com.aliedu.kern.core.common.redaction.Redactor
import com.aliedu.kern.core.providers.api.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a URL as text so the agent can consult a doc page or a raw file without shelling out to
 * `curl`. It is deliberately narrow: http/https only, textual content types only, a hard size cap,
 * and every result passed through [Redactor] so a token in the query string or body cannot reach
 * the transcript. It is read-only and never writes to the workspace, so it is not approval-gated
 * (the URL is visible in the tool trace).
 */
@Singleton
class FetchUrlTool @Inject constructor() : Tool {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override val name = "fetch_url"
    override val isReadOnly = true
    override val description =
        "Fetch a web page or raw text file over http(s) and return it as plain text. Read-only; " +
            "use it to consult documentation. Only textual responses are returned."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{"url":{"type":"string","description":"Absolute http(s) URL."},"max_chars":{"type":"integer","description":"Maximum characters to return (default 20000)."}},"required":["url"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val url = (args["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return ToolResult(false, "", "Missing required argument 'url'.")
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") {
            return ToolResult(false, "", "Only http and https URLs are supported (got '$scheme').")
        }
        val maxChars = ((args["max_chars"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_MAX_CHARS)
            .coerceIn(1_000, MAX_MAX_CHARS)

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.5")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext ToolResult(
                            false, "",
                            "HTTP ${response.code} ${response.message} for ${Redactor.redactUrl(url)}"
                        )
                    }
                    val contentType = response.header("Content-Type").orEmpty()
                    if (!isTextual(contentType)) {
                        return@withContext ToolResult(
                            false, "",
                            "Refusing to return '${contentType.ifBlank { "unknown" }}' content; only text is supported."
                        )
                    }
                    // peekBody caps the bytes buffered so a huge response cannot exhaust memory.
                    val body = response.peekBody((maxChars.toLong() * 4) + 8)?.string().orEmpty()
                    val isHtml = contentType.contains("html", ignoreCase = true)
                    val text = (if (isHtml) htmlToText(body) else body).trim()
                    if (text.isEmpty()) {
                        return@withContext ToolResult(true, "(empty response body)")
                    }
                    val redacted = Redactor.redactText(text, emptyList())
                    val clipped = redacted.take(maxChars)
                    val suffix = if (redacted.length > maxChars) "\n[truncated at $maxChars characters]" else ""
                    ToolResult(true, clipped + suffix)
                }
            } catch (e: Exception) {
                ToolResult(false, "", "Could not fetch ${Redactor.redactUrl(url)}: ${e.message}")
            }
        }
    }

    /** A very small HTML-to-text pass: enough to make a doc page readable, not a full parser. */
    private fun htmlToText(html: String): String {
        val withoutScripts = SCRIPT_OR_STYLE.replace(html) { "" }
        val withBreaks = BLOCK_BREAK.replace(withoutScripts) { "\n" }
        val stripped = TAG.replace(withBreaks) { "" }
        val unescaped = stripped
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return unescaped.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun isTextual(contentType: String): Boolean {
        if (contentType.isBlank()) return true
        val type = contentType.substringBefore(';').trim().lowercase()
        return type.startsWith("text/") ||
            type == "application/json" ||
            type == "application/xml" ||
            type.endsWith("+json") ||
            type.endsWith("+xml")
    }

    private companion object {
        const val DEFAULT_MAX_CHARS = 20_000
        const val MAX_MAX_CHARS = 200_000
        const val USER_AGENT = "Kern-Agent/1.0"
        val SCRIPT_OR_STYLE = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
        val BLOCK_BREAK = Regex("(?i)</?(p|div|br|li|tr|h[1-6]|section|article|header|footer)[^>]*>")
        val TAG = Regex("(?s)<[^>]+>")
    }
}
