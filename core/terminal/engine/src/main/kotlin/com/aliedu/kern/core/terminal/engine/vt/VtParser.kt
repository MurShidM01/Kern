package com.aliedu.kern.core.terminal.engine.vt

/** A single decoded terminal instruction produced by [VtParser]. */
sealed interface Action {
    data class Print(val cp: Int, val width: Int) : Action
    data class Execute(val control: Char) : Action
    data class CsiDispatch(
        val privateMarker: Char?,
        val params: List<Int>,
        val subparams: List<List<Int>>,
        val intermediates: String,
        val final: Char
    ) : Action

    data class EscDispatch(val intermediates: String, val final: Char) : Action
    data class OscDispatch(val command: Int, val payload: String) : Action
}

private enum class ParseState {
    GROUND, ESCAPE, ESCAPE_INTERMEDIATE,
    CSI_ENTRY, CSI_PARAM, CSI_INTERMEDIATE, CSI_IGNORE,
    OSC_STRING, OSC_ESCAPE,
    DCS_ENTRY, DCS_PASSTHROUGH, DCS_IGNORE
}

/**
 * A VT/ANSI byte→action state machine. Operates on decoded [Char]s (the runtime guarantees valid
 * UTF-8), so ESC/CSI sequences — always ASCII — are lossless. Handles sequences split arbitrarily
 * across [parse] calls (the parser is stateful).
 */
class VtParser {

    private var state = ParseState.GROUND
    private val paramBuf = StringBuilder()
    private var privateMarker: Char? = null
    private val intermediates = StringBuilder()
    private val oscBuf = StringBuilder()

    fun reset() {
        state = ParseState.GROUND
        paramBuf.clear(); privateMarker = null; intermediates.clear(); oscBuf.clear()
    }

