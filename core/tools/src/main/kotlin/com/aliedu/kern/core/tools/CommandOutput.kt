package com.aliedu.kern.core.tools

/**
 * Bounds a tool result so the model sees the *shape* of a long output, not just its opening.
 *
 * Tool results used to be cut with `take(4_000)`, which kept the first four thousand characters
 * and silently dropped the rest. For the commands that matter most that is exactly the wrong half:
 * a failing `apt install`, `npm install` or `make` prints its error at the *end*. [headTail] keeps
 * both ends and says how much it dropped, so the model never reasons from a truncated log it thinks
 * is complete.
 */
object OutputWindow {

    /** Room reserved for the elision marker, so the result never exceeds the caller's limit. */
    private const val MARKER_RESERVE = 64

    /**
     * Returns [text] unchanged when it fits in [limit]; otherwise the first 60% and last 40% of the
     * budget, joined by a marker naming how many characters were dropped.
     */
    fun headTail(text: String, limit: Int): String {
        if (limit <= 0) return ""
        if (text.length <= limit) return text
        val budget = (limit - MARKER_RESERVE).coerceAtLeast(limit / 2)
        val head = (budget * 3) / 5
        val tail = budget - head
        val headEnd = safeEnd(text, head)
        val tailStart = safeStart(text, text.length - tail)
        val omitted = (text.length - headEnd - (text.length - tailStart)).coerceAtLeast(0)
        val marker = "\n… [" + omitted + " characters omitted] …\n"
        return text.substring(0, headEnd) + marker + text.substring(tailStart)
    }

    /** Moves [end] back one character when it would split a surrogate pair. */
    fun safeEnd(text: String, end: Int): Int {
        val bounded = end.coerceIn(0, text.length)
        return if (bounded in 1 until text.length && Character.isHighSurrogate(text[bounded - 1])) {
            bounded - 1
        } else {
            bounded
        }
    }

    /** Moves [start] forward one character when it would split a surrogate pair. */
    fun safeStart(text: String, start: Int): Int {
        val bounded = start.coerceIn(0, text.length)
        return if (bounded in 1 until text.length && Character.isLowSurrogate(text[bounded])) {
            bounded + 1
        } else {
            bounded
        }
    }
}

/**
 * What to tell the model when a command did not simply fail: it was killed.
 *
 * There is no timeout any more, so the only thing that ends a foreground command early is the user
 * cancelling the turn, which kills its whole process group (SIGTERM, then SIGKILL after a grace
 * period). The process does not keep running and its output after the kill is gone for good. A
 * package manager interrupted that way is the one case with a concrete recovery step, and leaving
 * it out is what turned one slow install into a broken environment.
 */
object CommandRecovery {

    private val PACKAGE_MANAGER =
        Regex("""\b(apt|apt-get|dpkg|apk|aptitude|yum|dnf|pacman|zypper)\b""", RegexOption.IGNORE_CASE)

    /** True when [command] drives a package manager, whose database can be left half-configured. */
    fun isPackageManager(command: String): Boolean = PACKAGE_MANAGER.containsMatchIn(command)

    /** The note appended to a command that was killed because the turn was cancelled. */
    fun killedNote(command: String): String = buildString {
        append(
            "The command was killed, not just stopped: cancelling the turn kills the whole " +
                "process group (SIGTERM, then SIGKILL after a short grace period), so anything it " +
                "had started died with it. The output above is what it printed before the kill; " +
                "anything after that was not captured."
        )
        if (isPackageManager(command)) {
            append(" ")
            append(
                "A package manager interrupted mid-transaction can leave its database locked or " +
                    "half-configured, so the next install fails with a dpkg lock or " +
                    "half-installed error: run `dpkg --configure -a` (Debian/Ubuntu) or " +
                    "`apk fix` (Alpine) before retrying."
            )
        }
        append(" ")
        append(
            "If the work needs to outlive the turn, run it with background_start and collect the " +
                "result with background_wait."
        )
    }
}

/**
 * Keeps the beginning *and* the end of a long stream, bounded by [cap] characters.
 *
 * Retaining only the first N characters is what made a failing `apt install` look healthy: the
 * error is the last thing the command prints. The tail window is the part the model actually needs,
 * and [truncated] says plainly that something was dropped, so no layer has to guess.
 */
class CappedBuffer(private val cap: Int) {

    private val headCap = (cap * 3) / 5
    private val tailCap = (cap - headCap).coerceAtLeast(0)
    private val head = StringBuilder()
    private val tail = StringBuilder()

    /** Characters appended so far, including the ones that did not fit. */
    var total: Int = 0
        private set

    var truncated: Boolean = false
        private set

    fun append(text: String) {
        if (text.isEmpty()) return
        total += text.length
        if (head.length < headCap) {
            val room = headCap - head.length
            if (text.length <= room) {
                head.append(text)
                return
            }
            // Cut on a character boundary: half a surrogate pair is a broken glyph in the result.
            val cut = OutputWindow.safeEnd(text, room)
            head.append(text, 0, cut)
            truncated = true
            appendTail(text.substring(cut))
        } else {
            truncated = true
            appendTail(text)
        }
    }

    /** The retained text, with a marker naming what fell between the head and the tail. */
    fun text(): String {
        if (!truncated) return head.toString()
        val omitted = (total - head.length - tail.length).coerceAtLeast(0)
        return head.toString() + "\n… [" + omitted + " characters omitted] …\n" + tail
    }

    private fun appendTail(text: String) {
        if (tailCap <= 0) return
        tail.append(text)
        if (tail.length > tailCap) {
            var drop = tail.length - tailCap
            if (drop < tail.length && Character.isLowSurrogate(tail[drop])) drop += 1
            tail.delete(0, drop)
        }
    }
}
