package com.aliedu.kern.core.workspace

import android.content.Context
import android.net.Uri
import com.aliedu.kern.core.data.repository.WorkspaceRepository
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves where a workspace's commands actually run (PRD 11.1 / 11.4, FR-017).
 *
 * - **App-managed** workspaces already live in a POSIX directory, so the working directory *is* the
 *   workspace root.
 * - **SAF** folders are not POSIX paths, so they are mirrored into `filesDir/execution/<id>`. Commands
 *   run against the mirror; the user reviews a per-file diff and syncs chosen files back explicitly.
 *   Nothing is ever written to the source without that action.
 */
@Singleton
class ExecutionWorkspaceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val storagePermission: StoragePermission
) : ExecutionWorkspaceResolver {

    fun executionRoot(): File = File(context.filesDir, "execution").apply { mkdirs() }

    fun executionDir(workspaceId: String): File = File(executionRoot(), workspaceId)

    /** A neutral working directory for the terminal when no workspace is active. */
    override fun scratchWorkspace(): ExecutionWorkspace {
        val dir = File(context.filesDir, SCRATCH_DIR).apply { mkdirs() }
        return ExecutionWorkspace(SCRATCH_ID, dir.absolutePath)
    }

    /** True once a SAF workspace has been mirrored (app-managed workspaces are always "ready"). */
    suspend fun isPrepared(workspaceId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = workspaceRepository.getWorkspace(workspaceId) ?: return@withContext false
        when (workspace.sourceType) {
            WorkspaceSourceType.APP_MANAGED -> !workspace.localPath.isNullOrBlank()
            WorkspaceSourceType.SELECTED_FOLDER ->
                realPathFor(workspace) != null || executionDir(workspaceId).isDirectory
        }
    }

    /**
     * Return the runnable working directory for [workspaceId], creating the SAF mirror on first use.
     * Re-calling never clobbers local edits — use [refreshFromSource] to re-copy deliberately.
     */
    override suspend fun prepare(workspaceId: String): ExecutionWorkspace = withContext(Dispatchers.IO) {
        val workspace = workspaceRepository.getWorkspace(workspaceId)
            ?: throw WorkspaceAccessException("Workspace not found.")
        when (workspace.sourceType) {
            WorkspaceSourceType.APP_MANAGED -> {
                val path = workspace.localPath
                    ?: throw WorkspaceAccessException("Workspace has no local path.")
                File(path).mkdirs()
                ExecutionWorkspace(workspaceId, path)
            }

            WorkspaceSourceType.SELECTED_FOLDER -> {
                // With all-files access the folder's real path *is* the working directory, so the
                // terminal and the file tools share one tree and no mirror can drift.
                val realPath = realPathFor(workspace)
                if (realPath != null) {
                    File(realPath).mkdirs()
                    return@withContext ExecutionWorkspace(workspaceId, realPath)
                }
                val dir = executionDir(workspaceId)
                if (!dir.isDirectory) {
                    dir.mkdirs()
                    mirrorFor(workspace).copyAll()
                }
                ExecutionWorkspace(workspaceId, dir.absolutePath)
            }
        }
    }

    /**
     * The folder's real path when it is usable (derived, all-files access granted, directory
     * reachable). Null means the workspace runs against the execution mirror instead.
     */
    private fun realPathFor(workspace: Workspace): String? {
        val path = ExternalPathResolver.realPathOf(workspace) ?: return null
        if (!storagePermission.isGranted()) return null
        return path.takeIf { File(it).isDirectory }
    }

    /** Re-copy the whole SAF source over the mirror, discarding local edits (explicit user action). */
    suspend fun refreshFromSource(workspaceId: String): Unit = withContext(Dispatchers.IO) {
        val workspace = requireMirroredWorkspace(workspaceId)
        val dir = executionDir(workspaceId)
        dir.deleteRecursively()
        dir.mkdirs()
        mirrorFor(workspace).copyAll()
    }

    /** Files that differ between the mirror and the SAF source, with a line-level +/- summary. */
    suspend fun listChanges(workspaceId: String): List<WorkspaceChange> = withContext(Dispatchers.IO) {
        val workspace = workspaceRepository.getWorkspace(workspaceId) ?: return@withContext emptyList()
        if (workspace.sourceType != WorkspaceSourceType.SELECTED_FOLDER) return@withContext emptyList()
        if (realPathFor(workspace) != null) return@withContext emptyList() // real path: nothing to review
        mirrorFor(workspace).listChanges()
    }

    /** A unified-style line diff of one path (mirror vs source) for the review UI. */
    suspend fun fileDiff(workspaceId: String, path: String): List<DiffLine> = withContext(Dispatchers.IO) {
        val workspace = workspaceRepository.getWorkspace(workspaceId) ?: return@withContext emptyList()
        if (workspace.sourceType != WorkspaceSourceType.SELECTED_FOLDER) return@withContext emptyList()
        if (realPathFor(workspace) != null) return@withContext emptyList()
        mirrorFor(workspace).fileDiff(path)
    }

    /** Copy the given mirror files back over the SAF source (manual, per-file). */
    suspend fun syncBack(workspaceId: String, paths: List<String>): SyncReport = withContext(Dispatchers.IO) {
        val workspace = requireMirroredWorkspace(workspaceId)
        mirrorFor(workspace).syncBack(paths)
    }

    /** Discard mirror edits for the given paths by restoring them from the source. */
    suspend fun restoreFromSource(workspaceId: String, paths: List<String>): Unit = withContext(Dispatchers.IO) {
        val workspace = requireMirroredWorkspace(workspaceId)
        mirrorFor(workspace).restore(paths)
    }

    /** Remove the whole execution mirror for a workspace (never touches an app-managed workspace). */
    suspend fun discard(workspaceId: String): Unit = withContext(Dispatchers.IO) {
        val workspace = workspaceRepository.getWorkspace(workspaceId)
        if (workspace?.sourceType == WorkspaceSourceType.APP_MANAGED) return@withContext
        executionDir(workspaceId).deleteRecursively()
    }

    // ---- helpers ----------------------------------------------------------

    private fun mirrorFor(workspace: Workspace): ExecutionMirror =
        ExecutionMirror(sourceTree(workspace), LocalExecutionTree(executionDir(workspace.id)))

    private fun sourceTree(workspace: Workspace): ExecutionTree {
        val uri = workspace.uriString?.let(Uri::parse)
            ?: throw WorkspaceAccessException("Workspace has no folder reference.")
        return SafExecutionTree(context, uri)
    }

    /** A folder workspace that is actually running against the mirror (not its real path). */
    private suspend fun requireMirroredWorkspace(workspaceId: String): Workspace {
        val workspace = workspaceRepository.getWorkspace(workspaceId)
            ?: throw WorkspaceAccessException("Workspace not found.")
        if (workspace.sourceType != WorkspaceSourceType.SELECTED_FOLDER) {
            throw WorkspaceAccessException("Only folder workspaces use an execution mirror.")
        }
        if (realPathFor(workspace) != null) {
            throw WorkspaceAccessException("This workspace runs directly on its folder; there is no mirror.")
        }
        return workspace
    }

    private companion object {
        const val SCRATCH_DIR = "terminal-scratch"
        const val SCRATCH_ID = "__scratch__"
    }
}
