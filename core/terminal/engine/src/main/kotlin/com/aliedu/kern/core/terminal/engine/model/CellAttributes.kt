package com.aliedu.kern.core.terminal.engine.model

/** Visual attributes applied to a [Cell] via SGR. */
data class CellAttributes(
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val blink: Boolean = false,
    val inverse: Boolean = false,
    val hidden: Boolean = false,
    val strikethrough: Boolean = false,
    val fg: TerminalColor = TerminalColor.Default,
    val bg: TerminalColor = TerminalColor.Default
) {
    companion object {
        val DEFAULT = CellAttributes()
    }
}
