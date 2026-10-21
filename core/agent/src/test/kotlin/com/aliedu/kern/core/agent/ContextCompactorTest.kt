package com.aliedu.kern.core.agent

import com.aliedu.kern.core.model.Message
import com.aliedu.kern.core.model.MessageRole
import com.aliedu.kern.core.model.MessageStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Backs PRD §4.3 bullet 5: over-budget turns are summarised, not lost. */
class ContextCompactorTest {

    private val compactor = ContextCompactor()

    private fun message(role: MessageRole, content: String, seq: Long) = Message(
        id = "m$seq",
        sessionId = "s",
        role = role,
        content = content,
        status = MessageStatus.COMPLETE,
        providerMessageId = null,
        createdAt = seq,
        seq = seq
    )

    @Test
    fun `an empty drop set produces no digest`() {
        assertThat(compactor.compact(emptyList(), 1_000)).isNull()
    }

    @Test
    fun `a digest keeps the user request and the assistant reply`() {
        val dropped = listOf(
            message(MessageRole.USER, "Add a settings screen", 1),
            message(MessageRole.ASSISTANT, "Added SettingsScreen.kt and wired the route.", 2)
        )
        val digest = compactor.compact(dropped, 4_000)

        assertThat(digest).isNotNull()
        assertThat(digest!!).contains("User asked: Add a settings screen")
        assertThat(digest).contains("Assistant replied: Added SettingsScreen.kt")
        assertThat(digest).contains("lossy summary")
    }

    @Test
    fun `long messages are collapsed to one truncated line`() {
        val dropped = listOf(
            message(MessageRole.USER, "word\n\n  ".repeat(500) + "ZZTAILZZ", 1)
        )
        val digest = compactor.compact(dropped, 4_000)!!

        // Newlines and runs of whitespace become single spaces.
        assertThat(digest).doesNotContain("\n\n")
        // Truncated, so the far tail is not present.
        assertThat(digest).doesNotContain("ZZTAILZZ")
    }

    @Test
    fun `only user and assistant turns are summarised`() {
        val dropped = listOf(
            message(MessageRole.SYSTEM, "internal preamble", 1),
            message(MessageRole.TOOL, "raw tool output", 2),
            message(MessageRole.USER, "real question", 3)
        )
        val digest = compactor.compact(dropped, 4_000)!!

        assertThat(digest).contains("real question")
        assertThat(digest).doesNotContain("internal preamble")
        assertThat(digest).doesNotContain("raw tool output")
    }
}