    fun parse(input: CharSequence, sink: (Action) -> Unit) {
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (state == ParseState.GROUND && ch.isHighSurrogate() &&
                i + 1 < input.length && input[i + 1].isLowSurrogate()
            ) {
                val cp = Character.toCodePoint(ch, input[i + 1])
                sink(Action.Print(cp, TextWidth.charWidth(cp)))
                i += 2
                continue
            }
            step(ch, sink)
            i++
        }
    }

    private fun step(ch: Char, sink: (Action) -> Unit) {
        when (state) {
            ParseState.GROUND -> ground(ch, sink)
            ParseState.ESCAPE -> escape(ch, sink)
            ParseState.ESCAPE_INTERMEDIATE -> escapeIntermediate(ch, sink)
            ParseState.CSI_ENTRY -> csiEntry(ch, sink)
            ParseState.CSI_PARAM -> csiParam(ch, sink)
            ParseState.CSI_INTERMEDIATE -> csiIntermediate(ch, sink)
            ParseState.CSI_IGNORE -> if (ch in '\u0040'..'\u007E') state = ParseState.GROUND
            ParseState.OSC_STRING -> oscString(ch, sink)
            ParseState.OSC_ESCAPE -> oscEscape(ch, sink)
            ParseState.DCS_ENTRY -> if (ch in '\u0040'..'\u007E') state = ParseState.DCS_PASSTHROUGH
            ParseState.DCS_PASSTHROUGH -> if (ch == '\u001B') state = ParseState.DCS_IGNORE
            ParseState.DCS_IGNORE -> if (ch in '\u0040'..'\u007E') state = ParseState.GROUND
        }
    }

    private fun ground(ch: Char, sink: (Action) -> Unit) {
        when {
            ch == '\u001B' -> state = ParseState.ESCAPE
            ch < '\u0020' -> sink(Action.Execute(ch))
            ch == '\u007F' -> Unit // DEL, ignored
            else -> sink(Action.Print(ch.code, TextWidth.charWidth(ch.code)))
        }
    }

    private fun escape(ch: Char, sink: (Action) -> Unit) {
        when (ch) {
            '\u001B' -> state = ParseState.ESCAPE
            '[' -> { clearCsi(); state = ParseState.CSI_ENTRY }
            ']' -> { oscBuf.clear(); state = ParseState.OSC_STRING }
            'P' -> { clearCsi(); state = ParseState.DCS_ENTRY }
            in '\u0020'..'\u002F' -> { intermediates.clear(); intermediates.append(ch); state = ParseState.ESCAPE_INTERMEDIATE }
            in '\u0030'..'\u007E' -> { sink(Action.EscDispatch("", ch)); state = ParseState.GROUND }
            else -> state = ParseState.GROUND
        }
    }

    private fun escapeIntermediate(ch: Char, sink: (Action) -> Unit) {
        when (ch) {
            in '\u0020'..'\u002F' -> intermediates.append(ch)
            in '\u0030'..'\u007E' -> { sink(Action.EscDispatch(intermediates.toString(), ch)); state = ParseState.GROUND }
            else -> state = ParseState.GROUND
        }
    }

    private fun csiEntry(ch: Char, sink: (Action) -> Unit) {
        when (ch) {
            in '\u0030'..'\u003B' -> { paramBuf.append(ch); state = ParseState.CSI_PARAM }
            in '\u003C'..'\u003F' -> { privateMarker = ch; state = ParseState.CSI_PARAM }
            in '\u0020'..'\u002F' -> { intermediates.append(ch); state = ParseState.CSI_INTERMEDIATE }
            in '\u0040'..'\u007E' -> { dispatchCsi(ch, sink); state = ParseState.GROUND }
            else -> state = ParseState.CSI_IGNORE
        }
    }

    private fun csiParam(ch: Char, sink: (Action) -> Unit) {
        when (ch) {
            in '\u0030'..'\u003B' -> paramBuf.append(ch)
            in '\u003C'..'\u003F' -> paramBuf.append(ch) // tolerate late private bytes
            in '\u0020'..'\u002F' -> { intermediates.append(ch); state = ParseState.CSI_INTERMEDIATE }
            in '\u0040'..'\u007E' -> { dispatchCsi(ch, sink); state = ParseState.GROUND }
            '\u001B' -> state = ParseState.ESCAPE
            else -> state = ParseState.CSI_IGNORE
        }
    }

    private fun csiIntermediate(ch: Char, sink: (Action) -> Unit) {
        when (ch) {
            in '\u0020'..'\u002F' -> intermediates.append(ch)
            in '\u0040'..'\u007E' -> { dispatchCsi(ch, sink); state = ParseState.GROUND }
            else -> state = ParseState.CSI_IGNORE
        }
    }

    private fun oscString(ch: Char, sink: (Action) -> Unit) {
        when (ch) {
            '\u0007' -> { dispatchOsc(sink); state = ParseState.GROUND }
            '\u001B' -> state = ParseState.OSC_ESCAPE
            else -> oscBuf.append(ch)
        }
    }

    private fun oscEscape(ch: Char, sink: (Action) -> Unit) {
        if (ch == '\\') {
            dispatchOsc(sink)
        } else {
            // Not a string terminator — the ESC started a new sequence.
            dispatchOsc(sink)
            state = ParseState.ESCAPE
            step(ch, sink)
            return
        }
        state = ParseState.GROUND
    }

    private fun dispatchOsc(sink: (Action) -> Unit) {
        val payload = oscBuf.toString()
        val sep = payload.indexOf(';')
        val command = if (sep >= 0) payload.substring(0, sep).trim().toIntOrNull() ?: -1
        else payload.trim().toIntOrNull() ?: -1
        val body = if (sep >= 0) payload.substring(sep + 1) else ""
        sink(Action.OscDispatch(command, body))
    }

    private fun dispatchCsi(final: Char, sink: (Action) -> Unit) {
        val raw = paramBuf.toString()
        val params = ArrayList<Int>()
        val subparams = ArrayList<List<Int>>()
        if (raw.isNotEmpty()) {
            for (part in raw.split(';')) {
                val subs = part.split(':').map { it.toIntOrNull() ?: 0 }
                subparams.add(subs)
                params.add(subs.firstOrNull() ?: 0)
            }
        }
        sink(
            Action.CsiDispatch(
                privateMarker = privateMarker,
                params = params,
                subparams = subparams,
                intermediates = intermediates.toString(),
                final = final
            )
        )
    }

    private fun clearCsi() {
        paramBuf.clear(); privateMarker = null; intermediates.clear()
    }
}
