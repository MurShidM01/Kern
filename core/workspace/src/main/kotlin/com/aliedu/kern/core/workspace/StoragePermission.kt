package com.aliedu.kern.core.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gate for all-files access (`MANAGE_EXTERNAL_STORAGE`), which the unified `/sdcard` workspace model
 * needs: without it Android's scoped storage hides `/storage/emulated/0/<folder>` from direct file
 * I/O. The permission is special — it is granted from a system settings screen rather than a runtime
 * dialog — so this exposes the settings [Intent] the UI must launch.
 *
 * When it is not granted, workspaces still work through the SAF adapter / execution mirror; only the
 * shared real-path behaviour is unavailable.
 */
@Singleton
class StoragePermission @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun isGranted(): Boolean = Environment.isExternalStorageManager()

    /** Opens the per-app "All files access" screen; falls back to the global list on some OEMs. */
    fun requestIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.fromParts("package", context.packageName, null))

    /** The global "All files access" list, used when the per-app screen is unavailable. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}
