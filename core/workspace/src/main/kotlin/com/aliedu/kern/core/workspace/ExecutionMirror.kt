package com.aliedu.kern.core.workspace

import com.github.difflib.DiffUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How a file in the execution mirror differs from its source (PRD 11.4). */
enum class ChangeKind { ADDED, MODIFIED, DELETED }

data class WorkspaceChange(
    val path: String,
    val kind: ChangeKind,
    val additions: Int = 0,
    val deletions: Int = 0
)

enum class DiffLineKind { CONTEXT, ADD, REMOVE }

data class DiffLine(val kind: DiffLineKind, val text: String)

data class SyncReport(val synced: List<String>, val failed: List<String>)

/**
 * Mirrors a workspace [source] tree into a local [mirror] tree and reports/syncs differences.
 *
 * Deliberately pure over [ExecutionTree] so the copy / diff / sync-back logic is unit-testable without
 * Android or SAF. The rule (PRD 11.4): commands run in the mirror; the source is only ever written by
 * an explicit [syncBack] of user-chosen paths — never automatically.
 */
class ExecutionMirror(
    private val source: ExecutionTree,
    private val mirror: ExecutionTree
) {

    /** Copy every source file into the mirror (creating parents as needed). */
    suspend fun copyAll(): Unit = withContext(Dispatchers.IO) {
        for (path in source.listFiles()) {
            mirror.write(path, source.read(path))
        }
    }

    /** Files that differ between the mirror and the source, with a line-level +/- summary. */
    suspend fun listChanges(): List<WorkspaceChange> = withContext(Dispatchers.IO) {
        val sourceFiles = source.listFiles().toSet()
        val mirrorFiles = mirror.listFiles().toSet()

        val changes = ArrayList<WorkspaceChange>()
        (mirrorFiles - sourceFiles).sorted().forEach { path ->
            val (adds, dels) = lineDelta(ByteArray(0), mirror.read(path))
            changes.add(WorkspaceChange(path, ChangeKind.ADDED, adds, dels))
        }
        (sourceFiles - mirrorFiles).sorted().forEach { path ->
            val (adds, dels) = lineDelta(source.read(path), ByteArray(0))
            changes.add(WorkspaceChange(path, ChangeKind.DELETED, adds, dels))
        }
        (sourceFiles intersect mirrorFiles).sorted().forEach { path ->
            val old = source.read(path)
            val new = mirror.read(path)
            if (!old.contentEquals(new)) {
                val (adds, dels) = lineDelta(old, new)
                changes.add(WorkspaceChange(path, ChangeKind.MODIFIED, adds, dels))
            }
        }
        changes
    }

    /** A unified-style line diff of one path (mirror vs source) for the review UI. */
    suspend fun fileDiff(path: String): List<DiffLine> = withContext(Dispatchers.IO) {
        val oldBytes = runCatching { source.read(path) }.getOrNull() ?: ByteArray(0)
        val newBytes = runCatching { mirror.read(path) }.getOrNull() ?: ByteArray(0)
        diffLines(oldBytes, newBytes)
    }

    /** Copy the given mirror files back over the source. Deletions propagate for source-only paths. */
    suspend fun syncBack(paths: List<String>): SyncReport = withContext(Dispatchers.IO) {
        val sourceFiles = source.listFiles().toSet()
        val mirrorFiles = mirror.listFiles().toSet()
        val synced = ArrayList<String>()
        val failed = ArrayList<String>()

        for (path in paths) {
            val ok = runCatching {
                when {
                    path in mirrorFiles -> source.write(path, mirror.read(path))
                    path in sourceFiles -> source.delete(path)
                    else -> true
                }
            }.getOrDefault(false)
            if (ok) synced.add(path) else failed.add(path)
        }
        SyncReport(synced, failed)
    }

    /** Discard mirror edits for the given paths by restoring them from the source. */
    suspend fun restore(paths: List<String>): Unit = withContext(Dispatchers.IO) {
        val sourceFiles = source.listFiles().toSet()
        for (path in paths) {
            if (path in sourceFiles) mirror.write(path, source.read(path)) else mirror.delete(path)
        }
    }

    private fun lineDelta(oldBytes: ByteArray, newBytes: ByteArray): Pair<Int, Int> {
        if (isBinary(oldBytes) || isBinary(newBytes)) return 0 to 0
        var adds = 0
        var dels = 0
        for (line in diffLines(oldBytes, newBytes)) {
            when (line.kind) {
                DiffLineKind.ADD -> adds++
                DiffLineKind.REMOVE -> dels++
                DiffLineKind.CONTEXT -> Unit
            }
        }
        return adds to dels
    }

    private fun diffLines(oldBytes: ByteArray, newBytes: ByteArray): List<DiffLine> {
        val oldLines = splitLines(oldBytes)
        val newLines = splitLines(newBytes)
        val patch = DiffUtils.diff(oldLines, newLines)
        val out = ArrayList<DiffLine>()
        var cursor = 0
        for (delta in patch.deltas) {
            val position = delta.source.position
            while (cursor < position && cursor < oldLines.size) {
                out.add(DiffLine(DiffLineKind.CONTEXT, oldLines[cursor]))
                cursor++
            }
            delta.source.lines.forEach { out.add(DiffLine(DiffLineKind.REMOVE, it)) }
            delta.target.lines.forEach { out.add(DiffLine(DiffLineKind.ADD, it)) }
            cursor = position + delta.source.lines.size
        }
        while (cursor < oldLines.size) {
            out.add(DiffLine(DiffLineKind.CONTEXT, oldLines[cursor]))
            cursor++
        }
        return out
    }

    private fun splitLines(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        return String(bytes, Charsets.UTF_8).split("\n")
    }

    private fun isBinary(bytes: ByteArray): Boolean {
        for (b in bytes) if (b.toInt() == 0) return true
        return false
    }
}
