package com.aliedu.kern.core.tools

import com.aliedu.kern.core.model.Todo
import com.aliedu.kern.core.model.TodoStatus
import com.aliedu.kern.core.providers.api.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_TODOS = 50

/**
 * Lets the agent publish and maintain a task list for multi-step work (FR-018). The model always
 * sends the FULL ordered list; the orchestrator forwards it to the UI via `AgentEvent.TodosUpdated`
 * and the repository replaces the session's list wholesale.
 */
@Singleton
class UpdateTodosTool @Inject constructor() : Tool {
    override val name = "update_todos"
    override val description =
        "Create or update the session task list for multi-step work. Send the FULL list each time; " +
            "each item has 'content' and 'status' (pending | in_progress | completed)."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{"todos":{"type":"array","description":"The full ordered task list.","items":{"type":"object","properties":{"content":{"type":"string","description":"Short imperative description of the step."},"status":{"type":"string","enum":["pending","in_progress","completed"]}},"required":["content","status"]}}},"required":["todos"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val array = args["todos"] as? JsonArray
            ?: return ToolResult(false, "", "Invalid todos payload: 'todos' array is required.")

        val now = System.currentTimeMillis()
        val todos = ArrayList<Todo>()
        for (element in array) {
            if (todos.size >= MAX_TODOS) break
            val obj = element as? JsonObject ?: continue
            val content = (obj["content"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.trim().orEmpty()
            if (content.isEmpty()) continue
            val rawStatus = (obj["status"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content.orEmpty()
            val index = todos.size
            todos.add(
                Todo(
                    id = "${context.sessionId}#$index",
                    sessionId = context.sessionId,
                    content = content,
                    status = parseStatus(rawStatus),
                    order = index,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        if (todos.isEmpty()) {
            return ToolResult(true, "Task list cleared.", todos = emptyList())
        }
        val done = todos.count { it.status == TodoStatus.COMPLETED }
        return ToolResult(
            success = true,
            output = "Updated task list: $done/${todos.size} completed.",
            todos = todos
        )
    }

    private fun parseStatus(raw: String): TodoStatus = when (raw.lowercase()) {
        "in_progress", "in-progress", "active", "doing" -> TodoStatus.IN_PROGRESS
        "completed", "complete", "done" -> TodoStatus.COMPLETED
        else -> TodoStatus.PENDING
    }
}
