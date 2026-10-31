package com.aliedu.kern.core.terminal.implementation.runtime

/**
 * Text helpers shared by everything that reads a PTY: the tty echoes what we type, wraps long
 * lines and sprinkles escape sequences through progress output. Extracted so the command executor
 * and the background job manager strip it the same way instead of drifting apart.
 */
internal object TtyText {

    /** OSC: a title or hyperlink, terminated by BEL or ST. */
    private val ANSI_OSC = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

    /** CSI: colours, cursor moves, bracketed-paste markers. */
    private val ANSI_CSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** Two-character escapes such as the charset switch. */
    private val ANSI_TWO_CHAR = Regex("\u001B[@-Z\\\\-_]")

    /** Drops CSI/OSC escape sequences and the carriage returns a pty inserts, leaving plain text. */
    fun stripAnsi(text: String): String =
        text.replace(ANSI_OSC, "")
            .replace(ANSI_CSI, "")
            .replace(ANSI_TWO_CHAR, "")
            .replace("\r", "")

}
