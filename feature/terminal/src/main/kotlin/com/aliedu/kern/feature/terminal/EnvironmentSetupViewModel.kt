package com.aliedu.kern.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliedu.kern.core.terminal.api.DistroOption
import com.aliedu.kern.core.terminal.api.EnvironmentInfo
import com.aliedu.kern.core.terminal.api.EnvironmentState
import com.aliedu.kern.core.terminal.api.InstallEvent
import com.aliedu.kern.core.terminal.api.InstallProgress
import com.aliedu.kern.core.terminal.api.TerminalRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EnvironmentSetupUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val info: EnvironmentInfo? = null,
    /** Distributions the catalog offers for this device, in catalog order. */
    val distros: List<DistroOption> = emptyList(),
    /** The distribution the Install button acts on. */
    val selectedDistroId: String? = null,
    val installing: Boolean = false,
    val progress: InstallProgress? = null,
    val log: String = "",
    val logExpanded: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false
)

/** Drives the environment setup screen (PRD FR-015 / 12.3). */
@HiltViewModel
class EnvironmentSetupViewModel @Inject constructor(
    private val runtime: TerminalRuntime
) : ViewModel() {

    private val _state = MutableStateFlow(EnvironmentSetupUiState())
    val state: StateFlow<EnvironmentSetupUiState> = _state.asStateFlow()

    private var installJob: Job? = null

    init {
        refresh()
    }

    /** Re-read the install state and log. Backs pull-to-refresh and the initial load. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val info = runCatching { runtime.inspectInstallState() }.getOrNull()
            val distros = runCatching { runtime.availableDistros() }.getOrDefault(emptyList())
            val log = runCatching { runtime.installLog() }.getOrDefault("")
            _state.update { current ->
                val installed = info?.takeIf { it.state == EnvironmentState.READY }?.distroId
                val selected = current.selectedDistroId
                    ?.takeIf { id -> distros.any { it.id == id } }
                    ?: installed
                    ?: info?.distroId
                    ?: distros.firstOrNull()?.id
                current.copy(
                    loading = false,
                    refreshing = false,
                    info = info,
                    distros = distros,
                    selectedDistroId = selected,
                    log = log
                )
            }
        }
    }

    /** Choose which distribution the Install button acts on. */
    fun selectDistro(id: String) {
        if (_state.value.installing) return
        _state.update { it.copy(selectedDistroId = id) }
    }

    fun install() {
        if (installJob?.isActive == true) return
        val distroId = _state.value.selectedDistroId
        installJob = viewModelScope.launch {
            _state.update { it.copy(installing = true, error = null, progress = null, completed = false) }
            runCatching {
                runtime.installOrRepair(distroId) { progress -> _state.update { it.copy(progress = progress) } }
                    .collect { event -> onEvent(event) }
            }.onFailure { error ->
                _state.update { it.copy(installing = false, error = error.message ?: "Install failed.") }
            }
            val info = runCatching { runtime.inspectInstallState() }.getOrNull()
            val log = runCatching { runtime.installLog() }.getOrDefault("")
            _state.update { it.copy(installing = false, info = info, log = log) }
        }
    }

    private fun onEvent(event: InstallEvent) {
        when (event) {
            is InstallEvent.Progress -> _state.update { it.copy(progress = event.progress) }
            is InstallEvent.Completed -> _state.update { it.copy(completed = true, info = event.info) }
            is InstallEvent.Failed -> _state.update { it.copy(error = event.message) }
        }
    }

    fun cancel() {
        installJob?.cancel()
        viewModelScope.launch {
            val info = runCatching { runtime.inspectInstallState() }.getOrNull()
            val log = runCatching { runtime.installLog() }.getOrDefault("")
            _state.update { it.copy(installing = false, error = "Install cancelled.", progress = null, info = info, log = log) }
        }
    }

    fun uninstall() {
        viewModelScope.launch {
            runCatching { runtime.uninstallEnvironment() }
            refresh()
        }
    }

    fun cleanIncomplete() {
        viewModelScope.launch {
            runCatching { runtime.cleanIncompleteInstall() }
            refresh()
        }
    }

    fun toggleLog() = _state.update { it.copy(logExpanded = !it.logExpanded) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeCompleted() = _state.update { it.copy(completed = false) }
}
