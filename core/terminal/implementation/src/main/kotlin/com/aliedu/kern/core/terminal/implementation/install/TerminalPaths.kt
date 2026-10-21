package com.aliedu.kern.core.terminal.implementation.install

import java.io.File

/**
 * All installer paths live under `filesDir/terminal`. Uninstall removes only this tree, so Part 1
 * data (database, preferences, workspaces, credentials) is never touched (PRD 12.3 recovery).
 *
 * [nativeLibraryDir] is the app's extracted native library directory — not part of the terminal tree,
 * but the one place Android still lets an app targeting API 29+ `execve`. The bundled proot launcher
 * lives there (see `:core:terminal:binaries`), so both the installer and the runtime read it here
 * instead of each re-deriving it from a [android.content.Context].
 */
class TerminalPaths(
    val root: File,
    val nativeLibraryDir: File
) {

    val rootfs: File get() = File(root, "rootfs")
    val runtime: File get() = File(root, "runtime")
    val stateFile: File get() = File(root, "install.json")
    val logFile: File get() = File(root, "install.log")

    fun staging(version: String): File = File(root, ".staging-$version")

    fun ensureRoot() {
        if (!root.exists()) root.mkdirs()
    }

    /** Remove the residue of any interrupted install (PRD 12.3: CLEAN INCOMPLETE INSTALL). */
    fun cleanStaging(): List<String> {
        val removed = ArrayList<String>()
        root.listFiles { file -> file.name.startsWith(STAGING_PREFIX) }?.forEach { dir ->
            removed.add(dir.name)
            dir.deleteRecursively()
        }
        return removed
    }

    companion object {
        const val STAGING_PREFIX = ".staging-"
    }
}
