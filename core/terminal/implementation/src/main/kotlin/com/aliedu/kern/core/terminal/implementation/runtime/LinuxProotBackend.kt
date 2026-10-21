package com.aliedu.kern.core.terminal.implementation.runtime

import android.content.Context
import android.net.ConnectivityManager
import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.SessionKind
import com.aliedu.kern.core.terminal.implementation.install.TerminalInstallStore
import com.aliedu.kern.core.terminal.implementation.install.TerminalPaths
import com.aliedu.kern.core.terminal.pty.Pty
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs sessions inside the installed Linux rootfs (Alpine, Ubuntu, Debian, …) via the bundled
 * proot (PRD 12.2).
 *
 * proot is shipped as `lib*.so` (see `:core:terminal:binaries`) so it lives in the app's native
 * library dir — the one location Android still allows an app targeting API 29+ to `execve`. The
 * guest is started on a real PTY and the workspace is exposed at the same path the rest of the app
 * uses (`/sdcard/<folder>`), so Files/Editor/Agent and the terminal see one shared tree.
 */
@Singleton
class LinuxProotBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val paths: TerminalPaths,
    private val store: TerminalInstallStore
) : SessionBackend {

    override val kind: SessionKind = SessionKind.LINUX_PROOT

    private val nativeLibraryDir: File get() = paths.nativeLibraryDir

    private val launcher: File?
        get() = ProotLauncher.resolve(nativeLibraryDir, paths.runtime)

    override fun isAvailable(): Boolean =
        ProotLauncher.isAvailable(nativeLibraryDir, paths.runtime, paths.rootfs)

    override suspend fun start(
        workspace: ExecutionWorkspace,
        displayName: String,
        scope: CoroutineScope
    ): ShellSession = withContext(Dispatchers.IO) {
        val proot = launcher ?: error("The proot launcher is not available on this device.")
        val dir = File(workspace.workingDirectory)
        if (!dir.exists()) dir.mkdirs()

        prepareGuest()

        val guestCwd = ProotCommand.guestPathFor(dir.absolutePath)
        val command = ProotCommand.build(
            launcher = proot.absolutePath,
            rootfs = paths.rootfs.absolutePath,
            guestCwd = guestCwd,
            bindings = ProotCommand.bindings(dir.absolutePath, resolvConf = resolvConfPath()),
            shell = loginShell()
        )
        val env = ProotCommand.environment(
            home = "/root",
            tmpDir = File(paths.root, TMP_DIR).apply { mkdirs() }.absolutePath,
            nativeLibraryDir = nativeLibraryDir.absolutePath,
            guestCwd = guestCwd,
            loaderPath = ProotLauncher.resolveLoader(nativeLibraryDir, paths.runtime)?.absolutePath
        )

        val pidOut = IntArray(1)
        val fd = Pty.createSubprocess(
            argv = command.toTypedArray(),
            cwd = dir.absolutePath,
            env = env.map { (key, value) -> "$key=$value" }.toTypedArray(),
            rows = DEFAULT_ROWS,
            cols = DEFAULT_COLS,
            pidOut = pidOut
        )
        if (fd < 0) error("Could not start the Linux session.")

        ShellSession(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            cwd = dir.absolutePath,
            kind = kind,
            process = PtyProcess(fd, pidOut[0]),
            scope = scope
        )
    }

    /**
     * Makes the guest runnable: a prompt pinned to `$ ` (a distro's `/etc/profile` otherwise prints
     * `\h:\w\$ `) and a mount point for the DNS configuration file that is bound over it.
     */
    private fun prepareGuest() {
        val etc = File(paths.rootfs, "etc")
        if (!etc.isDirectory) return

        val profileDir = File(etc, "profile.d").apply { mkdirs() }
        val promptScript = File(profileDir, PROMPT_SCRIPT)
        if (!promptScript.exists()) promptScript.writeText("export PS1='$ '\n")

        val resolv = File(etc, "resolv.conf")
        if (!resolv.exists()) runCatching { resolv.createNewFile() }
    }

    /**
     * Writes a resolver configuration for the guest from the device's current DNS servers, falling
     * back to public resolvers when the network reports none. Returns the host path to bind at
     * `/etc/resolv.conf`, or null if it could not be written.
     */
    /**
     * The guest login shell recorded at install time. Debian-family images ship bash; Alpine and
     * Void use `/bin/sh`. Falls back to `/bin/sh` for a record written before shells were catalogued.
     */
    private fun loginShell(): String =
        store.read()?.shell?.takeIf { it.isNotBlank() } ?: DEFAULT_SHELL

    private fun resolvConfPath(): String? {
        val target = File(paths.root, RESOLV_CONF)
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(ProotCommand.resolvConf(dnsServers()))
            target.absolutePath
        }.getOrNull()
    }

    private fun dnsServers(): List<String> {
        val reported = runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = manager?.activeNetwork ?: return@runCatching emptyList()
            manager.getLinkProperties(network)?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        }.getOrDefault(emptyList())
        return reported.ifEmpty { FALLBACK_DNS }
    }

    private companion object {
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80
        const val TMP_DIR = "tmp"
        const val RESOLV_CONF = "resolv.conf"
        const val PROMPT_SCRIPT = "00-kern-prompt.sh"
        const val DEFAULT_SHELL = "/bin/sh"
        val FALLBACK_DNS = listOf("8.8.8.8", "1.1.1.1")
    }
}
