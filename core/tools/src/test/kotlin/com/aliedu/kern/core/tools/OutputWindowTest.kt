package com.aliedu.kern.core.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tool results used to be cut with `take(n)`, which kept the head and dropped the tail — exactly
 * backwards for the failures that matter, whose error is printed last.
 */
class OutputWindowTest {

    @Test
    fun `short text is returned untouched`() {
        assertThat(OutputWindow.headTail("all good", 100)).isEqualTo("all good")
    }

    @Test
    fun `long text keeps both ends and says what it dropped`() {
        val text = "HEAD" + "x".repeat(10_000) + "TAIL"
        val window = OutputWindow.headTail(text, 1_000)

        assertThat(window).startsWith("HEAD")
        assertThat(window).endsWith("TAIL")
        assertThat(window).contains("characters omitted")
        assertThat(window.length).isAtMost(1_000)
    }

    @Test
    fun `a limit of zero yields nothing`() {
        assertThat(OutputWindow.headTail("text", 0)).isEmpty()
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        // An emoji is two UTF-16 code units; cutting between them produces a lone surrogate, which
        // reaches the model as a replacement glyph.
        val text = "a".repeat(10) + "😀".repeat(100)
        val end = OutputWindow.safeEnd(text, 11)
        assertThat(Character.isHighSurrogate(text[end - 1])).isFalse()

        val start = OutputWindow.safeStart(text, 11)
        assertThat(Character.isLowSurrogate(text[start])).isFalse()
    }
}
