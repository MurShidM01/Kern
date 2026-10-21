package com.aliedu.kern.feature.home

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliedu.kern.core.data.preferences.SettingsRepository
import com.aliedu.kern.core.data.repository.ProviderRepository
import com.aliedu.kern.core.data.repository.SessionRepository
import com.aliedu.kern.core.data.repository.WorkspaceRepository
import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.ProviderProfile
import com.aliedu.kern.core.model.Session
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.workspace.StoragePermission
import com.aliedu.kern.core.workspace.WorkspaceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val workspaces: List<Workspace> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val activeWorkspace: Workspace? = null,
    val providerConfigured: Boolean = false,
    val activeGrantState: GrantState = GrantState.UNKNOWN,
    val storageAccessGranted: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val sessionRepository: SessionRepository,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val workspaceManager: WorkspaceManager,
    private val storagePermission: StoragePermission
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val storageAccess = MutableStateFlow(storagePermission.isGranted())
    private val refreshing = MutableStateFlow(false)

    private data class HomeInputs(
        val workspaces: List<Workspace>,
        val sessions: List<Session>,
        val onboarding: Boolean,
        val lastId: String?,
        val profiles: List<ProviderProfile>
    )

    val uiState: StateFlow<HomeUiState> = combine(
        combine(
            workspaceRepository.observeWorkspaces(),
            sessionRepository.observeSessions(),
            settingsRepository.onboardingCompleted,
            settingsRepository.lastWorkspaceId,
            providerRepository.observeProfiles()
        ) { workspaces, sessions, onboarding, lastId, profiles ->
            HomeInputs(workspaces, sessions, onboarding, lastId, profiles)
        },
        busy,
        error,
        storageAccess,
        refreshing
    ) { inputs, isBusy, err, hasStorageAccess, isRefreshing ->
        val active = inputs.workspaces.firstOrNull { it.id == inputs.lastId }
        HomeUiState(
            loading = false,
            refreshing = isRefreshing,
            onboardingCompleted = inputs.onboarding,
            workspaces = inputs.workspaces,
            sessions = inputs.sessions,
            activeWorkspace = active,
            providerConfigured = inputs.profiles.isNotEmpty(),
            activeGrantState = active?.grantState ?: GrantState.UNKNOWN,
            storageAccessGranted = hasStorageAccess,
            busy = isBusy,
            error = err
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    val defaultProfile: StateFlow<ProviderProfile?> = providerRepository.observeDefaultProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun completeOnboarding() {
        viewModelScope.launch { settingsRepository.setOnboardingCompleted(true) }
    }

    /** Re-reads all-files access; call on resume so a grant made in system settings is picked up. */
    fun refreshStorageAccess() {
        storageAccess.value = storagePermission.isGranted()
    }

    /**
     * Pull-to-refresh: re-read the storage grant and re-validate the active workspace's folder, so a
     * permission granted (or a folder reconnected) outside the app is reflected here.
     */
    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            storageAccess.value = storagePermission.isGranted()
            val active = uiState.value.activeWorkspace
            if (active != null) runCatching { workspaceManager.refreshGrantState(active) }
            refreshing.value = false
        }
    }

    /**
     * The intents that open the all-files-access screen, most specific first. The caller tries each
     * in turn: some OEMs do not ship the per-app screen, so the global list is the fallback.
     */
    fun storageAccessIntents(): List<Intent> =
        listOf(storagePermission.requestIntent(), storagePermission.settingsIntent())

    fun openSafWorkspace(uri: Uri, displayName: String) {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            runCatching { workspaceManager.openSafWorkspace(uri, displayName) }
                .onFailure { error.value = "Could not open that folder: ${it.message}" }
            busy.value = false
        }
    }

    fun createAppManagedWorkspace(name: String) {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            runCatching { workspaceManager.createAppManagedWorkspace(name) }
                .onFailure { error.value = "Could not create the workspace: ${it.message}" }
            busy.value = false
        }
    }

    fun setActive(workspace: Workspace) {
        viewModelScope.launch {
            workspaceManager.setActive(workspace.id)
            workspaceManager.refreshGrantState(workspace)
        }
    }

    fun deleteWorkspace(workspace: Workspace) {
        viewModelScope.launch {
            runCatching { workspaceManager.deleteWorkspace(workspace) }
                .onFailure { error.value = "Could not delete the workspace: ${it.message}" }
        }
    }

    fun deleteSession(session: Session) {
        viewModelScope.launch {
            runCatching { sessionRepository.delete(session.id) }
                .onFailure { error.value = "Could not delete the session: ${it.message}" }
        }
    }

    fun clearSessions() {
        viewModelScope.launch {
            runCatching { sessionRepository.deleteAllSessions() }
                .onFailure { error.value = "Could not clear sessions: ${it.message}" }
        }
    }

    fun clearError() {
        error.value = null
    }
}
