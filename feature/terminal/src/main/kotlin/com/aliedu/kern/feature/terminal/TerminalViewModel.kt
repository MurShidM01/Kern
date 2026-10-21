package com.aliedu.kern.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliedu.kern.core.data.preferences.SettingsRepository
import com.aliedu.kern.core.data.repository.WorkspaceRepository
import com.aliedu.kern.core.model.TerminalTheme
import com.aliedu.kern.core.terminal.api.EnvironmentInfo
import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.SessionKind
import com.aliedu.kern.core.terminal.api.TerminalOutput
import com.aliedu.kern.core.terminal.api.TerminalRuntime
import com.aliedu.kern.core.terminal.api.TerminalSession
import com.aliedu.kern.core.terminal.engine.TerminalEmulator
import com.aliedu.kern.core.terminal.engine.model.ScreenSnapshot
import com.aliedu.kern.core.workspace.ExecutionWorkspaceResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** Virtual keys offered above the soft keyboard (PRD 12.4). */
enum class TerminalKey { ESC, TAB, CTRL_C, CTRL_D, CTRL_Z, CTRL_L, UP, DOWN, LEFT, RIGHT, HOME, END }

data class TerminalSessionUi(
    val id: String,
    val displayName: String,
    val cwd: String,
    val kind: SessionKind,
    val snapshot: ScreenSnapshot,
    val alive: Boolean,
    val exitCode: Int?
)

data class TerminalUiState(
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val workspaceName: String? = null,
    val environment: EnvironmentInfo? = null,
    val sessions: List<TerminalSessionUi> = emptyList(),
    val activeSessionId: String? = null,
    val message: String? = null,
    val fontSizeSp: Float = 12f,
    val terminalTheme: TerminalTheme = TerminalTheme.FOLLOW_APP,
    val ctrlArmed: Boolean = false,
    val altArmed: Boolean = false,
    val sidebarOpen: Boolean = false,
    val selection: TerminalSelection? = null
) {
    val active: TerminalSessionUi? get() = sessions.firstOrNull { it.id == activeSessionId }
}

