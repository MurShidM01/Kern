package com.aliedu.kern.core.terminal.implementation.install

import com.aliedu.kern.core.terminal.implementation.runtime.ProotCommand
import com.aliedu.kern.core.terminal.implementation.runtime.ProotLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of trying to execute the installed runtime on this device. */
data class ProbeResult(val executable: Boolean, val detail: String)

/**
 * Maps a failed probe's output onto a message worth showing the user. Pure so it can be asserted
 * without a device that actually denies `ptrace`.
 */
internal fun describeProbeFailure(output: String, fallback: String): String {
    val text = output.trim()
    return when {
        // proot names the real reason on its own; the exit code adds nothing.
        text.contains("ptrace", ignoreCase = true) || text.contains("TRACEME") ->
            RuntimeCapabilityProbe.SANDBOX_BLOCKED_DETAIL
        text.contains("Permission denied", ignoreCase = true) -> RuntimeCapabilityProbe.EXEC_BLOCKED_DETAIL
        text.isNotEmpty() -> "$fallback: ${text.take(160)}"
        else -> fallback
    }
}

/**
 * Determines whether a runtime binary can actually be executed on this device (plan risk R1).
 *
 * Android 10+ denies `execve` of app-storage files for apps targeting API 29+, but the native library
 * dir remains executable — which is why Kern ships proot as a `lib*.so` there. [probe] runs whatever
 * command line it is given (`libproot.so --version` for the cheap check), so the terminal never
 * claims a runtime it cannot run.
 *
 * [probeLaunch] goes one step further and checks that the *sandbox itself* works. proot is built on
 * `ptrace`, and some kernels deny it to app UIDs; there, `--version` succeeds while every real
 * session dies with `ptrace(TRACEME): Permission denied`. Only a probe that enters the guest catches
 * that, so the terminal can fall back to the built-in shell instead of showing a dead one.
 */
@Singleton
class RuntimeCapabilityProbe @Inject constructor(private val paths: TerminalPaths) {

    /**
     * Runs [argv] and reports whether it exited 0.
     *
     * [argv] is the **complete** command line, launcher included — the same shape [ProotCommand]
     * produces for a session. The launcher must appear exactly once: proot reads any later copy as
     * the guest program to run, and then dies inside the sandbox with `SIGSEGV` instead of a
     * readable error, which looks exactly like a device that cannot run the runtime at all.
     */
    suspend fun probe(
        argv: List<String>,
        env: Map<String, String> = emptyMap()
    ): ProbeResult = withContext(Dispatchers.IO) {
        val binary = File(argv.first())
        if (!binary.exists()) return@withContext ProbeResult(false, "The runtime binary is missing.")

        try {
            val builder = ProcessBuilder(argv).redirectErrorStream(true)
            builder.environment().putAll(env)
            val process = builder.start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext ProbeResult(false, "The runtime did not respond within ${PROBE_TIMEOUT_SECONDS}s.")
            }
            val code = process.exitValue()
            if (code == 0) ProbeResult(true, output.trim().take(200))
            else ProbeResult(false, describeProbeFailure(output, "The runtime exited with code $code"))
        } catch (t: Throwable) {
            ProbeResult(false, describe(t))
        }
    }

    /**
     * Runs a trivial guest command through proot, exactly the way a session launches it. A working
     * device returns 0; a device whose kernel denies the sandbox reports it here rather than in the
     * terminal.
     */
    suspend fun probeLaunch(rootfs: File): ProbeResult {
        if (!rootfs.isDirectory) return ProbeResult(false, "The Alpine rootfs is missing.")
        val launcher = ProotLauncher.resolve(paths.nativeLibraryDir, paths.runtime)
            ?: return ProbeResult(false, "The proot launcher is unavailable on this device.")

        val env = ProotCommand.environment(
            home = "/root",
            tmpDir = File(paths.root, PROBE_TMP_DIR).apply { mkdirs() }.absolutePath,
            nativeLibraryDir = paths.nativeLibraryDir.absolutePath,
            guestCwd = "/",
            loaderPath = ProotLauncher.resolveLoader(paths.nativeLibraryDir, paths.runtime)?.absolutePath
        )
        return probe(launchArgv(launcher, rootfs), env)
    }

    /**
     * The complete command line for the launch self-test.
     *
     * Kept separate from [probe] so the one property that must hold — the launcher appearing exactly
     * once, as `argv[0]` — can be asserted without a device that has a bundled proot.
     */
    internal fun launchArgv(launcher: File, rootfs: File): List<String> =
        ProotCommand.probe(launcher.absolutePath, rootfs.absolutePath)

    /** Maps the failure to start the process at all onto a user-facing reason. */
    private fun describe(t: Throwable): String {
        val message = t.message ?: t::class.java.simpleName
        val blocked = message.contains("Permission denied", ignoreCase = true) ||
            message.contains("error=13") ||
            message.contains("EACCES")
        return if (blocked) EXEC_BLOCKED_DETAIL else message
    }

    companion object {
        const val PROBE_TIMEOUT_SECONDS = 10L
        const val EXEC_BLOCKED_DETAIL =
            "Android blocks executing binaries from app storage (exec restriction for apps targeting " +
                "API 29+), so the built-in shell is used instead."
        const val SANDBOX_BLOCKED_DETAIL =
            "This device's kernel denies the proot sandbox (ptrace is blocked for apps), so the " +
                "built-in shell is used instead."

        private const val PROBE_TMP_DIR = "tmp"
    }
}
