package com.aliedu.kern.feature.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.aliedu.kern.core.terminal.engine.model.TerminalColor

/**
 * Maps terminal colours to Compose colours. Two base palettes (Tango, tuned for dark and light code
 * backgrounds) keep shell output legible on either theme; 256-colour and truecolour values pass
 * through. This mirrors the flat, colour-only approach of the rest of Kern's design system.
 */
object TerminalPalette {

    private val darkBase = listOf(
        Color(0xFF2E3436), Color(0xFFCC0000), Color(0xFF4E9A06), Color(0xFFC4A000),
        Color(0xFF3465A4), Color(0xFF75507B), Color(0xFF06989A), Color(0xFFD3D7CF),
        Color(0xFF555753), Color(0xFFEF2929), Color(0xFF8AE234), Color(0xFFFCE94F),
        Color(0xFF729FCF), Color(0xFFAD7FA8), Color(0xFF34E2E2), Color(0xFFEEEEEC)
    )

    private val lightBase = listOf(
        Color(0xFF000000), Color(0xFFC91B00), Color(0xFF00A000), Color(0xFFA68A00),
        Color(0xFF1B6ACB), Color(0xFFA000A0), Color(0xFF008080), Color(0xFF555555),
        Color(0xFF666666), Color(0xFFD50000), Color(0xFF008700), Color(0xFF8A6D00),
        Color(0xFF0043A0), Color(0xFF8A008A), Color(0xFF006A6A), Color(0xFF111111)
    )

    /** Choose the base palette that reads best on [background] (the code surface colour). */
    fun baseFor(background: Color): List<Color> =
        if (background.luminance() > 0.5f) lightBase else darkBase

    /** Resolve a terminal colour to a Compose colour, falling back to [default] for the default entry. */
    fun resolve(color: TerminalColor, base: List<Color>, default: Color): Color = when (color) {
        is TerminalColor.Default -> default
        is TerminalColor.Rgb -> Color(color.r.coerceIn(0, 255), color.g.coerceIn(0, 255), color.b.coerceIn(0, 255))
        is TerminalColor.Indexed -> indexed(color.index, base, default)
    }

    private fun indexed(index: Int, base: List<Color>, default: Color): Color = when {
        index < 0 -> default
        index < 16 -> base[index]
        index < 232 -> {
            val i = index - 16
            val r = i / 36
            val g = (i % 36) / 6
            val b = i % 6
            Color(cubeLevel(r), cubeLevel(g), cubeLevel(b))
        }
        index < 256 -> {
            val level = 8 + (index - 232) * 10
            Color(level, level, level)
        }
        else -> default
    }

    private fun cubeLevel(v: Int): Int = if (v == 0) 0 else 55 + v * 40
}
