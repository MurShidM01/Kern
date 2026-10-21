package com.aliedu.kern.feature.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.aliedu.kern.core.terminal.engine.model.Cell
import com.aliedu.kern.core.terminal.engine.model.CellAttributes
import com.aliedu.kern.core.terminal.engine.model.TerminalColor
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnnotatedStringBuilderTest {

    private val background = Color(0xFF1E293B)
    private val defaultFg = Color(0xFFE2E8F0)
    private val base = TerminalPalette.baseFor(background)

    private fun row(text: String, attrs: CellAttributes = CellAttributes.DEFAULT): List<Cell> =
        text.map { Cell(it.toString(), attrs) }

    private fun build(cells: List<Cell>, cursorCol: Int? = null, cursorColor: Color = Color.Unspecified) =
        AnnotatedStringBuilder.buildRow(cells, base, defaultFg, background, cursorCol, cursorColor)

    @Test
    fun `plain text is preserved with the default foreground`() {
        val result = build(row("hello"))
        assertThat(result.text).isEqualTo("hello")
        assertThat(result.spanStyles).hasSize(1)
        assertThat(result.spanStyles[0].item.color).isEqualTo(defaultFg)
        assertThat(result.spanStyles[0].item.background).isEqualTo(Color.Unspecified)
    }

    @Test
    fun `adjacent cells with equal attributes share one span`() {
        val result = build(row("abcd"))
        assertThat(result.spanStyles).hasSize(1)
        assertThat(result.spanStyles[0].start).isEqualTo(0)
        assertThat(result.spanStyles[0].end).isEqualTo(4)
    }

    @Test
    fun `a bold run sets the bold weight only on that run`() {
        val cells = row("ab") + row("CD", CellAttributes(bold = true)) + row("ef")
        val result = build(cells)
        assertThat(result.text).isEqualTo("abCDef")
        val boldSpans = result.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertThat(boldSpans).hasSize(1)
        assertThat(boldSpans[0].start).isEqualTo(2)
        assertThat(boldSpans[0].end).isEqualTo(4)
    }

    @Test
    fun `italic and underline map to their text styles`() {
        val cells = row("x", CellAttributes(italic = true, underline = true))
        val style = build(cells).spanStyles.single().item
        assertThat(style.fontStyle).isEqualTo(FontStyle.Italic)
        assertThat(style.textDecoration).isEqualTo(TextDecoration.Underline)
    }

    @Test
    fun `strikethrough maps to line-through`() {
        val style = build(row("x", CellAttributes(strikethrough = true))).spanStyles.single().item
        assertThat(style.textDecoration).isEqualTo(TextDecoration.LineThrough)
    }

    @Test
    fun `an indexed foreground resolves through the palette`() {
        val expected = TerminalPalette.resolve(TerminalColor.Indexed(2), base, defaultFg)
        val style = build(row("g", CellAttributes(fg = TerminalColor.Indexed(2)))).spanStyles.single().item
        assertThat(style.color).isEqualTo(expected)
    }

    @Test
    fun `truecolour foreground passes through unchanged`() {
        val style = build(row("z", CellAttributes(fg = TerminalColor.Rgb(10, 20, 30))))
            .spanStyles.single().item
        assertThat(style.color).isEqualTo(Color(10, 20, 30))
    }

    @Test
    fun `inverse swaps foreground and background`() {
        val fg = TerminalColor.Rgb(200, 100, 50)
        val bg = TerminalColor.Rgb(1, 2, 3)
        val style = build(row("i", CellAttributes(fg = fg, bg = bg, inverse = true)))
            .spanStyles.single().item
        assertThat(style.color).isEqualTo(Color(1, 2, 3))
        assertThat(style.background).isEqualTo(Color(200, 100, 50))
    }

    @Test
    fun `continuation cells contribute no text`() {
        val cells = listOf(
            Cell("世", CellAttributes.DEFAULT, width = 2),
            Cell("", CellAttributes.DEFAULT, width = 1, continuation = true),
            Cell("a", CellAttributes.DEFAULT)
        )
        val result = build(cells)
        assertThat(result.text).isEqualTo("世a")
    }

    @Test
    fun `the cursor cell gets the cursor background`() {
        val cursorColor = Color(0x80FF0000)
        val result = build(row("abc"), cursorCol = 1, cursorColor = cursorColor)
        assertThat(result.text).isEqualTo("abc")
        val cursorSpan = result.spanStyles.first { it.item.background == cursorColor }
        assertThat(cursorSpan.start).isEqualTo(1)
        assertThat(cursorSpan.end).isEqualTo(2)
    }

    @Test
    fun `palette chooses a light base on a light background`() {
        val light = TerminalPalette.baseFor(Color.White)
        val dark = TerminalPalette.baseFor(Color(0xFF111111))
        assertThat(light).isNotEqualTo(dark)
    }

    @Test
    fun `256-colour cube values match the xterm levels`() {
        // index 16 is the first cube entry (0,0,0); index 196 is (255,0,0).
        assertThat(TerminalPalette.resolve(TerminalColor.Indexed(16), base, defaultFg))
            .isEqualTo(Color(0, 0, 0))
        assertThat(TerminalPalette.resolve(TerminalColor.Indexed(196), base, defaultFg))
            .isEqualTo(Color(255, 0, 0))
    }
}
