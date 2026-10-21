package com.aliedu.kern.core.tools

import com.aliedu.kern.core.providers.api.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A plan the agent wants to execute, written in Markdown and shown to the user before any work
 * starts. Approving it persists the Markdown under `~/.kern/PLANS/<workspace-id>/` and the agent
 * then works from it, so a long task has a reviewed shape instead of improvised steps.
 */
data class PlanProposal(
    val id: String,
    val title: String,
    val markdown: String
)

/**
 * Lets the model propose a plan before touching the workspace. Like a file proposal it has no
 * effect of its own: the orchestrator pauses the turn, the UI asks the user to approve or ask for
 * changes, and only then does the agent start executing.
 */
@Singleton
class ProposePlanTool @Inject constructor() : Tool {

    override val name = "propose_plan"

    override val description =
        "Propose a plan for the task before doing the work. Write it as Markdown: a short goal, the " +
            "steps in order, the files or commands each step touches, and how you will verify the " +
            "result. The user approves it or asks for changes; only then should you execute."

    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "title":{"type":"string","description":"Short title for the plan, e.g. 'Add dark mode'."},
              "plan":{"type":"string","description":"The complete plan as Markdown."}
            },"required":["title","plan"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val title = (args["title"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim().orEmpty()
        val markdown = (args["plan"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim().orEmpty()
        if (markdown.isEmpty()) {
            return ToolResult(false, "", "A plan is required: send 'plan' as Markdown.")
        }
        return ToolResult(
            success = true,
            // Never an empty body: the transcript and the model both need to know the plan was
            // captured and is waiting on the user, not that the call did nothing.
            output = "Proposed the plan \"" + title.ifEmpty { "Plan" } + "\". Awaiting approval.",
            requiresApproval = true,
            plan = PlanProposal(
                id = UUID.randomUUID().toString(),
                title = title.ifEmpty { "Plan" },
                markdown = markdown
            )
        )
    }
}
