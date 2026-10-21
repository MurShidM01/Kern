package com.aliedu.kern.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliedu.kern.core.data.preferences.SettingsRepository
import com.aliedu.kern.core.terminal.api.EnvironmentState
import com.aliedu.kern.core.terminal.api.SessionKind
import com.aliedu.kern.core.terminal.api.TerminalRuntime
import com.aliedu.kern.core.tools.CommandExecutor
import com.aliedu.kern.core.tools.CommandImpact
import com.aliedu.kern.core.tools.CommandRequest
import com.aliedu.kern.core.tools.CommandResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PackageManagerUiState(
    val loading: Boolean = true,
    val environmentReady: Boolean = false,
    /**
     * Set when a Linux environment is installed but it is not Ubuntu. The screen drives `apt`, so the
     * catalog is meaningless in another guest; the screen points the user at the terminal instead of
     * running a command the guest does not have.
     */
    val unsupportedDistro: String? = null,
    /** True while a pull-to-refresh / app-bar refresh is loading the whole catalog. */
    val refreshing: Boolean = false,
    /** True while any single package command is in flight. */
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val query: String = "",
    val installed: List<String> = emptyList(),
    /** Every package the apt index offers (`apt-cache pkgnames`), i.e. everything installable. */
    val available: List<String> = emptyList(),
    /** [available] filtered by [query] — kept in state so the list is not re-filtered on recomposition. */
    val results: List<String> = emptyList(),
    val catalogLoaded: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false
)

/**
 * Drives the Ubuntu (`apt`) package manager. Every action runs through the shared [CommandExecutor],
 * which executes inside the workspace's Ubuntu session — the same path the agent uses for
 * `run_command` — so the app and the shell always see the same package database.
 *
 * The screen lists *every* package the index offers with an Install action, filters locally as the
 * user types, and streams the command output live so installs show their progress.
 */
