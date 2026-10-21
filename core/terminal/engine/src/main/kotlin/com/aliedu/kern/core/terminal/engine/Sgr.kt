package com.aliedu.kern.core.terminal.engine

import com.aliedu.kern.core.terminal.engine.model.CellAttributes
import com.aliedu.kern.core.terminal.engine.model.TerminalColor

/** Applies SGR (`CSI ... m`) parameters to a [CellAttributes] state. */
object Sgr {

    fun apply(current: CellAttributes, params: List<Int>, subparams: List<List<Int>>): CellAttributes {
        if (params.isEmpty()) return current.copy(bold = false, dim = false, italic = false, underline = false,
            blink = false, inverse = false, hidden = false, strikethrough = false,
            fg = TerminalColor.Default, bg = TerminalColor.Default)

        var a = current
        var i = 0
        while (i < params.size) {
            val code = params[i]
            when (code) {
                0 -> a = CellAttributes.DEFAULT
                1 -> a = a.copy(bold = true)
                2 -> a = a.copy(dim = true)
                3 -> a = a.copy(italic = true)
                4 -> a = a.copy(underline = true)
                5, 6 -> a = a.copy(blink = true)
                7 -> a = a.copy(inverse = true)
                8 -> a = a.copy(hidden = true)
                9 -> a = a.copy(strikethrough = true)
                21 -> a = a.copy(bold = false)
                22 -> a = a.copy(bold = false, dim = false)
                23 -> a = a.copy(italic = false)
                24 -> a = a.copy(underline = false)
                25 -> a = a.copy(blink = false)
                27 -> a = a.copy(inverse = false)
                28 -> a = a.copy(hidden = false)
                29 -> a = a.copy(strikethrough = false)
                in 30..37 -> a = a.copy(fg = TerminalColor.Indexed(code - 30))
                39 -> a = a.copy(fg = TerminalColor.Default)
                in 40..47 -> a = a.copy(bg = TerminalColor.Indexed(code - 40))
                49 -> a = a.copy(bg = TerminalColor.Default)
                in 90..97 -> a = a.copy(fg = TerminalColor.Indexed(code - 90 + 8))
                in 100..107 -> a = a.copy(bg = TerminalColor.Indexed(code - 100 + 8))
                38, 48 -> {
                    val sub = subparams.getOrNull(i)
                    val color: TerminalColor?
                    val consumed: Int
                    if (sub != null && sub.size > 1) {
                        // Colon form: 38:2::r:g:b or 38:5:n
                        color = parseExtended(sub, 1)
                        consumed = 0
                    } else {
                        val (c, n) = parseExtendedSemicolon(params, i + 1)
                        color = c
                        consumed = n
                    }
                    if (color != null) a = if (code == 38) a.copy(fg = color) else a.copy(bg = color)
                    i += consumed
                }
                else -> Unit
            }
            i++
        }
        return a
    }

    private fun parseExtended(sub: List<Int>, from: Int): TerminalColor? {
        if (from >= sub.size) return null
        return when (sub[from]) {
            5 -> sub.getOrNull(from + 1)?.let { TerminalColor.Indexed(it) }
            2 -> {
                // 38:2:r:g:b or 38:2:<colorspace>:r:g:b — r,g,b are always the final three values.
                val rest = sub.subList(from + 1, sub.size)
                if (rest.size < 3) null
                else TerminalColor.Rgb(rest[rest.size - 3], rest[rest.size - 2], rest[rest.size - 1])
            }
            else -> null
        }
    }

    /** Semicolon form: 38;5;n or 38;2;r;g;b — returns the colour and how many extra params it ate. */
    private fun parseExtendedSemicolon(params: List<Int>, start: Int): Pair<TerminalColor?, Int> {
        if (start >= params.size) return null to 0
        return when (params[start]) {
            5 -> {
                val n = params.getOrNull(start + 1) ?: return null to 1
                TerminalColor.Indexed(n) to 2
            }
            2 -> {
                val r = params.getOrNull(start + 1); val g = params.getOrNull(start + 2); val b = params.getOrNull(start + 3)
                if (r == null || g == null || b == null) null to 1
                else TerminalColor.Rgb(r, g, b) to 4
            }
            else -> null to 1
        }
    }
}
