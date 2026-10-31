package com.aliedu.kern.feature.agent

import com.aliedu.kern.core.agent.AgentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-lifetime home for the agent's live turn.
 *
 * A turn must survive the screen that started it: leaving the agent tab, opening the editor, or
 * backgrounding the app all destroy the `ViewModel` (and with it `viewModelScope`), which used to
 * cancel the provider stream mid-task. The state and the running job live here instead, so a new
 * `AgentViewModel` attaches to whatever is already happening and the work keeps going.
 */
@Singleton
class AgentSessionStore @Inject constructor() {

    /**
     * Never cancelled: owned by the process, not by a screen. Replaced by tests so a turn runs on
     * the test dispatcher instead of real threads.
     */
    var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        internal set

    /** The single source of truth the agent screen renders. */
    val state = MutableStateFlow(AgentUiState())

    /**
     * The in-flight turn, if any. Shared rather than held per screen so the cancel button works
     * after a navigation round-trip and two screens can never start two turns at once.
     */
    var turnJob: Job? = null

    /**
     * Bumped whenever a turn starts and whenever the user stops one. Event handlers compare the
     * generation they were started with against this value before touching the UI state: an event
     * that was already inside the collector when Stop was pressed would otherwise write
     * `RUNNING_TOOL` back over the idle state, leaving the composer's Stop button and the
     * "Running …" phase pill on screen with nothing actually running.
     */
    var turnGeneration: Long = 0L

    /**
     * The session whose turn is running right now, if any.
     *
     * The screen can be switched to another session while a turn is still streaming — the turn
     * itself is not tied to a screen, so it keeps going. This is what tells the two apart: events
     * from a turn that belongs to a session the user has left must not paint the transcript they
     * are looking at, and re-attaching to a session whose turn is running must not treat its open
     * tool rows as orphans of a dead turn.
     */
    var runningTurnSessionId: String? = null

    /** The phase the running turn last reported, restored when the user comes back to it. */
    var runningTurnState: AgentState = AgentState.IDLE
}
