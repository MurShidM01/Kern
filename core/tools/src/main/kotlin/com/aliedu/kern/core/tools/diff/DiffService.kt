package com.aliedu.kern.core.tools.diff

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A unified diff plus change counts for one file (PRD 9.5). */
data class FileDiff(
    val path: String,
    val unifiedDiff: String,
    val insertions: Int,
    val deletions: Int
)

/**
 * Computes reviewable diffs and content hashes used to detect file changes between preview and
 * apply (PRD 9.2 apply_edit, 9.5). Syntax-neutral unified diff first; highlighting is an
 * enhancement, not a correctness dependency.
 */
@Singleton
class DiffService @Inject constructor() {

    fun unifiedDiff(path: String, oldText: String, newText: String, contextLines: Int = 3): FileDiff {
        val oldLines = oldText.split("\n")
        val newLines = newText.split("\n")
        val patch = DiffUtils.diff(oldLines, newLines)
        val unified = UnifiedDiffUtils.generateUnifiedDiff(path, path, oldLines, patch, contextLines)
            .joinToString("\n")

        var insertions = 0
        var deletions = 0
        patch.deltas.forEach { delta ->
            insertions += delta.target.lines.size
            deletions += delta.source.lines.size
        }
        return FileDiff(path, unified, insertions, deletions)
    }

    /** SHA-256 of file content; used as the patch-approval binding hash (PRD 14.1 PendingPatch). */
    fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Stable hash of a multi-file proposal, used to bind approval to the exact reviewed change. */
    fun proposalHash(files: List<Pair<String, String>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.first }.forEach { (path, content) ->
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(content.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
