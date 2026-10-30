package com.aliedu.kern.core.agent

import com.aliedu.kern.core.tools.DeleteFileTool
import com.aliedu.kern.core.tools.EditFileTool
import com.aliedu.kern.core.tools.FetchUrlTool
import com.aliedu.kern.core.tools.GitCommitTool
import com.aliedu.kern.core.tools.GitDiffTool
import com.aliedu.kern.core.tools.GitStatusTool
import com.aliedu.kern.core.tools.GlobTool
import com.aliedu.kern.core.tools.GrepTool
import com.aliedu.kern.core.tools.ListFilesTool
import com.aliedu.kern.core.tools.ProjectDetector
import com.aliedu.kern.core.tools.ProposeEditTool
import com.aliedu.kern.core.tools.ProposePatchTool
import com.aliedu.kern.core.tools.ProposePlanTool
import com.aliedu.kern.core.tools.RememberTool
import com.aliedu.kern.core.tools.ReadFileTool
import com.aliedu.kern.core.tools.RunBuildTool
import com.aliedu.kern.core.tools.RunCommandTool
import com.aliedu.kern.core.tools.RunLintTool
import com.aliedu.kern.core.tools.RunTestsTool
import com.aliedu.kern.core.tools.SearchFilesTool
import com.aliedu.kern.core.tools.TerminalInspector
import com.aliedu.kern.core.tools.TerminalStatus
import com.aliedu.kern.core.tools.TerminalStatusTool
import com.aliedu.kern.core.tools.ToolRegistry
import com.aliedu.kern.core.tools.UpdateTodosTool
import com.aliedu.kern.core.tools.WriteFileTool
import com.aliedu.kern.core.tools.background.BackgroundJob
import com.aliedu.kern.core.tools.background.BackgroundJobRegistry
import com.aliedu.kern.core.tools.background.BackgroundOutput
import com.aliedu.kern.core.tools.background.BackgroundOutputTool
import com.aliedu.kern.core.tools.background.BackgroundStartTool
import com.aliedu.kern.core.tools.background.BackgroundStatusTool
import com.aliedu.kern.core.tools.background.BackgroundStopTool
import com.aliedu.kern.core.tools.background.BackgroundWaitTool
import com.aliedu.kern.core.tools.devserver.DevServer
import com.aliedu.kern.core.tools.devserver.DevServerRegistry
import com.aliedu.kern.core.tools.devserver.ServerStartTool
import com.aliedu.kern.core.tools.devserver.ServerStopTool
import com.aliedu.kern.core.tools.diff.DiffService
import com.aliedu.kern.core.tools.preview.PreviewConsoleTool
import com.aliedu.kern.core.tools.preview.PreviewController
import com.aliedu.kern.core.tools.preview.PreviewEvalTool
import com.aliedu.kern.core.tools.preview.PreviewOpenTool
import com.aliedu.kern.core.tools.preview.PreviewScreenshotTool
import com.aliedu.kern.core.tools.preview.PreviewStatusTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A registry with no servers, for tests that only need the tool set to be complete. */
class FakeDevServers : DevServerRegistry {
    private val _servers = MutableStateFlow<List<DevServer>>(emptyList())
    override val servers: StateFlow<List<DevServer>> = _servers

    /** Replace the running-server list the way the real manager would. */
    fun setServers(list: List<DevServer>) {
        _servers.value = list
    }

    override suspend fun start(
        command: String,
        port: Int?,
        workspaceId: String,
        cwd: String,
        onOutput: ((String) -> Unit)?
    ): Result<DevServer> = Result.failure(IllegalStateException("no dev servers in this test"))

    override suspend fun stop(id: String): Boolean = false
    override fun isOwned(sessionId: String): Boolean = false
}

/** No jobs in unit tests; the tools only format what the registry reports. */
class FakeBackgroundJobs : BackgroundJobRegistry {
    private val _jobs = MutableStateFlow<List<BackgroundJob>>(emptyList())
    override val jobs: StateFlow<List<BackgroundJob>> = _jobs

    /** Replace the job list the way the real manager would. */
    fun setJobs(list: List<BackgroundJob>) {
        _jobs.value = list
    }

    override suspend fun start(
        command: String,
        workspaceId: String,
        cwd: String,
        maxOutputChars: Int
    ): Result<BackgroundJob> = Result.failure(IllegalStateException("no background jobs in this test"))

    override fun status(id: String): BackgroundJob? = _jobs.value.firstOrNull { it.id == id }

    override fun output(id: String, cursor: Int): BackgroundOutput? {
        val job = status(id) ?: return null
        return BackgroundOutput(job.id, job.state, job.output, job.output.length, false, job.output.length)
    }

    override suspend fun await(id: String, timeoutMs: Long): BackgroundJob? = status(id)

    override suspend fun stop(id: String): Boolean = false

    override fun isOwned(sessionId: String): Boolean = false
}

/** No terminal in unit tests; the tool only formats what the inspector reports. */
private object FakeTerminalInspector : TerminalInspector {
    override suspend fun status(workspaceId: String): TerminalStatus =
        TerminalStatus(
            sessionId = null,
            sessionName = null,
            cwd = null,
            liveSessions = 0,
            backend = "test",
            recent = emptyList()
        )
}

/**
 * The production tool set. Orchestrator tests care that *a* tool resolves by name, not which
 * instance it is, so this keeps every call site in sync with [ToolRegistry]'s constructor.
 */
fun testToolRegistry(diffService: DiffService): ToolRegistry {
    val servers = FakeDevServers()
    val jobs = FakeBackgroundJobs()
    val preview = PreviewController()
    return ToolRegistry(
        ListFilesTool(), ReadFileTool(), SearchFilesTool(), GlobTool(), GrepTool(),
        ProposeEditTool(diffService), EditFileTool(diffService), WriteFileTool(diffService),
        ProposePatchTool(diffService),
        DeleteFileTool(diffService), FetchUrlTool(), UpdateTodosTool(),
        ProposePlanTool(), RememberTool(), RunCommandTool(), TerminalStatusTool(FakeTerminalInspector),
        RunBuildTool(ProjectDetector()), RunTestsTool(ProjectDetector()), RunLintTool(ProjectDetector()),
        GitStatusTool(), GitDiffTool(), GitCommitTool(),
        ServerStartTool(), ServerStopTool(servers),
        PreviewOpenTool(preview, servers), PreviewStatusTool(preview, servers),
        PreviewConsoleTool(preview), PreviewEvalTool(preview), PreviewScreenshotTool(preview),
        BackgroundStartTool(), BackgroundStatusTool(jobs), BackgroundOutputTool(jobs),
        BackgroundWaitTool(jobs), BackgroundStopTool(jobs)
    )
}
