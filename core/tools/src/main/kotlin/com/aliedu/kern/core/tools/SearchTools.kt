package com.aliedu.kern.core.tools

import com.aliedu.kern.core.model.FileKind
import com.aliedu.kern.core.model.FileNode
import com.aliedu.kern.core.providers.api.ToolSchema
import com.aliedu.kern.core.workspace.WorkspaceAccessException
import com.aliedu.kern.core.workspace.WorkspaceAdapter
import com.aliedu.kern.core.workspace.WorkspacePaths
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import javax.inject.Inject
import javax.inject.Singleton

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.flag(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

/**
 * Translates a glob into a regex. A pattern without `/` matches against the file name at any depth;
 * a pattern that contains a separator is anchored to the search root. Double star crosses
 * directories, single star and `?` stay within one segment.
 */
internal object GlobPattern {
    /**
     * [anchored] decides whether the pattern must match from the search root or may match at any
     * depth. It defaults to "contains a separator", but a gitignore rule that starts with `/` is
     * anchored even when the rest has none.
     */
    fun toRegex(
        pattern: String,
        anchored: Boolean = pattern.trim().replace('\\', '/').contains('/')
    ): Regex? {
        val trimmed = pattern.trim().replace('\\', '/')
        if (trimmed.isEmpty()) return null
        val body = buildString {
            var index = 0
            while (index < trimmed.length) {
                when (val char = trimmed[index]) {
                    '*' -> {
                        if (index + 1 < trimmed.length && trimmed[index + 1] == '*') {
                            append(".*")
                            index++
                            if (index + 1 < trimmed.length && trimmed[index + 1] == '/') index++
                        } else {
                            append("[^/]*")
                        }
                    }

                    '?' -> append("[^/]")
                    else -> append(Regex.escape(char.toString()))
                }
                index++
            }
        }
        val prefix = if (anchored) "" else "(?:.*/)?"
        return Regex("^$prefix$body$")
    }
}

/**
 * gitignore-style ignore rules (PRD §4.3 bullet 5). A pragmatic subset: comments, negation, a
 * leading `/` to anchor, a trailing `/` for directories, and `*` / `**` / `?`. Later rules win, so
 * a negation can re-include a file. It cannot re-include a file under an ignored directory, which
 * matches git's own rule and is why the walker prunes ignored directories outright.
 */
internal class IgnoreRules private constructor(private val rules: List<Rule>) {

    fun ignores(path: String, isDirectory: Boolean): Boolean {
        if (path.isEmpty()) return false
        var ignored = false
        for (rule in rules) {
            if (rule.matches(path, isDirectory)) ignored = !rule.negated
        }
        return ignored
    }

    private class Rule(val regex: Regex, val negated: Boolean, val dirOnly: Boolean) {
        fun matches(path: String, isDirectory: Boolean): Boolean {
            if (!dirOnly) return regex.matches(path)
            var candidate = path
            var directory = isDirectory
            while (true) {
                if (directory && regex.matches(candidate)) return true
                val slash = candidate.lastIndexOf('/')
                if (slash < 0) return false
                candidate = candidate.substring(0, slash)
                directory = true
            }
        }
    }

    companion object {
        /** Directory names an agent essentially never wants, regardless of any .gitignore. */
        val DEFAULT_LINES = listOf(
            ".git/", ".gradle/", ".idea/", ".venv/", "node_modules/", "build/", "dist/",
            "target/", "__pycache__/", ".DS_Store"
        )

        fun defaults(): IgnoreRules = parse(DEFAULT_LINES)

        fun parse(lines: List<String>): IgnoreRules {
            val parsed = ArrayList<Rule>(lines.size)
            for (raw in lines) {
                val line = raw.trimEnd()
                if (line.isEmpty() || line.startsWith("#")) continue
                var pattern = line
                var negated = false
                if (pattern.startsWith("!")) {
                    negated = true
                    pattern = pattern.substring(1)
                }
                if (pattern.startsWith("\\#") || pattern.startsWith("\\!")) {
                    pattern = pattern.substring(1)
                }
                val dirOnly = pattern.endsWith("/")
                if (dirOnly) pattern = pattern.dropLast(1)
                val anchored = pattern.startsWith("/") || pattern.contains('/')
                pattern = pattern.removePrefix("/")
                if (pattern.isEmpty()) continue
                val regex = GlobPattern.toRegex(pattern, anchored = anchored) ?: continue
                parsed.add(Rule(regex, negated, dirOnly))
            }
            return IgnoreRules(parsed)
        }
    }
}

/**
 * Bounded recursive walk shared by the search tools, so the caps that keep a workspace scan from
 * eating the context budget live in one place (PRD 9.3). Built-in defaults plus the workspace-root
 * `.gitignore` prune noise before it reaches the model.
 */
internal class WorkspaceWalker(
    private val maxFiles: Int = MAX_FILES,
    private val maxDirectories: Int = MAX_DIRECTORIES
) {

    suspend fun files(adapter: WorkspaceAdapter, root: String): List<FileNode> {
        val rules = loadIgnoreRules(adapter)
        val found = ArrayList<FileNode>()
        val queue = ArrayDeque<String>()
        queue.add(root)
        var directories = 0
        while (queue.isNotEmpty() && found.size < maxFiles) {
            if (directories++ >= maxDirectories) break
            val directory = queue.removeFirst()
            val nodes = try {
                adapter.list(directory)
            } catch (e: WorkspaceAccessException) {
                continue
            }
            for (node in nodes) {
                if (node.kind == FileKind.DIRECTORY) {
                    if (!rules.ignores(node.path, isDirectory = true)) queue.add(node.path)
                } else {
                    if (rules.ignores(node.path, isDirectory = false)) continue
                    found.add(node)
                    if (found.size >= maxFiles) break
                }
            }
        }
        return found
    }

    private suspend fun loadIgnoreRules(adapter: WorkspaceAdapter): IgnoreRules {
        val defaults = IgnoreRules.defaults()
        val lines = try {
            if (!adapter.exists(GITIGNORE)) emptyList()
            else adapter.readText(GITIGNORE, MAX_GITIGNORE_BYTES).text.lines()
        } catch (e: WorkspaceAccessException) {
            emptyList()
        }
        if (lines.isEmpty()) return defaults
        // Defaults first, then .gitignore so a project rule can override a built-in.
        return IgnoreRules.parse(IgnoreRules.DEFAULT_LINES + lines)
    }

    companion object {
        const val MAX_FILES = 2_000
        const val MAX_DIRECTORIES = 500
        private const val GITIGNORE = ".gitignore"
        private const val MAX_GITIGNORE_BYTES = 64_000L
    }
}

/**
 * Finds files by glob pattern: a double star crosses directories, a single star stays within one.
 * [SearchFilesTool] matches substrings in names and contents; this answers "which files are these",
 * which is what an agent needs before reading or editing.
 */
@Singleton
class GlobTool @Inject constructor() : Tool {
    private val walker = WorkspaceWalker()

    override val name = "glob"
    override val isReadOnly = true
    override val description =
        "Find workspace files by glob pattern, e.g. '**/*.kt' or 'src/**/*Test*'. Returns matching " +
            "paths; ignored paths (.gitignore plus common build folders) are pruned."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{"pattern":{"type":"string","description":"Glob pattern; '**' crosses directories, '*' stays within one."},"path":{"type":"string","description":"Workspace-relative folder to search under; omit for the whole workspace."}},"required":["pattern"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val pattern = args.text("pattern")
            ?: return ToolResult(false, "", "Missing required argument 'pattern'.")
        val root = args.text("path").orEmpty().let { raw ->
            context.resolvePath(raw) ?: return ToolResult(false, "", "Invalid path: $raw")
        }
        val regex = GlobPattern.toRegex(pattern)
            ?: return ToolResult(false, "", "Unsupported glob pattern: $pattern")

        return try {
            val matches = walker.files(context.adapter, root)
                .filter { node -> regex.matches(node.path) }
                .take(context.maxResults)
            if (matches.isEmpty()) {
                ToolResult(true, "No files match '$pattern'.")
            } else {
                ToolResult(true, matches.joinToString("\n") { it.path })
            }
        } catch (e: WorkspaceAccessException) {
            ToolResult(false, "", e.message)
        }
    }
}

/** One matching line, kept small so a broad regex cannot flood the context. */
private const val MAX_LINE_CHARS = 200

/**
 * Regex content search across the workspace, reporting `path:line: text` like `grep -n`.
 * [SearchFilesTool] does literal substring matching; agents routinely need a pattern.
 */
@Singleton
class GrepTool @Inject constructor() : Tool {
    private val walker = WorkspaceWalker()

    override val name = "grep"
    override val isReadOnly = true
    override val description =
        "Search file contents with a regular expression. Returns 'path:line: text' for each match."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{"pattern":{"type":"string","description":"Regular expression to match against each line."},"path":{"type":"string","description":"Workspace-relative folder to search under; omit for the whole workspace."},"ignore_case":{"type":"boolean"}},"required":["pattern"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val pattern = args.text("pattern")
            ?: return ToolResult(false, "", "Missing required argument 'pattern'.")
        val root = args.text("path").orEmpty().let { raw ->
            context.resolvePath(raw) ?: return ToolResult(false, "", "Invalid path: $raw")
        }
        val ignoreCase = args.flag("ignore_case") ?: false
        val regex = try {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (e: IllegalArgumentException) {
            return ToolResult(false, "", "Invalid regular expression: ${e.message}")
        }

        return try {
            val hits = ArrayList<String>()
            for (node in walker.files(context.adapter, root)) {
                if (hits.size >= context.maxResults) break
                val size = node.sizeBytes ?: 0L
                if (node.kind == FileKind.BINARY || size > MAX_SEARCH_FILE_BYTES) continue
                val text = try {
                    context.adapter.readText(node.path).text
                } catch (e: WorkspaceAccessException) {
                    continue
                }
                text.lineSequence().forEachIndexed { index, line ->
                    if (hits.size >= context.maxResults) return@forEachIndexed
                    if (regex.containsMatchIn(line)) {
                        val snippet = line.trim().take(MAX_LINE_CHARS)
                        hits.add("${node.path}:${index + 1}: $snippet")
                    }
                }
            }
            if (hits.isEmpty()) {
                ToolResult(true, "No matches for '$pattern'.")
            } else {
                val suffix = if (hits.size >= context.maxResults) "\n[limited to ${context.maxResults} matches]" else ""
                ToolResult(true, hits.joinToString("\n") + suffix)
            }
        } catch (e: WorkspaceAccessException) {
            ToolResult(false, "", e.message)
        }
    }

    private companion object {
        const val MAX_SEARCH_FILE_BYTES = 512_000L
    }
}
