package com.aliedu.kern.core.terminal.implementation.runtime

/**
 * Builds the proot argv/environment. Pure so it can be asserted without a real proot binary.
 *
 * The shape follows the reference Android implementation (`proot-distro`): fake root, the guest
 * pseudo-filesystems bound in, and the primary external storage exposed at `/sdcard` so a workspace
 * picked from `emulated/0` is reachable inside the guest at the exact path Files/Editor/Agent use.
 */
object ProotCommand {

    const val SDCARD_HOST = "/storage/emulated/0"
    const val SDCARD_GUEST = "/sdcard"

    /** Where a workspace that is not under primary external storage is bound. */
    const val FALLBACK_GUEST = "/workspace"

    /** Android pseudo-filesystems every session needs. */
    val SYSTEM_BINDINGS = listOf("/dev", "/proc", "/sys", "/system")

    /**
     * @param guestCwd the **guest** working directory (see [guestPathFor]).
     * @param bindings `host` or `host:guest` entries (see [bindings]).
     */
    fun build(
        launcher: String,
        rootfs: String,
        guestCwd: String,
        bindings: List<String>,
        shell: String = "/bin/sh"
    ): List<String> {
        val argv = mutableListOf(
            launcher,
            // Kill the guest when proot exits so no orphaned processes survive the session.
            "--kill-on-exit",
            // Termux's hard-link emulation; apt/dpkg hard-link from the guest rootfs on Android storage.
            "--link2symlink",
            "-r", rootfs,
            "-0",
            "-w", guestCwd
        )
        bindings.forEach { argv += listOf("-b", it) }
        argv += listOf(shell, "-l")
        return argv
    }

    /**
     * The argv for the **launch self-test**: a trivial guest command run through the same proot shape
     * a session uses.
     *
     * `proot --version` prints a version and exits 0 even when the sandbox cannot start at all — for
     * example when the kernel denies `ptrace` to app UIDs, which is what actually backs proot. The
     * probe therefore has to *enter* the guest, so a device that cannot run the runtime is detected
     * before the terminal commits to Alpine.
     */
    fun probe(launcher: String, rootfs: String): List<String> = listOf(
        launcher,
        "--kill-on-exit",
        "--link2symlink",
        "-r", rootfs,
        "-0",
        "-w", "/",
        PROBE_COMMAND
    )

    /**
     * Host/guest bindings: the Android pseudo-filesystems, primary external storage at `/sdcard`,
     * the workspace itself when it lives outside external storage, and a DNS configuration file.
     */
    fun bindings(
        hostDir: String,
        resolvConf: String? = null,
        sdcardHost: String = SDCARD_HOST,
        projectsRoot: String? = null
    ): List<String> {
        val list = SYSTEM_BINDINGS.toMutableList()
        list += "$sdcardHost:$SDCARD_GUEST"
        val guest = guestPathFor(hostDir, sdcardHost, projectsRoot)
        when {
            // A project inside the projects root: bind the *root*, not the project, so the guest
            // sees every project at /workspace and the current one at /workspace/<name>.
            projectsRoot != null && guest.startsWith(FALLBACK_GUEST + "/") ->
                list += projectsRoot.trimEnd('/') + ":" + FALLBACK_GUEST
            guest == FALLBACK_GUEST -> list += "$hostDir:$FALLBACK_GUEST"
        }
        if (resolvConf != null) list += "$resolvConf:/etc/resolv.conf"
        return list
    }

    /**
     * The guest path for a host workspace directory. A directory under primary external storage
     * appears at `/sdcard/<relative>`; anything else is bound at [FALLBACK_GUEST].
     *
     * When [projectsRoot] is given and the directory is inside it, the path is relative to that
     * root instead: the root is bound at [FALLBACK_GUEST], so `first_app` under it is
     * `/workspace/first_app` and `ls /workspace` lists every project.
     */
    fun guestPathFor(
        hostDir: String,
        sdcardHost: String = SDCARD_HOST,
        projectsRoot: String? = null
    ): String {
        val normalized = hostDir.trimEnd('/')
        if (projectsRoot != null) {
            val root = projectsRoot.trimEnd('/')
            if (normalized == root) return FALLBACK_GUEST
            if (normalized.startsWith("$root/")) return FALLBACK_GUEST + normalized.removePrefix(root)
        }
        return when {
            normalized == sdcardHost -> SDCARD_GUEST
            normalized.startsWith("$sdcardHost/") -> SDCARD_GUEST + normalized.removePrefix(sdcardHost)
            else -> FALLBACK_GUEST
        }
    }

    /**
     * The guest environment. `LD_LIBRARY_PATH` points at the native library dir so proot's own
     * dependencies (`libtalloc.so`, `libandroid-shmem.so`) resolve from where Android extracted them.
     */
    fun environment(
        home: String,
        tmpDir: String,
        nativeLibraryDir: String,
        guestCwd: String,
        loaderPath: String? = null
    ): Map<String, String> = buildMap {
        put("HOME", home)
        put("PWD", guestCwd)
        put("TERM", "xterm-256color")
        put("LANG", "en_US.UTF-8")
        put("PATH", DEFAULT_PATH)
        put("PS1", PROMPT)
        put("PROOT_TMP_DIR", tmpDir)
        // proot's seccomp acceleration is unavailable on Android; the ptrace path is used instead.
        put("PROOT_NO_SECCOMP", "1")
        put("LD_LIBRARY_PATH", nativeLibraryDir)
        if (loaderPath != null) put("PROOT_LOADER", loaderPath)
    }

    /**
     * A resolver configuration for the guest, built from the device's DNS servers (rootfs images ship
     * none, and Android exposes DNS per network rather than through `/etc/resolv.conf`).
     */
    fun resolvConf(nameservers: List<String>): String = buildString {
        append("# Generated by Kern for the Linux guest.\n")
        nameservers.forEach { append("nameserver ").append(it).append('\n') }
    }

    private const val PROMPT = "$ "
    private const val DEFAULT_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** A trivial binary every rootfs ships, with no output and a zero exit status. */
    private const val PROBE_COMMAND = "/bin/true"
}
