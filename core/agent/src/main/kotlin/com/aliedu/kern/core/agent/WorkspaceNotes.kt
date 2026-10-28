package com.aliedu.kern.core.agent

import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.tools.MemoryNote
import com.aliedu.kern.core.tools.PlanProposal
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Singleton

/**
 * The agent's per-workspace notes on disk:
 *
 * ```
 * ~/.kern/PLANS/<workspace>/
 *     plan.md        the plan the user approved for the current task
 *     memory.md      durable facts about this workspace, injected into every turn
 *     archive/       superseded plans, kept so a decision can be re-read later
 * ```
 *
 * One directory per workspace keeps a project's plans and memory together, which is what makes the
 * memory useful: it describes *this* codebase, not every codebase the app has ever opened.
 *
 * All methods are blocking and small; callers run them off the main thread.
 */
@Singleton
class WorkspaceNotes(
    /** The `~/.kern` directory. Injected by [NotesModule]; tests pass a temporary folder. */
    private val root: File
) {

    /** Directory that holds this workspace's plans and memory. */
    fun directory(workspace: Workspace): File = File(File(root, PLANS_DIR), slug(workspace))

    /** Absolute path shown to the user (and quoted in the system prompt). */
    fun location(workspace: Workspace): String = directory(workspace).absolutePath

    fun plan(workspace: Workspace): String? =
        read(File(directory(workspace), PLAN_FILE))?.takeIf { it.isNotBlank() }

    /**
     * Persists [plan] as the workspace's current plan. The plan it replaces is archived rather than
     * deleted, so the history of a long task stays readable.
     */
    fun savePlan(workspace: Workspace, plan: PlanProposal) {
        val dir = directory(workspace)
        val file = File(dir, PLAN_FILE)
        read(file)?.takeIf { it.isNotBlank() }?.let { previous ->
            val archive = File(dir, ARCHIVE_DIR).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            runCatching { File(archive, "$stamp-${slug(plan.title)}.md").writeText(previous) }
        }
        dir.mkdirs()
        file.writeText(
            buildString {
                appendLine("# ${plan.title}")
                appendLine()
                appendLine(plan.markdown)
                appendLine()
                appendLine("_Approved ${timestamp()}._")
            }
        )
    }

    /** Current memory file contents, or null when nothing has been remembered yet. */
    fun memory(workspace: Workspace): String? =
        read(File(directory(workspace), MEMORY_FILE))?.takeIf { it.isNotBlank() }

    /**
     * Deletes every workspace's memory file, leaving plans and archived plans alone.
     *
     * It walks the plans directory rather than the workspace list so memory written for a workspace
     * that has since been removed is cleared too. Returns how many files were deleted.
     */
    fun clearAllMemory(): Int {
        val plans = File(root, PLANS_DIR).takeIf { it.isDirectory } ?: return 0
        return plans.listFiles().orEmpty()
            .filter { it.isDirectory }
            .count { dir -> runCatching { File(dir, MEMORY_FILE).delete() }.getOrDefault(false) }
    }

    /** True when any workspace has a memory file — used to enable the settings action. */
    fun hasMemory(): Boolean {
        val plans = File(root, PLANS_DIR).takeIf { it.isDirectory } ?: return false
        return plans.listFiles().orEmpty().any { File(it, MEMORY_FILE).isFile }
    }

    /** Appends one durable fact under its section, skipping an exact duplicate. */
    fun remember(workspace: Workspace, note: MemoryNote) {
        val bullet = "- ${note.text.trim()}"
        if (bullet.length <= 2) return
        val file = File(directory(workspace), MEMORY_FILE)
        val current = read(file).orEmpty()
        if (current.lineSequence().any { it.trim() == bullet }) return
        val heading = "## ${sectionTitle(note.section)}"
        val lines = current.lines().toMutableList()
        if (current.isBlank()) {
            lines.clear()
            lines.add("# Memory — ${workspace.displayName}")
            lines.add("")
            lines.add(HEADER_NOTE)
            lines.add("")
        }
        val index = lines.indexOfFirst { it.trim().equals(heading, ignoreCase = true) }
        if (index < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add(heading)
            lines.add(bullet)
        } else {
            // Insert after the section's last bullet so the newest note stays with its heading.
            var insertAt = index + 1
            var cursor = index + 1
            while (cursor < lines.size && !lines[cursor].startsWith("## ")) {
                if (lines[cursor].startsWith("- ")) insertAt = cursor + 1
                cursor++
            }
            lines.add(insertAt, bullet)
        }
        file.parentFile?.mkdirs()
        file.writeText(trim(lines))
    }

    /**
     * Records what a finished turn did. This is the automatic half of the memory: even when the
     * model forgets to call `remember`, the workspace keeps a short trail of what has been done to
     * it, which the next turn reads.
     */
    fun appendTaskLog(workspace: Workspace, request: String, outcome: String) {
        val file = File(directory(workspace), MEMORY_FILE)
        val current = read(file).orEmpty()
        val lines = current.lines().toMutableList()
        if (current.isBlank()) {
            lines.clear()
            lines.add("# Memory — ${workspace.displayName}")
            lines.add("")
            lines.add(HEADER_NOTE)
            lines.add("")
        }
        val heading = "## Task log"
        val index = lines.indexOfFirst { it.trim().equals(heading, ignoreCase = true) }
        val entry = "- ${timestamp()} — ${request.oneLine()} → $outcome"
        if (index < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add(heading)
            lines.add(entry)
        } else {
            var insertAt = index + 1
            var cursor = index + 1
            while (cursor < lines.size && !lines[cursor].startsWith("## ")) {
                if (lines[cursor].startsWith("- ")) insertAt = cursor + 1
                cursor++
            }
            lines.add(insertAt, entry)
        }
        file.parentFile?.mkdirs()
        file.writeText(trim(lines, maxTaskLogEntries = MAX_TASK_LOG))
    }

    // -- internals ---------------------------------------------------------------------------

    private fun read(file: File): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    /** Keeps the file inside the context budget by dropping the oldest task-log lines. */
    private fun trim(lines: MutableList<String>, maxTaskLogEntries: Int = Int.MAX_VALUE): String {
        val heading = lines.indexOfFirst { it.trim().equals("## Task log", ignoreCase = true) }
        if (heading >= 0 && maxTaskLogEntries != Int.MAX_VALUE) {
            val entries = ArrayList<Int>()
            var cursor = heading + 1
            while (cursor < lines.size && !lines[cursor].startsWith("## ")) {
                if (lines[cursor].startsWith("- ")) entries.add(cursor)
                cursor++
            }
            if (entries.size > maxTaskLogEntries) {
                val drop = entries.take(entries.size - maxTaskLogEntries)
                drop.asReversed().forEach { lines.removeAt(it) }
            }
        }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private fun sectionTitle(section: String): String = when (section) {
        "conventions" -> "Conventions"
        "decisions" -> "Decisions"
        "gotchas" -> "Gotchas"
        "preferences" -> "Preferences"
        else -> "Project"
    }

    /**
     * A readable, filesystem-safe directory name. The workspace's display name is what the user
     * recognises ("git-socio"), so it wins; the id is only a fallback.
     */
    private fun slug(workspace: Workspace): String = slug(workspace.displayName)

    private fun slug(raw: String): String {
        val cleaned = raw.lowercase(Locale.US)
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
        return cleaned.take(48).ifEmpty { "workspace" }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    private fun String.oneLine(): String = trim().replace(Regex("\\s+"), " ").take(120)

    private companion object {
        const val PLANS_DIR = "PLANS"
        const val PLAN_FILE = "plan.md"
        const val MEMORY_FILE = "memory.md"
        const val ARCHIVE_DIR = "archive"
        const val MAX_TASK_LOG = 40

        /**
         * Written under the title so anyone reading the file — the user included — knows what it is
         * for. The agent gets the same rule in the prompt: reference, not a task list.
         */
        const val HEADER_NOTE =
            "_Notes about this workspace, written by the agent. Reference only: not a task list._"
    }
}
