package com.aliedu.kern.core.tools

/**
 * Turns a long build/test/lint log into something a model can act on (PRD §4.3 bullet 4). A failing
 * Gradle or npm run can print thousands of lines of progress before the three lines that matter, so
 * the summary keeps the opening context, every line that looks like a real problem, and the tail —
 * and says how much it dropped. Short output is returned untouched.
 */
object CommandOutputSummarizer {

    /** Logs at or below this many lines are already cheap enough to pass through whole. */
    const val MAX_LINES = 400
    private const val HEAD_LINES = 12
    private const val TAIL_LINES = 12
    private const val MAX_SIGNAL_LINES = 40
    private const val MAX_SIGNAL_CHARS = 300

    private val SIGNAL_PATTERNS = listOf(
        Regex("""(?i)\berror\b"""),
        Regex("""(?i)\bfailed\b"""),
        Regex("""(?i)\bfailure\b"""),
        Regex("""(?i)\bexception\b"""),
        Regex("""(?i)\bcannot find\b"""),
        Regex("""(?i)\bundefined reference\b"""),
        Regex("""(?i)\bunresolved reference\b"""),
        Regex("""(?i)\bnot found\b"""),
        Regex("""(?i)\bwarning:"""),
        Regex("""(?i)\bFAILED\b"""),
        Regex("""^\s*[✗✘×]"""),
        Regex("""(?i)\bBUILD FAILED\b"""),
        Regex("""(?i)\bcompilation error\b"""),
        Regex("""(?i)\bassertionerror\b""")
    )

    fun summarize(output: String): String {
        val lines = output.lines()
        if (lines.size <= MAX_LINES) return output

        val head = lines.take(HEAD_LINES)
        val tail = lines.takeLast(TAIL_LINES)

        // LinkedHashSet keeps the first occurrence of each signal line, in order, without duplicates.
        val signals = LinkedHashSet<String>()
        for (line in lines) {
            if (signals.size >= MAX_SIGNAL_LINES) break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (SIGNAL_PATTERNS.any { it.containsMatchIn(line) }) {
                signals.add(trimmed.take(MAX_SIGNAL_CHARS))
            }
        }

        val omitted = (lines.size - head.size - tail.size).coerceAtLeast(0)
        return buildString {
            append(head.joinToString("\n"))
            if (signals.isNotEmpty()) {
                appendLine()
                appendLine("--- key lines ---")
                append(signals.joinToString("\n"))
            }
            appendLine()
            appendLine("--- last lines ---")
            append(tail.joinToString("\n"))
            append("\n[summarised: $omitted of ${lines.size} lines omitted]")
        }
    }
}
