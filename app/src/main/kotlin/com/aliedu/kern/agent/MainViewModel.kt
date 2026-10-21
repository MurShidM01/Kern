package com.aliedu.kern.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliedu.kern.core.data.preferences.SettingsRepository
import com.aliedu.kern.core.data.repository.ProviderRepository
import com.aliedu.kern.core.data.repository.WorkspaceRepository
import com.aliedu.kern.core.model.ThemeMode
import com.aliedu.kern.core.model.ThemePalette
import com.aliedu.kern.core.tools.preview.PreviewController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainState(
    val ready: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themePalette: ThemePalette = ThemePalette.INDIGO,
    val dynamicColor: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val activeWorkspaceId: String? = null,
    val activeWorkspaceName: String? = null,
    val providerLabel: String? = null
)

/**
 * App-level state: theme (P1-03), onboarding, active workspace, and the provider chip shown in
 * top bars. Backed by local persistence so it survives process death.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val providerRepository: ProviderRepository,
    previewController: PreviewController
) : ViewModel() {

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    /**
     * Bumped when the agent calls `preview_open`. The shell watches it and navigates, so "open the
     * preview" actually opens it instead of only pointing a WebView the user has to go find.
     */
    val previewOpenSignal: StateFlow<Long> = previewController.openSignal

    init {
        viewModelScope.launch {
            settingsRepository.themeMode.collect { mode ->
                _state.value = _state.value.copy(themeMode = mode)
            }
        }
        viewModelScope.launch {
            settingsRepository.themePalette.collect { palette ->
                _state.value = _state.value.copy(themePalette = palette)
            }
        }
        viewModelScope.launch {
            settingsRepository.dynamicColor.collect { enabled ->
                _state.value = _state.value.copy(dynamicColor = enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.onboardingCompleted.collect { completed ->
                _state.value = _state.value.copy(onboardingCompleted = completed, ready = true)
            }
        }
        viewModelScope.launch {
            combine(
                workspaceRepository.observeWorkspaces(),
                settingsRepository.lastWorkspaceId
            ) { workspaces, id ->
                id to workspaces.firstOrNull { it.id == id }?.displayName
            }.collect { (id, name) ->
                _state.value = _state.value.copy(activeWorkspaceId = id, activeWorkspaceName = name)
            }
        }
        viewModelScope.launch {
            providerRepository.observeDefaultProfile().collect { profile ->
                _state.value = _state.value.copy(
                    providerLabel = profile?.modelId
                )
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }

    fun completeOnboarding() = viewModelScope.launch { settingsRepository.setOnboardingCompleted(true) }
}
