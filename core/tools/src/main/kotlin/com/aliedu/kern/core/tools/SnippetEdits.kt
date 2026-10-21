package com.aliedu.kern.core.tools

/**
 * The exact-snippet edit primitive shared by `edit_file` and `propose_patch`. Each pair is applied
 * in order against the running text, so a later edit sees the result of an earlier one. A pair must
 * match exactly once unless `replaceAll` is set — an ambiguous match is almost always a mistake, and
 * guessing which occurrence the model meant is how a file gets silently corrupted.
 */
internal object SnippetEdits {

    sealed interface Outcome {
        data class Applied(val text: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun apply(text: String, edits: List<Pair<String, String>>, replaceAll: Boolean, path: String): Outcome {
        val direct = applyTo(text, edits, replaceAll, path)
        if (direct is Outcome.Applied || !text.contains("\r\n")) return direct
        // The file uses CRLF but the model sends LF, because that is how it saw the text. Retry the
        // same edits against an LF copy and put the line endings back, instead of failing an edit
        // that is actually correct and sending the model round the same loop again.
        val normalizedEdits = edits.map { (old, new) ->
            old.replace("\r\n", "\n") to new.replace("\r\n", "\n")
        }
        return when (val retried = applyTo(text.replace("\r\n", "\n"), normalizedEdits, replaceAll, path)) {
            is Outcome.Applied -> Outcome.Applied(retried.text.replace("\n", "\r\n"))
            is Outcome.Failed -> direct
        }
    }

    private fun applyTo(
        text: String,
        edits: List<Pair<String, String>>,
        replaceAll: Boolean,
        path: String
    ): Outcome {
        var updated = text
        for ((index, edit) in edits.withIndex()) {
            val (old, new) = edit
            val position = index + 1
            if (old.isEmpty()) {
                return Outcome.Failed("Edit $position: 'old_string' must not be empty.")
            }
            val occurrences = countOccurrences(updated, old)
            when {
                occurrences == 0 -> return Outcome.Failed(
                    "Edit $position: 'old_string' was not found in $path. Read the file again and " +
                        "copy the text exactly, including indentation."
                )

                occurrences > 1 && !replaceAll -> return Outcome.Failed(
                    "Edit $position: 'old_string' appears $occurrences times in $path. Include more " +
                        "surrounding text to make it unique, or set replace_all=true."
                )
            }
            updated = if (replaceAll) updated.replace(old, new) else updated.replaceFirst(old, new)
        }
        return Outcome.Applied(updated)
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}
