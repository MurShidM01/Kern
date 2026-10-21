package com.aliedu.kern.core.workspace

import android.os.Environment
import java.io.File

/**
 * A [WorkspaceAdapter] over a real filesystem directory (e.g. `/storage/emulated/0/Kern`).
 *
 * The file operations are the same as [AppManagedWorkspaceAdapter] — both work on an ordinary
 * [File] root — so this delegates to it and only adds the extra precondition: all-files access
 * (`MANAGE_EXTERNAL_STORAGE`) must still be granted, since the directory lives outside the app's
 * sandbox. When the grant is missing the manager falls back to the SAF adapter instead.
 */
class RealPathWorkspaceAdapter(
    private val rootDir: File,
    override val workspaceId: String
) : WorkspaceAdapter by AppManagedWorkspaceAdapter(rootDir, workspaceId) {

    override suspend fun isAccessValid(): Boolean =
        Environment.isExternalStorageManager() && rootDir.isDirectory
}
