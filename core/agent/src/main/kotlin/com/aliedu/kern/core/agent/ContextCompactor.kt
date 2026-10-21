package com.aliedu.kern.core.agent

import com.aliedu.kern.core.model.Message
import com.aliedu.kern.core.model.MessageRole
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compacts conversation turns that no longer fit the context budget (PRD §4.3 bullet 5).
 *
 * When [ContextBuilder] has to leave older messages out, dropping them outright loses the user's
 * earlier intent and the decisions already reached. This compactor folds those turns into one short,
 * deterministic digest instead. It is deliberately **local and extractive** — no extra model call —
 * so it is fast, offline, and never surprises the user with invented content.
 *
 * What is kept, in priority order:
 *  - the user's own requests, which define what the session is *for*;
 *  - the head of each assistant reply, which usually carries the conclusion;
 *  - a note that the digest is lossy, so the model re-reads files rather than trusting stale detail.
 */
@Singleton
class ContextCompactor @Inject constructor() {

    /**
     * @param dropped the older messages left out of the replay window, oldest-first.
     * @param budgetBytes upper bound for the digest itself.
     * @return a digest, or null when there is nothing worth summarising.
     */
    fun compact(dropped: List<Message>, budgetBytes: Int): String? {
        if (dropped.isEmpty()) return null
        val perMessageCap = PER_MESSAGE_CHARS
        val lines = StringBuilder()
        var used = 0

        for (message in dropped) {
            val label = when (message.role) {
                MessageRole.USER -> "User asked"
                MessageRole.ASSISTANT -> "Assistant replied"
                else -> continue
            }
            val body = oneLine(message.content, perMessageCap)
            if (body.isEmpty()) continue
            val line = "- $label: $body"
            val size = line.toByteArray(Charsets.UTF_8).size
            if (used + size > budgetBytes && lines.isNotEmpty()) break
            lines.appendLine(line)
            used += size
        }

        if (lines.isEmpty()) return null
        return buildString {
            appendLine("Earlier in this session, compacted to save context (this is a lossy summary):")
            append(lines)
            append(
                "Re-read any file you need before relying on details above; the digest keeps intent " +
                    "and conclusions, not exact contents."
            )
        }
    }

    /** Collapses whitespace and trims to [cap] characters, marking the cut. */
    private fun oneLine(text: String, cap: Int): String {
        val flattened = text.trim().replace(Regex("\\s+"), " ")
        if (flattened.isEmpty()) return ""
        return if (flattened.length <= cap) flattened else flattened.take(cap).trimEnd() + "…"
    }

    companion object {
        /** How much of any single dropped message to keep. */
        const val PER_MESSAGE_CHARS = 400
        /** Hard ceiling on the digest regardless of the caller's budget. */
        const val MAX_DIGEST_CHARS = 6_000
    }
}
