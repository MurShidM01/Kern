package com.aliedu.kern.core.agent

import com.aliedu.kern.core.tools.CommandExecutor
import com.aliedu.kern.core.tools.CommandRequest
import com.aliedu.kern.core.tools.CommandResult

/** Records command executions and returns a scripted result, so the orchestrator can be tested. */
class TestCommandExecutor(
    var result: CommandResult = CommandResult(exitCode = 0, output = "ok"),
    /** Chunks handed to [onOutput] while the command "runs", to exercise live streaming. */
    private val chunks: List<String> = emptyList()
) : CommandExecutor {
    val ran = mutableListOf<CommandRequest>()

    override suspend fun run(
        request: CommandRequest,
        onOutput: ((String) -> Unit)?
    ): CommandResult {
        ran.add(request)
        chunks.forEach { onOutput?.invoke(it) }
        return result
    }
}
