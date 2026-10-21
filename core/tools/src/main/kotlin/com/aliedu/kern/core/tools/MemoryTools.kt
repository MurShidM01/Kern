package com.aliedu.kern.core.tools

import com.aliedu.kern.core.providers.api.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** One durable fact the agent wants to keep about this workspace. */
data class MemoryNote(
    /** One of [RememberTool.SECTIONS]. */
    val section: String,
    val text: String
)

/**
 * Gives the agent a long-term memory for the workspace. Notes are Markdown bullets appended under a
 * section of `~/.kern/PLANS/<workspace-id>/memory.md`, which is injected into every later turn, so
 * the agent stops re-learning the same conventions, commands and decisions.
 *
 * Writes are performed by the orchestrator (this tool only describes the note), which keeps the
 * tool layer free of file-system dependencies and lets the orchestrator de-duplicate.
 */
@Singleton
class RememberTool @Inject constructor() : Tool {

    override val name = "remember"

    override val description =
        "Save a durable fact about this workspace to memory: build/test commands, conventions, " +
            "architecture decisions, gotchas, or what the user prefers. Keep one fact per call and " +
            "phrase it as a reusable statement. Do not store secrets or one-off task details."

    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "section":{"type":"string","enum":["project","conventions","decisions","gotchas","preferences"],"description":"Which part of memory this belongs to."},
              "note":{"type":"string","description":"The fact, one sentence."}
            },"required":["section","note"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val section = (args["section"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim()?.lowercase().orEmpty()
        val note = (args["note"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim().orEmpty()
        if (note.isEmpty()) {
            return ToolResult(false, "", "A note is required: send 'note' as one sentence.")
        }
        val resolved = if (section in SECTIONS) section else "project"
        return ToolResult(
            success = true,
            output = "Saved to memory under '$resolved'.",
            memoryNote = MemoryNote(resolved, note)
        )
    }

    companion object {
        val SECTIONS = listOf("project", "conventions", "decisions", "gotchas", "preferences")
    }
}
