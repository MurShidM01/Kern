package com.aliedu.kern.feature.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliedu.kern.core.tools.devserver.DevServer
import com.aliedu.kern.core.tools.devserver.DevServerRegistry
import com.aliedu.kern.core.tools.devserver.DevServerState
import com.aliedu.kern.core.tools.preview.ConsoleEntry
import com.aliedu.kern.core.tools.preview.ConsoleLevel
import com.aliedu.kern.core.tools.preview.PreviewController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the preview screen renders. */
data class PreviewUiState(
    val url: String? = null,
    val title: String? = null,
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: String? = null,
    /** Servers the agent has started, newest last. */
    val servers: List<DevServer> = emptyList(),
    val activeServerId: String? = null,
    val console: List<ConsoleEntry> = emptyList(),
    val consoleOpen: Boolean = false,
    /** The levels currently shown. Starts as every level, like a browser console. */
    val levels: Set<ConsoleLevel> = ConsoleLevel.entries.toSet(),
    val stoppingId: String? = null
) {
    val errorCount: Int get() = console.count { it.level == ConsoleLevel.ERROR }

    /** The console, filtered by the level chips. */
    val visibleConsole: List<ConsoleEntry> get() = console.filter { it.level in levels }

    val activeServer: DevServer? get() = servers.firstOrNull { it.id == activeServerId }

    /** True when there is something worth showing: a page, or a server to point at. */
    val hasSomethingToShow: Boolean
        get() = url != null || servers.any { it.state == DevServerState.RUNNING }
}

/**
 * Drives the preview screen.
 *
 * The state itself lives in [PreviewController] (a singleton) because the agent's tools write to it
 * from another screen entirely; this view model only adapts it for the UI and owns the screen-local
 * bits — whether the console drawer is open and which levels are filtered in.
 */
@HiltViewModel
class PreviewViewModel @Inject constructor(
    /** Exposed so the screen can register its WebView, and the agent's tools can drive it. */
    val controller: PreviewController,
    private val devServers: DevServerRegistry
) : ViewModel() {

    private val consoleOpen = MutableStateFlow(false)
    private val levels = MutableStateFlow(ConsoleLevel.entries.toSet())
    private val stoppingId = MutableStateFlow<String?>(null)

    val state: StateFlow<PreviewUiState> = combine(
        controller.state,
        controller.console,
        devServers.servers,
        consoleOpen,
        levels
    ) { preview, console, servers, open, filtered ->
        PreviewUiState(
            url = preview.url,
            title = preview.title,
            loading = preview.loading,
            progress = preview.progress,
            canGoBack = preview.canGoBack,
            canGoForward = preview.canGoForward,
            error = preview.error,
            servers = servers,
            activeServerId = servers.firstOrNull { it.url != null && it.url == preview.url }?.id,
            console = console,
            consoleOpen = open,
            levels = filtered,
            stoppingId = stoppingId.value
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PreviewUiState())

    /**
     * Called once the screen is on screen: points the preview at a running server when nothing is
     * selected yet, so opening the preview from the app bar shows the server instead of a blank page.
     */
    fun onScreenOpened() {
        if (controller.state.value.url != null) return
        val target = devServers.servers.value
            .lastOrNull { it.state == DevServerState.RUNNING && it.url != null }
            ?: return
        viewModelScope.launch { controller.open(target.url!!) }
    }

    fun selectServer(server: DevServer) {
        val url = server.url ?: return
        viewModelScope.launch { controller.open(url) }
    }

    fun reload() {
        viewModelScope.launch { controller.reload() }
    }

    fun goBack() {
        viewModelScope.launch { controller.goBack() }
    }

    fun goForward() {
        viewModelScope.launch { controller.goForward() }
    }

    fun stopServer(id: String) {
        viewModelScope.launch {
            stoppingId.value = id
            devServers.stop(id)
            stoppingId.value = null
        }
    }

    fun toggleConsole() {
        consoleOpen.value = !consoleOpen.value
    }

    fun clearConsole() {
        controller.clearConsole()
    }

    fun toggleLevel(level: ConsoleLevel) {
        val current = levels.value
        levels.value = if (level in current) current - level else current + level
    }

    private companion object {
        /** Keep collecting briefly across a rotation so the console does not blink empty. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
