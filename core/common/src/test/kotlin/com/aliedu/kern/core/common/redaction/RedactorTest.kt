package com.aliedu.kern.core.common.redaction

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Backs T1-10 / P1-08: an API key canary placed anywhere must never survive redaction into a
 * log line, URL, header map, JSON tool argument, or exception message (PRD 15.2, PRIV-04).
 */
class RedactorTest {

    private val canary = "sk-canary-9f3a-DO-NOT-LEAK-1234567890"

    @Test
    fun `sensitive keys are detected case-insensitively`() {
        assertThat(Redactor.isSensitiveKey("api_key")).isTrue()
        assertThat(Redactor.isSensitiveKey("API-KEY")).isTrue()
        assertThat(Redactor.isSensitiveKey("Authorization")).isTrue()
        assertThat(Redactor.isSensitiveKey("X-Api-Key")).isTrue()
        assertThat(Redactor.isSensitiveKey("model")).isFalse()
        assertThat(Redactor.isSensitiveKey("path")).isFalse()
    }

    @Test
    fun `url query secrets are masked but harmless params are kept`() {
        val url = "https://gw.example.com/v1/chat?api_key=$canary&model=gpt-4o&token=abc"
        val redacted = Redactor.redactUrl(url)
        assertThat(redacted).doesNotContain(canary)
        assertThat(redacted).contains("api_key=${Redactor.MASK}")
        assertThat(redacted).contains("token=${Redactor.MASK}")
        assertThat(redacted).contains("model=gpt-4o")
    }

    @Test
    fun `url userinfo password is masked`() {
        val redacted = Redactor.redactUrl("https://alice:$canary@gw.example.com/v1")
        assertThat(redacted).doesNotContain(canary)
        assertThat(redacted).contains("alice:${Redactor.MASK}@")
    }

    @Test
    fun `header values are masked only for sensitive names`() {
        assertThat(Redactor.redactHeader("Authorization", "Bearer $canary")).isEqualTo(Redactor.MASK)
        assertThat(Redactor.redactHeader("Content-Type", "application/json"))
            .isEqualTo("application/json")
        val headers = Redactor.redactHeaders(
            mapOf("Authorization" to "Bearer $canary", "Accept" to "text/event-stream")
        )
        assertThat(headers.values).doesNotContain(canary)
        assertThat(headers["Accept"]).isEqualTo("text/event-stream")
    }

    @Test
    fun `json tool arguments mask sensitive values`() {
        val json = """{"api_key":"$canary","path":"src/App.kt","token":"xyz"}"""
        val redacted = Redactor.redactJsonArgs(json)
        assertThat(redacted).doesNotContain(canary)
        assertThat(redacted).contains("src/App.kt")
        assertThat(redacted).contains("\"api_key\":\"${Redactor.MASK}\"")
    }

    @Test
    fun `known secret substrings are scrubbed from arbitrary text`() {
        val text = "call failed with key $canary while reading file"
        val redacted = Redactor.redactText(text, listOf(canary))
        assertThat(redacted).doesNotContain(canary)
        assertThat(redacted).contains(Redactor.MASK)
    }

    @Test
    fun `short secrets are not treated as redactable to avoid over-masking`() {
        // Values under 4 chars are ignored by design (PRD: avoid masking every "a").
        val redacted = Redactor.redactText("aaa and bbbb", listOf("aaa"))
        assertThat(redacted).isEqualTo("aaa and bbbb")
    }

    @Test
    fun `maskSecret never returns the original value`() {
        assertThat(Redactor.maskSecret(canary)).isEqualTo(Redactor.MASK)
    }

    @Test
    fun `throwable message is scrubbed of known secrets`() {
        val error = IllegalStateException("upstream rejected key $canary")
        val message = Redactor.redactThrowableMessage(error, listOf(canary))
        assertThat(message).doesNotContain(canary)
    }
}
