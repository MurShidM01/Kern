package com.aliedu.kern.feature.agent

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
}
