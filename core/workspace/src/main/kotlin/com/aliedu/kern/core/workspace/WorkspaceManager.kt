package com.aliedu.kern.core.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aliedu.kern.core.data.preferences.SettingsRepository
import com.aliedu.kern.core.data.repository.WorkspaceRepository
import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates, resolves, and validates workspaces (PRD 11.2). Persists SAF grants when the provider
 * supports it, re-validates access at use time, and never silently falls back to another folder.
 */
@Singleton
class WorkspaceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val settingsRepository: SettingsRepository,
    private val storagePermission: StoragePermission
) {

    fun appManagedRoot(): File = File(context.filesDir, "workspaces").apply { mkdirs() }

    suspend fun openSafWorkspace(treeUri: Uri, displayName: String): Workspace =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val now = System.currentTimeMillis()
            val workspace = Workspace(
                id = UUID.randomUUID().toString(),
                displayName = displayName.ifBlank { "Workspace" },
                sourceType = WorkspaceSourceType.SELECTED_FOLDER,
                uriString = treeUri.toString(),
                localPath = null,
                grantState = GrantState.VALID,
                lastOpenedAt = now,
                createdAt = now,
                // Derived regardless of the grant so it is already known once access is granted.
                realPath = ExternalPathResolver.resolve(treeUri)
            )
            workspaceRepository.upsert(workspace)
            workspaceRepository.touch(workspace.id, now)
            settingsRepository.setLastWorkspaceId(workspace.id)
            workspace
        }

    suspend fun createAppManagedWorkspace(name: String): Workspace = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(appManagedRoot(), id).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val workspace = Workspace(
            id = id,
            displayName = name.ifBlank { "New workspace" },
            sourceType = WorkspaceSourceType.APP_MANAGED,
            uriString = null,
            localPath = dir.absolutePath,
            grantState = GrantState.NOT_APPLICABLE,
            lastOpenedAt = now,
            createdAt = now
        )
        workspaceRepository.upsert(workspace)
        settingsRepository.setLastWorkspaceId(workspace.id)
        workspace
    }

    suspend fun adapterFor(workspace: Workspace): WorkspaceAdapter = when (workspace.sourceType) {
        WorkspaceSourceType.SELECTED_FOLDER -> {
            val realPath = usableRealPath(workspace)
            if (realPath != null) {
                RealPathWorkspaceAdapter(File(realPath), workspace.id)
            } else {
                val uri = workspace.uriString?.let(Uri::parse)
                    ?: throw WorkspaceAccessException("Workspace has no folder reference.")
                SafWorkspaceAdapter(context, workspace.id, uri)
            }
        }

        WorkspaceSourceType.APP_MANAGED -> {
            val path = workspace.localPath
                ?: throw WorkspaceAccessException("Workspace has no local path.")
            AppManagedWorkspaceAdapter(File(path), workspace.id)
        }
    }

    /**
     * The workspace's real directory when it can actually be used: a path was derived, all-files
     * access is granted, and the directory is reachable. Otherwise the caller uses the SAF fallback.
     */
    fun usableRealPath(workspace: Workspace): String? {
        val path = ExternalPathResolver.realPathOf(workspace) ?: return null
        if (!storagePermission.isGranted()) return null
        return path.takeIf { File(it).isDirectory }
    }

    /** Re-checks a persisted SAF grant and updates the stored state (PRD 11.2). */
    suspend fun refreshGrantState(workspace: Workspace): GrantState = withContext(Dispatchers.IO) {
        if (workspace.sourceType == WorkspaceSourceType.APP_MANAGED) {
            val state = if (File(workspace.localPath.orEmpty()).exists()) {
                GrantState.NOT_APPLICABLE
            } else {
                GrantState.REVOKED
            }
            workspaceRepository.updateGrantState(workspace.id, state)
            return@withContext state
        }

        val uri = workspace.uriString?.let(Uri::parse)
        val valid = uri != null && (
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } ||
                runCatching { context.contentResolver.openInputStream(uri)?.close(); true }.getOrDefault(false)
            )
        val state = if (valid) GrantState.VALID else GrantState.REVOKED
        workspaceRepository.updateGrantState(workspace.id, state)
        state
    }

    suspend fun deleteWorkspace(workspace: Workspace) = withContext(Dispatchers.IO) {
        if (workspace.sourceType == WorkspaceSourceType.APP_MANAGED) {
            workspace.localPath?.let { runCatching { File(it).deleteRecursively() } }
        } else {
            workspace.uriString?.let { uriStr ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(uriStr),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            }
        }
        workspaceRepository.delete(workspace.id)
    }

    suspend fun setActive(workspaceId: String) {
        workspaceRepository.touch(workspaceId, System.currentTimeMillis())
        settingsRepository.setLastWorkspaceId(workspaceId)
    }
}
