package com.aliedu.kern.core.workspace

import android.os.Build
import android.os.Environment
import java.io.File

/**
 * A [WorkspaceAdapter] over a real filesystem directory (e.g. `/storage/emulated/0/Kern`).
 *
 * The file operations are the same as [AppManagedWorkspaceAdapter] — both work on an ordinary
 * [File] root — so this delegates to it and only adds the extra precondition: all-files access
 * (`MANAGE_EXTERNAL_STORAGE`) must still be granted, since the directory lives outside the app's
 * sandbox. When the grant is missing the manager falls back to the SAF adapter instead.
 *
 * All-files access arrived in Android 11 (API 30). Below that the platform call does not exist, so
 * the adapter reports the access as invalid and the SAF adapter is used instead.
 */
class RealPathWorkspaceAdapter(
    private val rootDir: File,
    override val workspaceId: String
) : WorkspaceAdapter by AppManagedWorkspaceAdapter(rootDir, workspaceId) {

    override suspend fun isAccessValid(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager() &&
            rootDir.isDirectory
}
