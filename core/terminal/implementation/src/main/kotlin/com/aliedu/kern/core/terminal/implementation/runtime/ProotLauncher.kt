package com.aliedu.kern.core.terminal.implementation.runtime

import java.io.File

/**
 * Resolves the proot launcher and the pieces it needs at runtime.
 *
 * Android only extracts `lib*.so` from the APK into the native library dir — the one location where
 * app files remain executable on targetSdk 29+. Kern bundles Termux's proot there (see
 * `:core:terminal:binaries`), so the launcher, its `talloc` dependency and the guest loader all live
 * in the same directory. A prebuilt launcher dropped into the runtime dir is also accepted
 * (older/rooted devices, and the app-storage path the installer still verifies).
 *
 * Pure and platform-independent so the gating can be asserted without an Android [android.content.Context].
 */
object ProotLauncher {

    /** Android only keeps `lib*.so` executable from the APK; a bare `proot` covers side-loaded builds. */
    val NATIVE_NAMES = listOf("libproot.so", "libproot_exec.so")
    const val RUNTIME_NAME = "proot"

    /** The proot guest loader, exec'd inside the rootfs via `PROOT_LOADER`. */
    const val LOADER_NAME = "libproot-loader.so"

    /** proot's talloc dependency, renamed to a `lib*.so` name so Android extracts it. */
    const val TALLOC_NAME = "libtalloc.so"

    /** First executable candidate: native library dir first, then the runtime dir. */
    fun resolve(nativeLibraryDir: File, runtimeDir: File): File? =
        NATIVE_NAMES.map { File(nativeLibraryDir, it) }
            .plus(File(runtimeDir, RUNTIME_NAME))
            .firstOrNull { it.exists() && it.canExecute() }

    /**
     * The loader proot execs inside the guest. Bundled builds always ship it next to the launcher;
     * a side-loaded `proot` may carry its own (proot then falls back to its built-in default).
     */
    fun resolveLoader(nativeLibraryDir: File, runtimeDir: File): File? =
        listOf(File(nativeLibraryDir, LOADER_NAME), File(runtimeDir, LOADER_NAME))
            .firstOrNull { it.isFile }

    /** proot's shared dependencies live in the native library dir; used for `LD_LIBRARY_PATH`. */
    fun resolveTalloc(nativeLibraryDir: File): File? =
        File(nativeLibraryDir, TALLOC_NAME).takeIf { it.isFile }

    /** A session can only run when both a launcher and the extracted rootfs are present. */
    fun isAvailable(nativeLibraryDir: File, runtimeDir: File, rootfs: File): Boolean =
        resolve(nativeLibraryDir, runtimeDir) != null && rootfs.exists()
}
