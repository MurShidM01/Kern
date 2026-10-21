package com.aliedu.kern.core.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
 *
 * All-files access arrived in Android 11 (API 30). On API 28 and 29 the permission does not exist
 * and the platform call would throw, so this reports "not granted" there and the app uses the SAF
 * adapter and the execution mirror instead.
 */
@Singleton
class StoragePermission @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** True when the platform supports all-files access at all, which is API 30 and up. */
    val isSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun isGranted(): Boolean = isSupported && Environment.isExternalStorageManager()

    /** Opens the per-app "All files access" screen; falls back to the global list on some OEMs. */
    fun requestIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.fromParts("package", context.packageName, null))

    /** The global "All files access" list, used when the per-app screen is unavailable. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}
