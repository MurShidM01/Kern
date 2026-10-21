package com.aliedu.kern.core.tools

import com.aliedu.kern.core.providers.api.ToolSchema
import com.aliedu.kern.core.tools.devserver.ServerStartTool
import com.aliedu.kern.core.tools.devserver.ServerStopTool
import com.aliedu.kern.core.tools.preview.PreviewConsoleTool
import com.aliedu.kern.core.tools.preview.PreviewEvalTool
import com.aliedu.kern.core.tools.preview.PreviewOpenTool
import com.aliedu.kern.core.tools.preview.PreviewScreenshotTool
import com.aliedu.kern.core.tools.preview.PreviewStatusTool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry of agent tools. Only non-mutating tools (and propose_edit, which produces a proposal
 * but does not write) are exposed to the model; applying is orchestrator-driven after approval
 * (PRD 9.2, 9.3).
 */
@Singleton
class ToolRegistry @Inject constructor(
    listFilesTool: ListFilesTool,
    readFileTool: ReadFileTool,
    searchFilesTool: SearchFilesTool,
    globTool: GlobTool,
    grepTool: GrepTool,
    proposeEditTool: ProposeEditTool,
    editFileTool: EditFileTool,
    writeFileTool: WriteFileTool,
    proposePatchTool: ProposePatchTool,
    deleteFileTool: DeleteFileTool,
    fetchUrlTool: FetchUrlTool,
    updateTodosTool: UpdateTodosTool,
    proposePlanTool: ProposePlanTool,
    rememberTool: RememberTool,
    runCommandTool: RunCommandTool,
    terminalStatusTool: TerminalStatusTool,
    runBuildTool: RunBuildTool,
    runTestsTool: RunTestsTool,
    runLintTool: RunLintTool,
    gitStatusTool: GitStatusTool,
    gitDiffTool: GitDiffTool,
    gitCommitTool: GitCommitTool,
    serverStartTool: ServerStartTool,
    serverStopTool: ServerStopTool,
    previewOpenTool: PreviewOpenTool,
    previewStatusTool: PreviewStatusTool,
    previewConsoleTool: PreviewConsoleTool,
    previewEvalTool: PreviewEvalTool,
    previewScreenshotTool: PreviewScreenshotTool
) {
    private val tools: Map<String, Tool> =
        listOf(
            listFilesTool,
            readFileTool,
            searchFilesTool,
            globTool,
            grepTool,
            proposeEditTool,
            editFileTool,
            writeFileTool,
            proposePatchTool,
            deleteFileTool,
            fetchUrlTool,
            updateTodosTool,
            proposePlanTool,
            rememberTool,
            runCommandTool,
            terminalStatusTool,
            runBuildTool,
            runTestsTool,
            runLintTool,
            gitStatusTool,
            gitDiffTool,
            gitCommitTool,
            serverStartTool,
            serverStopTool,
            previewOpenTool,
            previewStatusTool,
            previewConsoleTool,
            previewEvalTool,
            previewScreenshotTool
        ).associateBy { it.name }

    fun all(): List<Tool> = tools.values.toList()

    fun schemas(): List<ToolSchema> = tools.values.map { it.schema() }

    fun get(name: String): Tool? = tools[name]

    fun isKnown(name: String): Boolean = tools.containsKey(name)
}