/**
 * Drives the terminal screen. One [TerminalEmulator] per session renders output; sessions themselves
 * live in the singleton [TerminalRuntime], so navigating away and back re-attaches to the same shell
 * and rebuilds the screen from the captured byte log.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val runtime: TerminalRuntime,
    private val executionWorkspaces: ExecutionWorkspaceResolver,
    private val settingsRepository: SettingsRepository,
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private data class Holder(
        val session: TerminalSession,
        val emulator: TerminalEmulator,
        var alive: Boolean = true,
        var exitCode: Int? = null,
        var job: Job? = null
    )

    private val holders = LinkedHashMap<String, Holder>()
    private var executionWorkspace: ExecutionWorkspace? = null

    /** Serialises [reconcile] so a resume racing the initial bootstrap cannot start two shells. */
    private val reconcileMutex = Mutex()

    /**
     * Conflated repaint requests. A command can emit thousands of PTY chunks in one burst (listing
     * every apt package is ~60k lines), and [publishNow] copies each session's whole scrollback —
     * doing that per chunk is enough to throttle the producing shell, because the session's output
     * flow applies backpressure. Conflating caps the UI at one repaint per [REPAINT_MIN_INTERVAL_MS].
     */
    private val repaintRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var screenVisible = true

    private var columns = 80
    private var rows = 24
    private var scrollback = DEFAULT_SCROLLBACK

    init {
        viewModelScope.launch {
            while (true) {
                repaintRequests.receive()
                publishNow()
                delay(REPAINT_MIN_INTERVAL_MS)
            }
        }
        // Font size applies live: the renderer re-measures its cell grid when the value changes.
        viewModelScope.launch {
            settingsRepository.terminalFontSizeSp.collect { size ->
                _state.update { it.copy(fontSizeSp = size) }
            }
        }
        // Terminal theme applies live: switching it repaints without restarting the shell.
        viewModelScope.launch {
            settingsRepository.terminalTheme.collect { theme ->
                _state.update { it.copy(terminalTheme = theme) }
            }
        }
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        // Scrollback bounds the emulator's history buffer, so it is read once per session set.
        scrollback = settingsRepository.terminalScrollback.first()
        val workspaceId = settingsRepository.lastWorkspaceId.first()
        val workspace = workspaceId?.let { runCatching { workspaceRepository.getWorkspace(it) }.getOrNull() }
        _state.update { it.copy(workspaceName = workspace?.displayName) }
        reconcile()
        _state.update { it.copy(loading = false) }
    }

    /**
     * Bring the live sessions in line with the current environment. Runs on first load and every time
     * the screen resumes, so installing (or removing) the Linux environment elsewhere is picked up here instead of
     * leaving the user on a shell that no longer matches the guest.
     *
     * Sessions on the wrong backend are closed, sessions on the right backend are adopted (the
     * runtime keeps them across navigation, and the command executor may have created one), and a
     * fresh session is started when nothing is left.
     */
    private suspend fun reconcile() = reconcileMutex.withLock {
        val environment = runCatching { runtime.inspectInstallState() }.getOrNull()
        val desired = runCatching { runtime.preferredSessionKind() }
            .getOrDefault(SessionKind.DEVICE_SHELL)
        _state.update { it.copy(environment = environment) }

        holders.values.filter { it.session.kind != desired }.forEach { holder ->
            holder.job?.cancel()
            holders.remove(holder.session.id)
            runCatching { runtime.closeSession(holder.session.id) }
        }

        runCatching { runtime.listSessions() }.getOrDefault(emptyList())
            .filter { it.kind == desired }
            .forEach { attach(it) }

        if (holders.isEmpty()) {
            startSession()
        } else if (holders.keys.none { it == _state.value.activeSessionId }) {
            _state.update { it.copy(activeSessionId = holders.keys.first(), selection = null) }
        }
        publish()
    }

    /** Re-sync the environment and sessions. Backs the terminal's refresh action and pull gesture. */
    fun syncEnvironment() {
        viewModelScope.launch {
            _state.update { it.copy(syncing = true) }
            try {
                reconcile()
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    private suspend fun resolveWorkspace(workspaceId: String?): ExecutionWorkspace {
        if (workspaceId != null) {
            runCatching { executionWorkspaces.prepare(workspaceId) }.onSuccess { return it }
        }
        return executionWorkspaces.scratchWorkspace()
    }

    /** Start a session on the current backend and make it active; returns null when it cannot start. */
    private suspend fun startSession(): TerminalSession? {
        val workspace = executionWorkspace
            ?: resolveWorkspace(settingsRepository.lastWorkspaceId.first()).also { executionWorkspace = it }
        val session = runCatching { runtime.createSession(workspace) }.getOrElse { error ->
            _state.update { it.copy(message = error.message ?: "Could not start a shell.") }
            return null
        }
        attach(session)
        _state.update { it.copy(activeSessionId = session.id) }
        return session
    }

    private suspend fun attach(session: TerminalSession) {
        if (holders.containsKey(session.id)) return
        val emulator = TerminalEmulator(columns, rows, scrollback)
        val holder = Holder(session = session, emulator = emulator)
        holders[session.id] = holder

        // Seed the screen from the captured byte log before subscribing, so re-entering the tab
        // reconstructs what the shell already printed.
        val replay = runCatching { runtime.replay(session.id) }.getOrDefault("")
        if (replay.isNotEmpty()) feed(holder, replay)

        holder.job = viewModelScope.launch {
            runCatching {
                runtime.observeOutput(session.id).collect { output -> onOutput(holder, output) }
            }
        }
        publish()
    }

    private fun feed(holder: Holder, text: String) {
        val responses = holder.emulator.feed(text)
        for (response in responses) {
            runCatching { runtime.sendInput(holder.session.id, response.toByteArray(Charsets.UTF_8)) }
        }
    }

    private fun onOutput(holder: Holder, output: TerminalOutput) {
        when (output) {
            is TerminalOutput.Stdout -> feed(holder, output.text)
            is TerminalOutput.Stderr -> feed(holder, output.text)
            is TerminalOutput.Exited -> {
                holder.alive = false
                holder.exitCode = output.exitCode
            }
        }
        publishCoalesced()
    }

    /** Rebuild the screen snapshots immediately — used by user actions, which expect instant feedback. */
    private fun publish() = publishNow()

    /** Request a repaint without blocking the caller; a burst of output collapses into one rebuild. */
    private fun publishCoalesced() {
        if (screenVisible) repaintRequests.trySend(Unit)
    }

    /**
     * Whether the terminal is on screen. Output is still parsed into the emulator while the screen is
     * hidden (so re-entering shows the current state), but the snapshots are not rebuilt: they copy
     * the whole scrollback and run on the main thread, and doing that while another screen is driving
     * the same shell would starve this screen's own output collector.
     */
    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        if (visible) repaintRequests.trySend(Unit)
    }

    private fun publishNow() {
        _state.update { current ->
            current.copy(
                sessions = holders.values.map { holder ->
                    TerminalSessionUi(
                        id = holder.session.id,
                        displayName = holder.session.displayName,
                        cwd = holder.session.cwd,
                        kind = holder.session.kind,
                        snapshot = holder.emulator.snapshot(),
                        alive = holder.alive,
                        exitCode = holder.exitCode
                    )
                }
            )
        }
    }

    // ---- user actions -----------------------------------------------------

    fun selectSession(id: String) {
        if (holders.containsKey(id)) {
            _state.update { it.copy(activeSessionId = id, selection = null, sidebarOpen = false) }
        }
    }

    fun createSession() {
        viewModelScope.launch { startSession() }
    }

    fun closeSession(id: String) {
        holders.remove(id)?.job?.cancel()
        viewModelScope.launch { runCatching { runtime.closeSession(id) } }
        val next = holders.keys.firstOrNull()
        _state.update {
            it.copy(
                activeSessionId = if (it.activeSessionId == id) next else it.activeSessionId,
                selection = null
            )
        }
        publish()
    }

    fun renameSession(id: String, displayName: String) {
        val name = displayName.trim().ifBlank { return }
        val holder = holders[id] ?: return
        viewModelScope.launch { runCatching { runtime.renameSession(id, name) } }
        holders[id] = holder.copy(session = holder.session.copy(displayName = name))
        publish()
    }

    fun sendInput(text: String) {
        val id = _state.value.activeSessionId ?: return
        // Typed text may carry a newline from the IME; a terminal expects carriage return.
        val bytes = applyModifiers(text.replace("\n", "\r")).toByteArray(Charsets.UTF_8)
        viewModelScope.launch { runCatching { runtime.sendInput(id, bytes) } }
    }

    /** Send a raw control/escape sequence (Enter, Backspace, arrows, TAB…) with no sticky modifiers. */
    fun sendSequence(sequence: String) {
        val id = _state.value.activeSessionId ?: return
        viewModelScope.launch { runCatching { runtime.sendInput(id, sequence.toByteArray(Charsets.UTF_8)) } }
    }

    fun sendKey(key: TerminalKey) {
        val id = _state.value.activeSessionId ?: return
        val sequence = keySequence(key)
        viewModelScope.launch { runCatching { runtime.sendInput(id, sequence.toByteArray(Charsets.UTF_8)) } }
    }

    fun toggleCtrl() = _state.update { it.copy(ctrlArmed = !it.ctrlArmed) }
    fun toggleAlt() = _state.update { it.copy(altArmed = !it.altArmed) }

    fun clearActive() {
        val holder = holders[_state.value.activeSessionId ?: return] ?: return
        holder.emulator.clearView()
        publish()
    }

    fun stopActive() {
        val id = _state.value.activeSessionId ?: return
        viewModelScope.launch { runCatching { runtime.requestStop(id) } }
    }

    fun scrollBy(delta: Int) {
        val holder = holders[_state.value.activeSessionId ?: return] ?: return
        holder.emulator.scrollViewportBy(delta)
        publish()
    }

    fun onResize(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        if (columns == this.columns && rows == this.rows) return
        this.columns = columns
        this.rows = rows
        holders.values.forEach { it.emulator.resize(columns, rows) }
        // Tell the guest its window changed so full-screen TUIs reflow (SIGWINCH).
        _state.value.activeSessionId?.let { runtime.resizeSession(it, columns, rows) }
        publish()
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---- sidebar / selection ----------------------------------------------

    fun toggleSidebar() = _state.update { it.copy(sidebarOpen = !it.sidebarOpen) }
    fun closeSidebar() = _state.update { it.copy(sidebarOpen = false) }

    fun beginSelection(position: CellPos) =
        _state.update { it.copy(selection = TerminalSelection(position, position)) }

    fun extendSelection(position: CellPos) =
        _state.update { current ->
            val existing = current.selection ?: TerminalSelection(position, position)
            current.copy(selection = existing.copy(focus = position))
        }

    fun clearSelection() = _state.update { it.copy(selection = null) }

    fun selectAll() {
        val snapshot = holders[_state.value.activeSessionId]?.emulator?.snapshot() ?: return
        _state.update { it.copy(selection = TerminalSelection.all(snapshot)) }
    }

    /** The selected text on the active session, or an empty string when nothing is selected. */
    fun selectedText(): String {
        val current = _state.value
        val selection = current.selection ?: return ""
        return selection.text(current.active?.snapshot ?: return "")
    }

    // ---- helpers ----------------------------------------------------------

    private fun applyModifiers(text: String): String {
        val snapshot = _state.value
        var result = text
        if (snapshot.ctrlArmed) result = result.map { controlCode(it) }.joinToString("")
        if (snapshot.altArmed) result = result.map { "\u001b$it" }.joinToString("")
        if (snapshot.ctrlArmed || snapshot.altArmed) {
            _state.update { it.copy(ctrlArmed = false, altArmed = false) }
        }
        return result
    }

    private fun controlCode(c: Char): Char = when {
        c in 'a'..'z' -> (c.code - 'a'.code + 1).toChar()
        c in 'A'..'Z' -> (c.code - 'A'.code + 1).toChar()
        c == ' ' -> '\u0000'
        c == '[' -> '\u001b'
        c == '\\' -> '\u001c'
        c == ']' -> '\u001d'
        c == '^' -> '\u001e'
        c == '_' -> '\u001f'
        else -> c
    }

    private fun keySequence(key: TerminalKey): String {
        val application = holders[_state.value.activeSessionId]?.emulator?.snapshot()?.applicationCursorKeys == true
        return when (key) {
            TerminalKey.ESC -> "\u001b"
            TerminalKey.TAB -> "\t"
            TerminalKey.CTRL_C -> "\u0003"
            TerminalKey.CTRL_D -> "\u0004"
            TerminalKey.CTRL_Z -> "\u001a"
            TerminalKey.CTRL_L -> "\u000c"
            TerminalKey.UP -> if (application) "\u001bOA" else "\u001b[A"
            TerminalKey.DOWN -> if (application) "\u001bOB" else "\u001b[B"
            TerminalKey.RIGHT -> if (application) "\u001bOC" else "\u001b[C"
            TerminalKey.LEFT -> if (application) "\u001bOD" else "\u001b[D"
            TerminalKey.HOME -> "\u001b[H"
            TerminalKey.END -> "\u001b[F"
        }
    }

    private companion object {
        const val DEFAULT_SCROLLBACK = 1000

        /** Upper bound on how often a burst of output can rebuild the screen snapshots (~30 fps). */
        const val REPAINT_MIN_INTERVAL_MS = 32L
    }
}
