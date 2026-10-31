package com.aliedu.kern.core.terminal.implementation.runtime

/**
 * Drops the terminal's echo of the script from the head of a command's output.
 *
 * The tty prints back everything written to it, so a captured stream starts with the command text
 * itself. Matching that text character by character is not reliable: the shell re-wraps a long
 * pasted line, splits it across reads, and prefixes it with prompt redraws, so the match fails
 * exactly when the line is long — which is every real command. When it failed, the model and the
 * job card received the echoed script, escape sequences and all, as if it were output.
 *
 * Instead the script prints a start marker before running anything, and that marker is built in the
 * shell from a seed (`KERN_M="__KERN_${KERN_S}__"`). The echo therefore contains the seed but never
 * the marker, so the first occurrence of the marker in the stream is real output, not an echo.
 */
internal class OutputGate(
    private val startMarker: String,
    /** How much echo to tolerate before deciding the marker is never coming. */
    private val holdLimit: Int = HOLD_LIMIT
) {

    private val pattern = Regex(Regex.escape(startMarker))
    private val held = StringBuilder()
    private var open = false

    /** True once the start marker has been seen, i.e. the command is actually running. */
    val opened: Boolean get() = open

    /**
     * Returns the part of [text] that is real output, or an empty string while still inside the
     * echo. Text is held until the start marker appears, so a marker split across two reads still
     * resolves.
     */
    fun accept(text: String): String {
        if (open) return text
        held.append(text)
        pattern.find(held)?.let { match ->
            open = true
            val rest = held.substring(match.range.last + 1)
            held.setLength(0)
            return rest
        }
        // No marker: echo is off, or the shell never ran the script. Keep what arrived rather
        // than holding it hostage.
        if (held.length > holdLimit) {
            open = true
            val rest = held.toString()
            held.setLength(0)
            return rest
        }
        return ""
    }

    /** Releases anything still held, for a run that ended before the marker appeared. */
    fun flush(): String {
        if (open) return ""
        open = true
        val rest = held.toString()
        held.setLength(0)
        return rest
    }

    private companion object {
        /** Enough for the echoed script plus a prompt; beyond this the marker is not coming. */
        const val HOLD_LIMIT = 4096
    }
}
