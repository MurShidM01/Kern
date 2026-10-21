package com.aliedu.kern.feature.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.aliedu.kern.core.terminal.engine.model.Cell
import com.aliedu.kern.core.terminal.engine.model.CellAttributes

/**
 * Turns one terminal row ([Cell]s) into an [AnnotatedString]. Consecutive cells that share attributes
 * (and selection state) are merged into a single span, and double-width continuation cells contribute
 * no text — keeping the span count (and therefore render cost) low for dense output.
 */
object AnnotatedStringBuilder {

    fun buildRow(
        cells: List<Cell>,
        base: List<Color>,
        defaultFg: Color,
        defaultBg: Color,
        cursorCol: Int? = null,
        cursorColor: Color = Color.Unspecified,
        selection: IntRange? = null,
        selectionColor: Color = Color.Unspecified
    ): AnnotatedString = buildAnnotatedString {
        var index = 0
        while (index < cells.size) {
            val cell = cells[index]
            if (cell.continuation) {
                index++
                continue
            }
            val attrs = cell.attrs
            val isCursor = index == cursorCol
            val isSelected = selection != null && index in selection
            val start = length
            append(cell.text)
            index++
            if (!isCursor) {
                while (index < cells.size &&
                    cells[index].attrs == attrs &&
                    !cells[index].continuation &&
                    index != cursorCol &&
                    (selection != null && index in selection) == isSelected
                ) {
                    append(cells[index].text)
                    index++
                }
            }
            if (length > start) {
                val style = styleFor(attrs, base, defaultFg, defaultBg)
                val background = when {
                    isCursor && cursorColor != Color.Unspecified -> cursorColor
                    isSelected && selectionColor != Color.Unspecified -> selectionColor
                    else -> style.background
                }
                addStyle(style.copy(background = background), start, length)
            }
        }
    }

    private fun styleFor(
        attrs: CellAttributes,
        base: List<Color>,
        defaultFg: Color,
        defaultBg: Color
    ): SpanStyle {
        val rawFg = TerminalPalette.resolve(attrs.fg, base, defaultFg)
        val rawBg = TerminalPalette.resolve(attrs.bg, base, defaultBg)
        val fg = if (attrs.inverse) rawBg else rawFg
        val bg = if (attrs.inverse) rawFg else rawBg

        var decoration: TextDecoration? = null
        if (attrs.underline && attrs.strikethrough) {
            decoration = TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        } else if (attrs.underline) {
            decoration = TextDecoration.Underline
        } else if (attrs.strikethrough) {
            decoration = TextDecoration.LineThrough
        }

        return SpanStyle(
            color = fg,
            background = if (bg != defaultBg) bg else Color.Unspecified,
            fontWeight = if (attrs.bold) FontWeight.Bold else null,
            fontStyle = if (attrs.italic) FontStyle.Italic else null,
            textDecoration = decoration
        )
    }
}
