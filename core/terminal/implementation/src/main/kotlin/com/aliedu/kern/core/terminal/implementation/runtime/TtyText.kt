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

    /**
     * Index just past the tty's echo of [script] in [text], or -1 if it is not present near the
     * head. The tty may render a newline as CRLF and wrap long lines, so we match the script
     * character by character while ignoring carriage returns and newlines.
     */
    fun findEchoEnd(text: String, script: String): Int {
        if (script.isEmpty()) return -1
        val limit = (script.length * 2 + 64).coerceAtMost(text.length)
        for (start in 0..limit) {
            var i = start
            var j = 0
            while (i < text.length && j < script.length) {
                val expected = script[j]
                if (expected == '\n') {
                    j++
                    continue
                }
                val actual = text[i]
                if (actual == '\r' || actual == '\n') {
                    i++
                    continue
                }
                if (actual != expected) break
                i++
                j++
            }
            if (j == script.length) return i
        }
        return -1
    }
}
