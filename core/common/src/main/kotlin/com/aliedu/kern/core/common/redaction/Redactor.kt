package com.aliedu.kern.core.common.redaction

/**
 * Central redaction utility (PRD 15.2). Every place that logs, exports, or displays a URL,
 * header, exception, or tool argument must run through this first so credentials can never
 * leak into logs, crash reports, tool traces, clipboard, or exported settings (PRIV-04).
 */
object Redactor {

    const val MASK = "[REDACTED]"

    private val SENSITIVE_KEYS = setOf(
        "api_key", "apikey", "api-key", "key", "token", "access_token", "refresh_token",
        "id_token", "auth", "authorization", "password", "passwd", "pwd", "secret",
        "client_secret", "bearer", "x-api-key", "session", "sessionid", "cookie"
    )

    fun isSensitiveKey(key: String): Boolean =
        SENSITIVE_KEYS.contains(key.trim().lowercase())

    /** Redacts sensitive query parameters and any userinfo in a URL. */
    fun redactUrl(url: String): String {
        if (url.isBlank()) return url
        val queryIndex = url.indexOf('?')
        if (queryIndex < 0) return redactUserInfo(url)

        val base = url.substring(0, queryIndex)
        val query = url.substring(queryIndex + 1)
        val redactedQuery = query.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) {
                pair
            } else {
                val name = pair.substring(0, eq)
                if (isSensitiveKey(name)) "$name=$MASK" else pair
            }
        }
        return redactUserInfo(base) + "?" + redactedQuery
    }

    private fun redactUserInfo(url: String): String {
        val schemeIdx = url.indexOf("://")
        if (schemeIdx < 0) return url
        val rest = url.substring(schemeIdx + 3)
        val at = rest.indexOf('@')
        if (at < 0) return url
        val credentials = rest.substring(0, at)
        val user = credentials.substringBefore(':')
        return url.substring(0, schemeIdx + 3) + "$user:$MASK@" + rest.substring(at + 1)
    }

    /** Redacts a header value when the header name is sensitive. */
    fun redactHeader(name: String, value: String): String =
        if (isSensitiveKey(name)) MASK else value

    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (k, v) -> redactHeader(k, v) }

    /** Always fully masks a secret; the original value is never returned. */
    fun maskSecret(@Suppress("UNUSED_PARAMETER") secret: String): String = MASK

    /** Replaces any occurrence of a known secret substring inside arbitrary text. */
    fun redactText(text: String, knownSecrets: Collection<String>): String {
        var result = text
        for (secret in knownSecrets) {
            if (secret.isNotBlank() && secret.length >= 4) {
                result = result.replace(secret, MASK)
            }
        }
        return result
    }

    /**
     * Best-effort redaction of a JSON-ish argument string: masks the values of keys that look
     * sensitive (e.g. {"api_key":"..."} -> {"api_key":"[REDACTED]"}).
     */
    fun redactJsonArgs(json: String): String {
        val regex = Regex("\"([A-Za-z0-9_\\-]+)\"\\s*:\\s*\"([^\"]*)\"")
        return regex.replace(json) { match ->
            val name = match.groupValues[1]
            if (isSensitiveKey(name)) {
                "\"$name\":\"$MASK\""
            } else {
                match.value
            }
        }
    }

    fun redactThrowableMessage(t: Throwable, knownSecrets: Collection<String> = emptyList()): String {
        val raw = t.message ?: t.javaClass.simpleName
        return redactText(raw, knownSecrets)
    }
}
