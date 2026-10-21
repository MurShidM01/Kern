package com.aliedu.kern.core.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A build log is mostly progress chatter; the summary must preserve what a developer needs — the
 * opening context, the actual errors, and the final verdict — while dropping the rest.
 */
class CommandOutputSummarizerTest {

    @Test
    fun `short output is returned unchanged`() {
        val short = "line one\nline two\n"
        assertThat(CommandOutputSummarizer.summarize(short)).isEqualTo(short)
    }

    @Test
    fun `long output keeps the head the tail and the errors`() {
        val lines = ArrayList<String>()
        lines.add("> Task :app:compileDebugKotlin")
        repeat(500) { lines.add("Downloading artifact $it ...") }
        lines.add("e: App.kt:12:5 Unresolved reference: missingSymbol")
        lines.add("> Task :app:compileDebugKotlin FAILED")
        lines.add("BUILD FAILED in 12s")
        val log = lines.joinToString("\n")

        val summary = CommandOutputSummarizer.summarize(log)

        assertThat(summary).contains("> Task :app:compileDebugKotlin")
        assertThat(summary).contains("Unresolved reference: missingSymbol")
        assertThat(summary).contains("BUILD FAILED in 12s")
        assertThat(summary).contains("--- key lines ---")
        assertThat(summary).contains("lines omitted]")
        assertThat(summary.length).isLessThan(log.length)
    }

    @Test
    fun `an error line is surfaced even when it is buried`() {
        val lines = ArrayList<String>()
        repeat(450) { lines.add("progress $it") }
        lines.add("ERROR: something broke at step 42")
        repeat(100) { lines.add("cleanup $it") }
        val summary = CommandOutputSummarizer.summarize(lines.joinToString("\n"))

        assertThat(summary).contains("ERROR: something broke at step 42")
    }

    @Test
    fun `the tail is always present`() {
        val lines = ArrayList<String>()
        repeat(600) { lines.add("noise $it") }
        lines.add("THE-VERY-LAST-LINE")
        val summary = CommandOutputSummarizer.summarize(lines.joinToString("\n"))

        assertThat(summary).contains("THE-VERY-LAST-LINE")
    }
}
