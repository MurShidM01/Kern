package com.aliedu.kern.core.tools

import com.aliedu.kern.core.workspace.WorkspacePaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Repairs a tool call's arguments against the tool's own schema before the tool ever sees them.
 *
 * Models get the *shape* of an argument wrong far more often than the value: a list sent as a JSON
 * string, an object wrapped in an extra "input" key, a number where a path belongs, a null for an
 * optional field, or Claude/Gemini-style names ("file_path", "old_str") for a tool that documents
 * "path" and "old_string". Every one of those used to come back as "missing required argument",
 * which the model usually answers by repeating the same broken call until the turn dies. Coercing
 * once here fixes every tool at the same time instead of each tool guessing on its own.
 */
object ToolArgs {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Alternate names a model may use for a documented argument, in priority order. */
    private val ALIASES = mapOf(
        "path" to listOf("file_path", "filepath", "filename", "target_file", "file"),
        "content" to listOf("file_text", "contents", "text", "new_text"),
        "new_content" to listOf("content", "file_text", "text"),
        "old_string" to listOf("old_str", "old_text", "search", "find"),
        "new_string" to listOf("new_str", "new_text", "replace", "replacement"),
        "edits" to listOf("changes", "replacements"),
        "command" to listOf("cmd", "command_line", "shell_command"),
        "pattern" to listOf("regex", "query", "glob"),
        "query" to listOf("search", "term"),
        "todos" to listOf("tasks", "items", "todo_list"),
        "plan" to listOf("markdown", "plan_markdown"),
        "url" to listOf("link", "uri"),
        "timeout_seconds" to listOf("timeout", "timeout_ms")
    )

    /** Keys a model sometimes wraps the whole payload in, e.g. {"input": {...}}. */
    private val WRAPPERS = listOf("input", "arguments", "args", "parameters", "payload")

    fun normalize(args: JsonObject, schemaJson: String): JsonObject {
        val schema = runCatching { json.parseToJsonElement(schemaJson).jsonObject }.getOrNull()
            ?: return args
        val properties = schema["properties"] as? JsonObject ?: return args
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .orEmpty()

        // Unwrap {"input": {...}} only when the real keys are missing, so a tool that legitimately
        // takes an argument named "input" is left alone.
        var source = args
        if (required.any { it !in source }) {
            val wrapped = WRAPPERS.firstNotNullOfOrNull { key ->
                (source[key] as? JsonObject)?.takeIf { candidate -> required.any { it in candidate } }
            }
            if (wrapped != null) source = wrapped
        }

        val normalized = normalizeObject(source, properties)
        // Nothing declared and nothing unwrapped: hand the original object back untouched.
        return if (normalized.isEmpty() && args.isNotEmpty()) args else normalized
    }

    private fun normalizeObject(source: JsonObject, properties: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(source.size + properties.size)
        for ((name, spec) in properties) {
            val declared = spec as? JsonObject ?: continue
            val type = (declared["type"] as? JsonPrimitive)?.content
            var value = source[name]
            if (value == null || value is JsonNull) {
                val alias = ALIASES[name].orEmpty().firstOrNull { it in source }
                if (alias != null) value = source[alias]
            }
            if (value == null || value is JsonNull) continue
            out[name] = coerce(value, type, declared)
        }
        // Preserve undeclared keys so a tool that reads an extra field still sees it.
        for ((name, value) in source) {
            if (name !in out && value !is JsonNull) out[name] = value
        }
        return JsonObject(out)
    }

    private fun coerce(value: JsonElement, type: String?, spec: JsonObject): JsonElement = when (type) {
        "array" -> {
            val parsed = value as? JsonArray ?: parseEmbedded(value)
            if (parsed is JsonArray) {
                val items = spec["items"] as? JsonObject
                val itemProps = items?.get("properties") as? JsonObject
                if (itemProps != null) {
                    JsonArray(parsed.map { item ->
                        if (item is JsonObject) normalizeObject(item, itemProps) else item
                    })
                } else {
                    parsed
                }
            } else {
                value
            }
        }

        "object" -> value as? JsonObject ?: parseEmbedded(value) ?: value

        "string" -> {
            val primitive = value as? JsonPrimitive
            if (primitive != null && !primitive.isString) JsonPrimitive(primitive.content) else value
        }

        "boolean" -> {
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
            when (text) {
                "true", "yes", "1" -> JsonPrimitive(true)
                "false", "no", "0" -> JsonPrimitive(false)
                else -> (value as? JsonPrimitive)?.let { p ->
                    p.booleanOrNull?.let { JsonPrimitive(it) } ?: value
                } ?: value
            }
        }

        "integer", "number" -> {
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            val parsed = text?.toIntOrNull()
            if (parsed != null) JsonPrimitive(parsed) else {
                (value as? JsonPrimitive)?.let { p ->
                    p.intOrNull?.let { JsonPrimitive(it) } ?: value
                } ?: value
            }
        }

        else -> value
    }

    /** A model that stringified its JSON ("[{\"old_string\":\"a\"}]") still meant a real array. */
    private fun parseEmbedded(value: JsonElement): JsonElement? {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val trimmed = text.trim()
        if (trimmed.isEmpty() || (trimmed.first() != '[' && trimmed.first() != '{')) return null
        return runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
    }
}

/**
 * Resolves a path argument the way the model meant it, not the way it typed it.
 *
 * The system prompt asks for workspace-relative paths, but models read command output where the
 * same tree is `/workspace/...` (app-managed) or `/sdcard/Kern/...` (real folder) and then echo
 * that absolute form back. Rejecting it as "Invalid path" made the model repeat the identical call
 * until the turn ran out of steps, so an absolute path that points inside this workspace is
 * accepted and rewritten. Anything genuinely outside it still returns null.
 */
internal fun ToolContext.resolvePath(raw: String): String? {
    val text = raw.trim().replace('\\', '/')
    if (text.isEmpty()) return ""
    // Android paths are always rooted; the drive-letter form only matters for JVM tests.
    val absolute = text.startsWith("/") || (text.length > 2 && text[1] == ':' && text[2] == '/')
    if (!absolute) return WorkspacePaths.normalize(text)

    for (prefix in absolutePrefixes()) {
        if (text == prefix) return ""
        if (text.startsWith("$prefix/")) {
            return WorkspacePaths.normalize(text.removePrefix("$prefix/"))
        }
    }
    return null
}

/** Absolute spellings of this workspace's root, longest first so a nested root wins. */
private fun ToolContext.absolutePrefixes(): List<String> {
    val roots = LinkedHashSet<String>()
    fun add(value: String?) {
        val trimmed = value?.trim()?.trimEnd('/')?.replace('\\', '/').orEmpty()
        if (trimmed.isNotEmpty() && trimmed != "/") roots.add(trimmed)
    }
    add(workspace.realPath)
    add(workspace.localPath)
    // Guest spellings the Linux terminal and command output use for the same directory.
    val real = workspace.realPath?.trimEnd('/')
    if (real != null && real.startsWith(ANDROID_SDCARD)) {
        add("/sdcard" + real.removePrefix(ANDROID_SDCARD))
    }
    add("/workspace")
    add(ANDROID_SDCARD + "/workspace")
    return roots.sortedByDescending { it.length }
}

private const val ANDROID_SDCARD = "/storage/emulated/0"