@HiltViewModel
class PackageManagerViewModel @Inject constructor(
    private val executor: CommandExecutor,
    private val runtime: TerminalRuntime,
    private val settingsRepository: SettingsRepository,
    private val logStore: PackageLogStore,
    private val catalogStore: PackageCatalogStore
) : ViewModel() {

    private val _state = MutableStateFlow(PackageManagerUiState())
    val state: StateFlow<PackageManagerUiState> = _state.asStateFlow()

    @Volatile
    private var workspaceId: String? = null

    init {
        viewModelScope.launch {
            workspaceId = settingsRepository.lastWorkspaceId.first()
            if (catalogStore.isLoaded) {
                // Show the previous catalog straight away; the reload below updates it in place.
                _state.update {
                    applyQuery(
                        it.copy(
                            loading = false,
                            environmentReady = true,
                            catalogLoaded = true,
                            installed = catalogStore.installed,
                            available = catalogStore.available
                        )
                    )
                }
            } else {
                // Listing the whole index takes tens of seconds, so paint a curated shortlist first —
                // the screen is never empty and the full catalog replaces it when it arrives.
                _state.update { applyQuery(it.copy(available = POPULAR_PACKAGES)) }
            }
            reload(userInitiated = false)
        }
    }

    /**
     * Re-check the environment and, when Ubuntu is the active backend, reload the whole catalog.
     * Backs pull-to-refresh and the app-bar Update action.
     */
    fun refresh() = reload(userInitiated = true)

    /**
     * [userInitiated] controls the pull-to-refresh indicator only. The initial load must not raise it:
     * the spinner would show on every open, which reads as a refresh the user never asked for.
     */
    private fun reload(userInitiated: Boolean) {
        viewModelScope.launch {
            if (userInitiated) _state.update { it.copy(refreshing = true, message = null) }
            try {
                val info = runCatching { runtime.inspectInstallState() }.getOrNull()
                val ready = info?.state == EnvironmentState.READY &&
                    runCatching { runtime.preferredSessionKind() }.getOrNull() == SessionKind.LINUX_PROOT
                // Only Ubuntu is offered; a leftover install of another distribution has no apt.
                val unsupported = if (ready && info?.distroId != null && info.distroId != SUPPORTED_DISTRO_ID) {
                    info.distroName ?: info.distroId
                } else {
                    null
                }
                _state.update {
                    it.copy(loading = false, environmentReady = ready, unsupportedDistro = unsupported)
                }
                if (ready && unsupported == null) loadCatalog()
            } finally {
                if (userInitiated) _state.update { it.copy(refreshing = false) }
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { applyQuery(it.copy(query = value, message = null)) }
    }

    fun clearQuery() = _state.update { applyQuery(it.copy(query = "")) }

    /** `apt-get update` — fetch the index, then relist what became installable. */
    fun updateIndex() {
        viewModelScope.launch {
            val update = exec(
                label = "Updating the package index…",
                command = UPDATE_INDEX,
                impact = CommandImpact.NETWORK,
                timeoutMs = PACKAGE_TIMEOUT_MS
            )
            if (!update.result.succeeded) {
                setMessage(failureMessage(update.result))
                return@launch
            }
            val listing = exec(
                label = "Listing available packages…",
                command = LIST_AVAILABLE,
                impact = CommandImpact.READ_ONLY,
                maxOutputBytes = LISTING_MAX_BYTES
            )
            val names = parsePackageNames(listing.text)
            if (names.isNotEmpty()) catalogStore.putCatalog(names, catalogStore.installed)
            _state.update { applyQuery(it.copy(available = names, catalogLoaded = true)) }
        }
    }

    fun install(name: String) {
        viewModelScope.launch {
            val outcome = exec(
                label = "Installing " + name + "…",
                command = "DEBIAN_FRONTEND=noninteractive apt-get install -y " + sanitize(name),
                impact = CommandImpact.NETWORK,
                timeoutMs = PACKAGE_TIMEOUT_MS
            )
            if (outcome.result.succeeded) refreshInstalled() else setMessage(failureMessage(outcome.result))
        }
    }

    fun remove(name: String) {
        viewModelScope.launch {
            val outcome = exec(
                label = "Removing " + name + "…",
                command = "DEBIAN_FRONTEND=noninteractive apt-get remove -y " + sanitize(name),
                impact = CommandImpact.MUTATING,
                timeoutMs = PACKAGE_TIMEOUT_MS
            )
            if (outcome.result.succeeded) refreshInstalled() else setMessage(failureMessage(outcome.result))
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---- internals --------------------------------------------------------

    /**
     * Reads the installed set, then every installable package. A fresh Ubuntu image ships without an
     * apt index, so an empty listing triggers one `apt-get update` and a retry rather than leaving the
     * screen blank.
     */
    private suspend fun loadCatalog() {
        val installedOutcome = exec("Reading installed packages…", LIST_INSTALLED, CommandImpact.READ_ONLY)
        val installed = parsePackageNames(installedOutcome.text)

        var listing = exec(
            label = "Listing available packages…",
            command = LIST_AVAILABLE,
            impact = CommandImpact.READ_ONLY,
            maxOutputBytes = LISTING_MAX_BYTES
        )
        var names = parsePackageNames(listing.text)
        if (names.isEmpty() && !listing.result.timedOut) {
            val update = exec(
                label = "Updating the package index…",
                command = UPDATE_INDEX,
                impact = CommandImpact.NETWORK,
                timeoutMs = CommandRequest.MAX_TIMEOUT_MS
            )
            if (update.result.succeeded) {
                listing = exec(
                    label = "Listing available packages…",
                    command = LIST_AVAILABLE,
                    impact = CommandImpact.READ_ONLY,
                    maxOutputBytes = LISTING_MAX_BYTES
                )
                names = parsePackageNames(listing.text)
            } else {
                setMessage(failureMessage(update.result))
            }
        }
        // Never present a silently-truncated catalog as if it were complete.
        if (listing.result.timedOut) setMessage(failureMessage(listing.result))
        catalogStore.putCatalog(names, installed)
        // A failed listing must not blank a catalog we already have.
        val effective = names.ifEmpty { catalogStore.available }
        _state.update {
            applyQuery(it.copy(installed = installed, available = effective, catalogLoaded = true))
        }
    }

    private suspend fun refreshInstalled() {
        val outcome = exec("Reading installed packages…", LIST_INSTALLED, CommandImpact.READ_ONLY)
        if (outcome.result.succeeded) {
            val installed = parsePackageNames(outcome.text)
            catalogStore.putInstalled(installed)
            _state.update { it.copy(installed = installed) }
        }
    }

    private fun applyQuery(state: PackageManagerUiState): PackageManagerUiState {
        val term = state.query.trim()
        val results = if (term.isEmpty()) {
            state.available
        } else {
            state.available.filter { it.contains(term, ignoreCase = true) }
        }
        return state.copy(results = results)
    }

    private fun setMessage(text: String) =
        _state.update { it.copy(message = text, messageIsError = true) }

    private class Outcome(val result: CommandResult, val text: String)

    /**
     * Runs [command] in the Ubuntu session, streaming its output into the shared [PackageLogStore].
     *
     * [Outcome.text] is `CommandResult.output`, which the executor cleans of the tty's echo of the
     * command and of the exit sentinel — so the package list is never polluted by shell noise.
     * [maxOutputBytes] is raised for commands that legitimately print a lot.
     *
     * The timeout is deliberately generous: listing the index prints ~60k names through the PTY and
     * takes ~10s on a warm shell, more on a cold one. The default 30s cap used to cut it short and
     * silently yield a partial catalog (which read as "packages are missing").
     */
    private suspend fun exec(
        label: String,
        command: String,
        impact: CommandImpact,
        timeoutMs: Long = CommandRequest.MAX_TIMEOUT_MS,
        maxOutputBytes: Int = CommandRequest.DEFAULT_MAX_OUTPUT_BYTES
    ): Outcome {
        val id = workspaceId
        if (id == null) {
            return Outcome(
                CommandResult(null, "Open a workspace before managing packages.", timedOut = false),
                ""
            )
        }
        _state.update { it.copy(busy = true, busyLabel = label, message = null) }
        logStore.begin(command)
        val result = runCatching {
            executor.run(
                CommandRequest(
                    workspaceId = id,
                    command = command,
                    impact = impact,
                    timeoutMs = timeoutMs,
                    maxOutputBytes = maxOutputBytes
                )
            ) { chunk -> logStore.append(chunk) }
        }.getOrElse { error ->
            CommandResult(
                exitCode = null,
                output = error.message ?: "Command failed.",
                timedOut = false
            )
        }
        _state.update { it.copy(busy = false, busyLabel = null) }
        return Outcome(result, result.output)
    }

    private fun failureMessage(result: CommandResult): String = when {
        result.timedOut -> "The command timed out."
        result.exitCode == null -> "The command could not be run."
        else -> "Command failed (exit ${result.exitCode}). ${result.output.take(200)}".trim()
    }

    /** The shell is quoted from user input, so restrict it to characters that appear in package names. */
    private fun sanitize(value: String): String =
        value.trim().replace(Regex("[^A-Za-z0-9._+@/-]"), "")

    private companion object {
        /** `apt-cache pkgnames` lists every package in the index; ~60k names is well past the default cap. */
        const val LISTING_MAX_BYTES = 8 * 1024 * 1024
    }
}

/**
 * apt downloads indexes and packages over the device's network, which is far slower than a desktop:
 * `apt-get update` alone pulls tens of megabytes on a fresh image. The shared two-minute cap is not
 * enough, so package operations get their own budget.
 */
private const val PACKAGE_TIMEOUT_MS = 10 * 60_000L

/** Package commands. Kept together so the UI never builds a shell line itself. */
private const val UPDATE_INDEX = "apt-get update"

/** Index listing: bare names, no versions — the fastest complete list apt offers. */
private const val LIST_AVAILABLE = "apt-cache pkgnames"

/** `pkg\t\tinstall` per line; the parser keeps the first token of the `install` rows. */
private const val LIST_INSTALLED = "dpkg --get-selections"

/** Prefix of the executor's exit sentinel; its line must never be read as a package name. */
private const val SENTINEL = "__KERN_DONE_"

/**
 * A curated shortlist painted the instant the screen opens, before the full index has been listed
 * (which prints ~60k names and takes a few seconds). The complete catalog replaces it as soon as it
 * arrives, so this is a head start rather than a separate mode. Names are Debian/Ubuntu ones.
 */
internal val POPULAR_PACKAGES: List<String> = listOf(
    // Everyday shell + file tools.
    "bash", "zsh", "fish", "nano", "vim", "neovim", "less", "tree", "file", "htop", "lsof",
    "procps", "coreutils", "findutils", "grep", "sed", "gawk", "tar", "gzip", "bzip2", "xz-utils",
    "zstd", "unzip", "zip", "rsync", "curl", "wget", "ca-certificates", "openssh-client", "git",
    "tmux", "screen", "jq", "ripgrep", "fd-find", "fzf", "bat", "diffutils", "patch", "dos2unix",
    "shellcheck",
    // Toolchains.
    "build-essential", "gcc", "g++", "clang", "make", "cmake", "ninja-build", "pkg-config",
    "autoconf", "automake", "libtool", "gdb", "strace", "linux-headers-generic",
    // Languages + runtimes.
    "python3", "python3-pip", "python3-venv", "nodejs", "npm", "yarn", "golang", "rustc", "cargo",
    "default-jdk", "ruby", "perl", "lua5.4",
    // Data, network and services.
    "sqlite3", "postgresql", "mariadb-server", "redis-server", "nginx", "nmap", "socat",
    "net-tools", "iproute2", "dnsutils", "tcpdump", "openssl",
    // Media.
    "ffmpeg", "imagemagick", "tesseract-ocr", "yt-dlp"
)

/** Catalog id of the only distribution whose package manager Kern drives in-app. */
private const val SUPPORTED_DISTRO_ID = "ubuntu"

/**
 * One package name per line, from either `apt-cache pkgnames` (bare names) or
 * `dpkg --get-selections` (`name\t\tinstall`). Only the first token is kept, so both shapes work.
 *
 * Shell noise is dropped defensively: the tty's echo of the command (which starts with the prompt),
 * the executor's exit sentinel, and apt/dpkg warnings (`W:`, `E:`, `N:`, `dpkg: …`) that would
 * otherwise look like package names.
 */
internal fun parsePackageNames(output: String): List<String> =
    output.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot {
            it.startsWith("W:") || it.startsWith("E:") || it.startsWith("N:") ||
                it.startsWith("WARNING") || it.startsWith("ERROR") || it.startsWith("dpkg:")
        }
        .filterNot { it.startsWith('$') || it.contains(SENTINEL) }
        // `dpkg` qualifies names with the architecture (`libstdc++6:arm64`); the UI installs by
        // bare name so the native architecture is chosen.
        .map { it.substringBefore(' ').substringBefore('\t').trim().substringBefore(':') }
        .filter { it.matches(PACKAGE_NAME) }
        .distinct()
        .sorted()
        .toList()

/** Debian package names: lowercase, digits and `+ . -`. Also rejects prose that slips through. */
private val PACKAGE_NAME = Regex("^[a-z0-9][a-z0-9+.-]+$")
