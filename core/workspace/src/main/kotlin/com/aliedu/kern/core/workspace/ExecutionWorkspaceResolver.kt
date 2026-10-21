package com.aliedu.kern.core.workspace

import com.aliedu.kern.core.terminal.api.ExecutionWorkspace

/**
 * Resolves the directory a workspace's shell commands run in. Implemented by [ExecutionWorkspaceManager]
 * and depended on by UI/agent layers that only need the working directory, not mirror management.
 */
interface ExecutionWorkspaceResolver {
    /** The runnable working directory for [workspaceId] (creating the SAF mirror on first use). */
    suspend fun prepare(workspaceId: String): ExecutionWorkspace

    /** A neutral working directory for when no workspace is active. */
    fun scratchWorkspace(): ExecutionWorkspace
}
