package com.aliedu.kern.feature.agent

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.ui.graphics.vector.ImageVector
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.model.Message
import com.aliedu.kern.core.model.ToolEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** One row of the agent transcript: an assistant/user message or a tool activity. */
sealed interface TimelineItem {
    val key: String

    data class Msg(val message: Message) : TimelineItem {
        override val key: String get() = "m:${message.id}"
    }

    data class Tool(val event: ToolEvent) : TimelineItem {
        override val key: String get() = "t:${event.id}"
    }
}

private val timelineJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Interleaves messages and the tool calls they triggered so tool activity appears inline, where the
 * agent used it. Tools are anchored to their step via [ToolEvent.messageId]; orphan tools (no anchor)
 * render after all messages.
 */
fun buildTimeline(messages: List<Message>, toolEvents: List<ToolEvent>): List<TimelineItem> {
    val byMessage = toolEvents.filter { it.messageId != null }.groupBy { it.messageId }
    val orphans = toolEvents.filter { it.messageId == null }.sortedBy { it.createdAt }
    val items = ArrayList<TimelineItem>(messages.size + toolEvents.size)
    messages.sortedBy { it.seq }.forEach { message ->
        if (message.content.isNotBlank() || !message.reasoning.isNullOrBlank()) {
            items.add(TimelineItem.Msg(message))
        }
        byMessage[message.id]?.sortedBy { it.createdAt }?.forEach { items.add(TimelineItem.Tool(it)) }
    }
    orphans.forEach { items.add(TimelineItem.Tool(it)) }
    return items
}

/** Human-friendly status line for a tool invocation, e.g. "Reading file src/App.kt". */
fun toolStatusLabel(event: ToolEvent): String {
    val args = runCatching { timelineJson.parseToJsonElement(event.argsRedacted).jsonObject }.getOrNull()
    fun str(key: String): String? =
        (args?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    val path = str("path").orEmpty()
    val command = str("command").orEmpty()
    return when (event.toolName) {
        "read_file" -> if (path.isBlank()) "Reading a file" else "Reading $path"
        "list_files" -> if (path.isBlank()) "Opening the workspace" else "Opening $path"
        // Patterns are not echoed in the label: "Finding **/*.kt" reads like an unfilled
        // placeholder. The exact arguments are one tap away in the expanded row.
        "search_files" -> "Searching files"
        "glob" -> "Finding files"
        "grep" -> "Searching file contents"
        "propose_edit" -> if (path.isBlank()) "Writing a file" else "Writing $path"
        "write_file" -> if (path.isBlank()) "Creating a file" else "Creating $path"
        "edit_file" -> if (path.isBlank()) "Editing a file" else "Editing $path"
        "propose_patch" -> "Changing several files"
        "delete_file" -> if (path.isBlank()) "Deleting a file" else "Deleting $path"
        "fetch_url" -> str("url")?.let { "Fetching $it" } ?: "Fetching a page"
        "run_command" -> if (command.isBlank()) "Running a command" else "Running $command"
        "terminal_status" -> "Checking the terminal"
        "run_build" -> "Building the project"
        "run_tests" -> "Running tests"
        "run_lint" -> "Linting"
        "git_status" -> "Checking git status"
        "git_diff" -> "Reading the diff"
        "git_commit" -> "Committing"
        "server_start" -> "Starting a server"
        "server_stop" -> "Stopping a server"
        "preview_open" -> "Opening the preview"
        "preview_console" -> "Reading the console"
        "preview_eval" -> "Running JavaScript in the page"
        "preview_screenshot" -> "Screenshotting the page"
        "propose_plan" -> "Planning the task"
        "remember" -> "Saving to memory"
        "update_todos" -> "Updating the task list"
        else -> event.toolName.replace('_', ' ')
    }
}

/** Icon paired with each tool status (PRD 6.3 forbids colour-only status). */
fun toolIcon(toolName: String): ImageVector = when (toolName) {
    "read_file" -> KernIcons.File
    "list_files" -> KernIcons.Folder
    "search_files", "glob", "grep" -> KernIcons.Search
    "propose_edit", "write_file", "edit_file", "propose_patch" -> KernIcons.Diff
    "delete_file" -> KernIcons.Delete
    "update_todos" -> KernIcons.Tasks
    "propose_plan" -> KernIcons.Tasks
    "remember" -> KernIcons.Reasoning
    "run_command", "terminal_status" -> KernIcons.Terminal
    "run_build", "run_tests", "run_lint" -> KernIcons.Tools
    "git_status", "git_diff", "git_commit" -> KernIcons.Changes
    "server_start", "server_stop" -> KernIcons.Globe
    "fetch_url", "preview_open", "preview_console", "preview_eval", "preview_screenshot" -> KernIcons.Globe
    else -> Icons.Outlined.Build
}
