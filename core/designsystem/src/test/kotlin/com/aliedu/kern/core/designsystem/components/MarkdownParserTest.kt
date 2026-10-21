package com.aliedu.kern.core.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure tests for the Markdown block parser that backs agent response rendering. Guards against the
 * regression where raw Markdown (`*`, `#`) leaked into assistant bubbles.
 */
class MarkdownParserTest {

    private val codeStyle = SpanStyle()
    private val linkColor = Color.Blue

    @Test
    fun `parses headings by level`() {
        val blocks = MarkdownParser.parse("# Title\n## Sub")
        assertThat(blocks).hasSize(2)
        assertThat(blocks[0]).isEqualTo(MdBlock.Heading(1, "Title"))
        assertThat(blocks[1]).isEqualTo(MdBlock.Heading(2, "Sub"))
    }

    @Test
    fun `parses unordered list into a single block`() {
        val blocks = MarkdownParser.parse("- one\n- two\n- three")
        assertThat(blocks).hasSize(1)
        val bullets = blocks[0] as MdBlock.Bullets
        assertThat(bullets.items).containsExactly("one", "two", "three").inOrder()
    }

    @Test
    fun `parses ordered list into a single block`() {
        val blocks = MarkdownParser.parse("1. first\n2. second")
        assertThat(blocks).hasSize(1)
        val ordered = blocks[0] as MdBlock.Ordered
        assertThat(ordered.items).containsExactly("first", "second").inOrder()
    }

    @Test
    fun `parses fenced code with language`() {
        val blocks = MarkdownParser.parse("```kotlin\nval x = 1\n```")
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isEqualTo(MdBlock.Code("kotlin", "val x = 1"))
    }

    @Test
    fun `joins consecutive lines into one paragraph`() {
        val blocks = MarkdownParser.parse("line one\nline two")
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isEqualTo(MdBlock.Paragraph("line one line two"))
    }

    @Test
    fun `parses blockquote and rule`() {
        val blocks = MarkdownParser.parse("> quoted\n\n---")
        assertThat(blocks).hasSize(2)
        assertThat(blocks[0]).isEqualTo(MdBlock.Quote("quoted"))
        assertThat(blocks[1]).isEqualTo(MdBlock.Rule)
    }

    @Test
    fun `parses pipe table with header and rows`() {
        val blocks = MarkdownParser.parse("| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |")
        assertThat(blocks).hasSize(1)
        val table = blocks[0] as MdBlock.Table
        assertThat(table.headers).containsExactly("A", "B").inOrder()
        assertThat(table.rows).hasSize(2)
        assertThat(table.rows[0]).containsExactly("1", "2").inOrder()
        assertThat(table.rows[1]).containsExactly("3", "4").inOrder()
    }

    @Test
    fun `inline strips bold markers and keeps text`() {
        val annotated = MarkdownParser.inline("a **bold** word", codeStyle, linkColor)
        assertThat(annotated.text).isEqualTo("a bold word")
    }

    @Test
    fun `inline strips inline code markers`() {
        val annotated = MarkdownParser.inline("open `index.html` now", codeStyle, linkColor)
        assertThat(annotated.text).isEqualTo("open index.html now")
    }

    @Test
    fun `inline renders link label without url`() {
        val annotated = MarkdownParser.inline("see [docs](https://example.com)", codeStyle, linkColor)
        assertThat(annotated.text).isEqualTo("see docs")
    }
}
