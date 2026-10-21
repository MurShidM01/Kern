package com.aliedu.kern.core.terminal.engine.model

/** A terminal colour: the default palette entry, a 256-colour index, or a 24-bit RGB value. */
sealed interface TerminalColor {
    data object Default : TerminalColor
    data class Indexed(val index: Int) : TerminalColor
    data class Rgb(val r: Int, val g: Int, val b: Int) : TerminalColor
}
