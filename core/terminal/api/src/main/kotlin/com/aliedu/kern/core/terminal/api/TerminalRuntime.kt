package com.aliedu.kern.core.terminal.api

import kotlinx.coroutines.flow.Flow

/**
 * Terminal runtime contract (PRD 12.2). Kern UI and AI tools depend on this interface, never on a
 * concrete terminal engine. Part 1 shipped only a truthful "not installed" implementation; Part 2
 * binds a layered runtime (device shell immediately, Alpine once installed).
 */

enum class EnvironmentState { NOT_INSTALLED, INSTALLING, READY, FAILED, UNSUPPORTED_ABI }

data class EnvironmentInfo(
    val state: EnvironmentState,
    val distroId: String? = null,
    /** Human-readable distribution name (e.g. "Ubuntu"), for screens and messages. */
    val distroName: String? = null,
    val version: String? = null,
    val installPath: String? = null,
    val detail: String? = null,
    /** The device's primary ABI (e.g. arm64-v8a); shown by the setup screen. */
    val abi: String? = null,
    /** Measured on-disk size of an installed environment, when known (PRD 12.4). */
    val installedSizeBytes: Long? = null,
    /** Free space on the install volume, when known (PRD 12.4). */
    val freeBytes: Long? = null,
    /** Space the bundle needs (installed size + safety margin), when known (PRD 12.4). */
    val requiredBytes: Long? = null,
    /**
     * Whether the installed runtime can actually launch on this device. False when the environment
     * is installed but the sandbox cannot start (e.g. the kernel denies `ptrace` to app UIDs), in
     * which case sessions fall back to the built-in shell. Meaningless unless [state] is READY.
     */
    val runtimeUsable: Boolean = true
)

data class InstallProgress(
    val phase: String,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val fraction: Float? = null,
    /** Current transfer speed, when known (FR-015). */
    val bytesPerSecond: Long? = null,
    /** The component currently being fetched/extracted (FR-015). */
    val currentComponent: String? = null
)

sealed interface InstallEvent {
    data class Progress(val progress: InstallProgress) : InstallEvent
    data class Completed(val info: EnvironmentInfo) : InstallEvent
    data class Failed(val message: String) : InstallEvent
}

/**
 * One Linux distribution the user can install, as offered by the environment setup screen.
 *
 * The catalog is data (an asset manifest), not code, so a release can add or re-point a distribution
 * without touching the installer (PRD 12.4).
 */
data class DistroOption(
    val id: String,
    val name: String,
    val version: String,
    val summary: String,
    /** Compressed download size for this device's ABI, when known. */
    val downloadBytes: Long? = null,
    /** Space the extracted rootfs occupies, when known. */
    val installedBytes: Long? = null,
    /** False when the catalog has no bundle for this device's ABI. */
    val supported: Boolean = true,
    /** True when this is the distribution currently installed on the device. */
    val installed: Boolean = false
)

data class ExecutionWorkspace(val workspaceId: String, val workingDirectory: String)

/**
 * Which backend is backing a session. DEVICE_SHELL = Android /system/bin/sh; LINUX_PROOT = a
 * downloaded Linux rootfs (Alpine, Ubuntu, Debian, …) running under the bundled proot.
 */
enum class SessionKind { DEVICE_SHELL, LINUX_PROOT }

data class TerminalSession(
    val id: String,
    val displayName: String,
    val cwd: String,
    val kind: SessionKind = SessionKind.DEVICE_SHELL
)

sealed interface TerminalOutput {
    data class Stdout(val text: String) : TerminalOutput
    data class Stderr(val text: String) : TerminalOutput
    data class Exited(val exitCode: Int) : TerminalOutput
}

interface TerminalRuntime {
    suspend fun inspectInstallState(): EnvironmentInfo
    /**
     * Downloads and installs a Linux rootfs. [distroId] selects the distribution from the catalog;
     * null means the catalog's default. Installing a different distribution than the one already
     * present replaces it, so the caller must confirm that with the user first.
     */
    fun installOrRepair(
        distroId: String? = null,
        onProgress: (InstallProgress) -> Unit
    ): Flow<InstallEvent>

    /** The distributions the setup screen can offer, with per-device support and install state. */
    suspend fun availableDistros(): List<DistroOption> = emptyList()
    suspend fun createSession(workspace: ExecutionWorkspace): TerminalSession
    fun sendInput(sessionId: String, input: ByteArray)
    fun observeOutput(sessionId: String): Flow<TerminalOutput>
    suspend fun requestStop(sessionId: String)
    suspend fun closeSession(sessionId: String)
    suspend fun uninstallEnvironment(): Result<Unit>

    /** Sessions currently held by the runtime (FR-016). Defaults keep Part 1 placeholders compiling. */
    suspend fun listSessions(): List<TerminalSession> = emptyList()

    /**
     * A live session whose working directory matches, if any (used to reuse a shell for commands).
     *
     * [kind] restricts the match to a backend. Callers that need the guest environment (e.g. the
     * agent's `run_command`, or the `apk` package manager) must pass the kind the runtime would
     * create now — otherwise a shell opened before Alpine was installed is reused and `apk` runs on
     * the device shell instead of inside Alpine.
     */
    suspend fun findSessionByCwd(cwd: String, kind: SessionKind? = null): TerminalSession? = null

    /**
     * The backend new sessions would use right now (Alpine once it is installed, otherwise the device
     * shell). Lets callers decide whether a cached session is still the right one.
     */
    suspend fun preferredSessionKind(): SessionKind = SessionKind.DEVICE_SHELL

    /**
     * The path [hostPath] appears at inside the preferred backend's filesystem, or null when the
     * host path is already the path that backend sees.
     *
     * A proot guest binds the projects root at `/workspace`, so a project inside it is
     * `/workspace/<name>` and `ls /workspace` lists every project; external storage appears at
     * `/sdcard/<relative>`. The host spelling does not exist inside the guest, so a `cd` to the host
     * path fails, the shell keeps whatever directory it was left in, and the next command runs
     * somewhere else while the caller believes it is in the workspace.
     */
    suspend fun guestPath(hostPath: String): String? = null

    /** Rename a session for the persistent session UI (PRD 12.4). */
    suspend fun renameSession(sessionId: String, displayName: String) {}

    /**
     * Tell a session's terminal its size changed so the guest receives SIGWINCH (fixes full-screen
     * TUIs). Default is a no-op so Part 1 placeholders keep compiling.
     */
    fun resizeSession(sessionId: String, columns: Int, rows: Int) {}

    /** Raw output captured since the session started, so the UI can rebuild the screen on re-entry. */
    suspend fun replay(sessionId: String): String = ""

    /** The append-only installer log, for the environment setup screen (PRD 12.4). */
    suspend fun installLog(): String = ""

    /** Remove the residue of an interrupted install without touching a valid one (PRD 12.3). */
    suspend fun cleanIncompleteInstall(): Result<Unit> = Result.success(Unit)
}
