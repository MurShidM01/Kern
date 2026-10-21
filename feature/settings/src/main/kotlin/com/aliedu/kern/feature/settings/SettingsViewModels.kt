package com.aliedu.kern.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliedu.kern.core.data.preferences.SettingsRepository
import com.aliedu.kern.core.data.repository.ProviderRepository
import com.aliedu.kern.core.data.repository.SessionRepository
import com.aliedu.kern.core.data.security.CredentialStore
import com.aliedu.kern.core.model.ProviderCapabilities
import com.aliedu.kern.core.model.ProviderProfile
import com.aliedu.kern.core.model.ProviderProtocol
import com.aliedu.kern.core.model.TerminalTheme
import com.aliedu.kern.core.model.ThemeMode
import com.aliedu.kern.core.model.ThemePalette
import com.aliedu.kern.core.providers.api.AiProviderAdapter
import com.aliedu.kern.core.providers.api.ModelInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URL
import java.util.UUID
import javax.inject.Inject

data class SettingsUiState(
    val refreshing: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themePalette: ThemePalette = ThemePalette.INDIGO,
    val dynamicColor: Boolean = false,
    val editorFontSizeSp: Float = 13f,
    val chatFontSizeSp: Float = 12f,
    val terminalFontSizeSp: Float = 12f,
    val terminalTheme: TerminalTheme = TerminalTheme.FOLLOW_APP,
    val terminalScrollback: Int = 1000,
    val showReasoning: Boolean = true,
    val autoScroll: Boolean = true,
    val profiles: List<ProviderProfile> = emptyList(),
    val defaultProfileId: String? = null,
    /** True when the default profile is set but its stored API key is missing/unreadable. */
    val defaultProviderKeyMissing: Boolean = false,
    val sessionCount: Int = 0
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val providerRepository: ProviderRepository,
    private val sessionRepository: SessionRepository,
    private val credentialStore: CredentialStore,
    private val providerAdapter: AiProviderAdapter
) : ViewModel() {

    private data class Appearance(
        val themeMode: ThemeMode,
        val themePalette: ThemePalette,
        val dynamicColor: Boolean,
        val editorFontSizeSp: Float
    )

    private data class Chat(
        val chatFontSizeSp: Float,
        val showReasoning: Boolean,
        val autoScroll: Boolean
    )

    private data class Terminal(
        val terminalFontSizeSp: Float,
        val terminalTheme: TerminalTheme,
        val terminalScrollback: Int
    )

    private data class Providers(
        val profiles: List<ProviderProfile>,
        val defaultProfileId: String?
    )

    /** Non-preference, refresh-derived state (session count, pull-to-refresh, credential probe). */
    private data class Derived(
        val sessionCount: Int,
        val refreshing: Boolean,
        val keyMissing: Boolean
    )

    private val refreshing = MutableStateFlow(false)

    /** Bumped by [refresh] so the credential check below is re-run on demand. */
    private val credentialRecheck = MutableStateFlow(0)

    /**
     * Whether the default profile has no usable stored key.
     *
     * Derived rather than written on refresh only: a profile saved without a key (or a key that went
     * missing with restored data) has to be reported before the first agent request, not after the
     * user happens to pull the screen down. Saving a profile re-emits the profile list, so the check
     * also clears as soon as a key is stored.
     */
    private val keyMissing: Flow<Boolean> = combine(
        providerRepository.observeProfiles(),
        providerRepository.observeDefaultProfile(),
        credentialRecheck
    ) { _, defaultProfile, _ ->
        val id = defaultProfile?.id
        id != null && !runCatching { credentialStore.hasCredential(id) }.getOrDefault(true)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            settingsRepository.themeMode,
            settingsRepository.themePalette,
            settingsRepository.dynamicColor,
            settingsRepository.editorFontSizeSp
        ) { themeMode, themePalette, dynamicColor, editorFontSizeSp ->
            Appearance(themeMode, themePalette, dynamicColor, editorFontSizeSp)
        },
        combine(
            settingsRepository.chatFontSizeSp,
            settingsRepository.showReasoning,
            settingsRepository.autoScroll
        ) { chatFontSizeSp, showReasoning, autoScroll ->
            Chat(chatFontSizeSp, showReasoning, autoScroll)
        },
        combine(
            settingsRepository.terminalFontSizeSp,
            settingsRepository.terminalTheme,
            settingsRepository.terminalScrollback
        ) { terminalFontSizeSp, terminalTheme, terminalScrollback ->
            Terminal(terminalFontSizeSp, terminalTheme, terminalScrollback)
        },
        combine(
            providerRepository.observeProfiles(),
            providerRepository.observeDefaultProfile()
        ) { profiles, defaultProfile ->
            Providers(profiles, defaultProfile?.id)
        },
        combine(
            sessionRepository.observeSessionCount(),
            refreshing,
            keyMissing
        ) { count, isRefreshing, missing -> Derived(count, isRefreshing, missing) }
    ) { appearance, chat, terminal, providers, derived ->
        SettingsUiState(
            refreshing = derived.refreshing,
            themeMode = appearance.themeMode,
            themePalette = appearance.themePalette,
            dynamicColor = appearance.dynamicColor,
            editorFontSizeSp = appearance.editorFontSizeSp,
            chatFontSizeSp = chat.chatFontSizeSp,
            terminalFontSizeSp = terminal.terminalFontSizeSp,
            terminalTheme = terminal.terminalTheme,
            terminalScrollback = terminal.terminalScrollback,
            showReasoning = chat.showReasoning,
            autoScroll = chat.autoScroll,
            profiles = providers.profiles,
            defaultProfileId = providers.defaultProfileId,
            defaultProviderKeyMissing = derived.keyMissing,
            sessionCount = derived.sessionCount
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }

    fun setThemePalette(palette: ThemePalette) =
        viewModelScope.launch { settingsRepository.setThemePalette(palette) }

    fun setDynamicColor(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }

    fun setEditorFontSize(sizeSp: Float) =
        viewModelScope.launch { settingsRepository.setEditorFontSizeSp(sizeSp) }

    fun setChatFontSize(sizeSp: Float) =
        viewModelScope.launch { settingsRepository.setChatFontSizeSp(sizeSp) }

    fun setTerminalFontSize(sizeSp: Float) =
        viewModelScope.launch { settingsRepository.setTerminalFontSizeSp(sizeSp) }

    fun setTerminalTheme(theme: TerminalTheme) =
        viewModelScope.launch { settingsRepository.setTerminalTheme(theme) }

    fun setTerminalScrollback(lines: Int) =
        viewModelScope.launch { settingsRepository.setTerminalScrollback(lines) }

    fun setShowReasoning(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setShowReasoning(enabled) }

    fun setAutoScroll(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setAutoScroll(enabled) }

    fun setDefaultProfile(id: String) = viewModelScope.launch { providerRepository.setDefault(id) }

    fun deleteProfile(id: String) = viewModelScope.launch { providerRepository.delete(id) }

    fun clearAllSessions() = viewModelScope.launch { sessionRepository.deleteAllSessions() }

    /**
     * Pull-to-refresh. Preferences are flow-backed and already live, so the meaningful re-read here is
     * the default provider's credential: it surfaces a profile whose stored key went missing (cleared
     * data, restored backup) instead of failing silently at the first agent call.
     */
    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            credentialRecheck.value += 1
            refreshing.value = false
        }
    }

    // ---- Provider models sheet -------------------------------------------------------------

    private val _models = MutableStateFlow(ProviderModelsUiState())

    /** State of the "models" bottom sheet (null profileId = hidden). */
    val models: StateFlow<ProviderModelsUiState> = _models.asStateFlow()

    /**
     * Opens the model sheet for [profileId] and fetches the provider's catalogue from `GET
     * {BASE_URL}/models` — the OpenAI-compatible listing every supported endpoint exposes.
     */
    fun openModels(profileId: String) {
        val profile = uiState.value.profiles.firstOrNull { it.id == profileId } ?: return
        _models.value = ProviderModelsUiState(
            profileId = profile.id,
            profileName = profile.displayName,
            currentModelId = profile.modelId,
            loading = true
        )
        viewModelScope.launch {
            val key = runCatching { credentialStore.getApiKey(profileId) }.getOrNull()
            val result = providerAdapter.listModels(profile, key)
            // The sheet may have been dismissed or re-opened for another profile meanwhile.
            if (_models.value.profileId != profileId) return@launch
            result.fold(
                onSuccess = { list ->
                    _models.value = _models.value.copy(
                        loading = false,
                        models = list.sortedBy { it.id },
                        error = if (list.isEmpty()) "The endpoint returned no models." else null
                    )
                },
                onFailure = { t ->
                    _models.value = _models.value.copy(
                        loading = false,
                        error = providerAdapter.normalizeError(t).userMessage
                    )
                }
            )
        }
    }

    fun dismissModels() {
        _models.value = ProviderModelsUiState()
    }

    /** Makes [modelId] the profile's active model, then closes the sheet. */
    fun selectModel(modelId: String) {
        val id = _models.value.profileId ?: return
        viewModelScope.launch {
            val profile = providerRepository.getProfile(id) ?: return@launch
            providerRepository.upsert(profile.copy(modelId = modelId, updatedAt = System.currentTimeMillis()))
            _models.value = ProviderModelsUiState()
        }
    }
}

/** State of the provider models bottom sheet. */
data class ProviderModelsUiState(
    val profileId: String? = null,
    val profileName: String = "",
    val currentModelId: String = "",
    val loading: Boolean = false,
    val models: List<ModelInfo> = emptyList(),
    val error: String? = null
) {
    val visible: Boolean get() = profileId != null
}

data class ProviderEditUiState(
    val profileId: String? = null,
    val displayName: String = "",
    val protocol: ProviderProtocol = ProviderProtocol.OPENAI_COMPATIBLE,
    val baseUrl: String = "",
    val modelId: String = "",
    val apiKeyInput: String = "",
    val hasStoredKey: Boolean = false,
    val showApiKey: Boolean = false,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val testMessage: String? = null,
    val testSuccess: Boolean? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val saved: Boolean = false
) {
    val isNew: Boolean get() = profileId == null
}

@HiltViewModel
class ProviderEditViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val credentialStore: CredentialStore,
    private val providerAdapter: AiProviderAdapter
) : ViewModel() {

    private val _state = MutableStateFlow(ProviderEditUiState())
    val state: StateFlow<ProviderEditUiState> = _state.asStateFlow()

    fun load(profileId: String?) {
        if (profileId == null) {
            _state.value = ProviderEditUiState()
            return
        }
        viewModelScope.launch {
            val profile = providerRepository.getProfile(profileId) ?: return@launch
            val hasKey = credentialStore.hasCredential(profile.id)
            _state.value = ProviderEditUiState(
                profileId = profile.id,
                displayName = profile.displayName,
                protocol = profile.protocol,
                baseUrl = profile.baseUrl,
                modelId = profile.modelId,
                hasStoredKey = hasKey
            )
        }
    }

    fun onDisplayNameChange(v: String) = update { copy(displayName = v) }
    fun onBaseUrlChange(v: String) = update { copy(baseUrl = v) }
    fun onModelIdChange(v: String) = update { copy(modelId = v) }
    fun onApiKeyChange(v: String) = update { copy(apiKeyInput = v) }
    fun toggleApiKeyVisibility() = update { copy(showApiKey = !showApiKey) }
    fun onProtocolChange(p: ProviderProtocol) = update { copy(protocol = p) }

    private fun update(block: ProviderEditUiState.() -> ProviderEditUiState) {
        _state.value = _state.value.block()
    }

    fun validate(): Map<String, String> {
        val s = _state.value
        val errors = LinkedHashMap<String, String>()
        if (s.displayName.isBlank()) errors["displayName"] = "Name is required."
        if (s.modelId.isBlank()) errors["modelId"] = "Model ID is required."
        validateBaseUrl(s.baseUrl)?.let { errors["baseUrl"] = it }
        if (s.isNew && s.apiKeyInput.isBlank()) {
            errors["apiKey"] = "An API key is required for a new profile."
        }
        return errors
    }

    fun testConnection() {
        val errors = validate()
        if (errors.isNotEmpty()) {
            update { copy(fieldErrors = errors) }
            return
        }
        viewModelScope.launch {
            update { copy(testing = true, testMessage = null, testSuccess = null, fieldErrors = emptyMap()) }
            val s = _state.value
            val key = s.apiKeyInput.ifBlank { s.profileId?.let { credentialStore.getApiKey(it) } }
            val probe = ProviderProfile(
                id = s.profileId ?: "probe",
                displayName = s.displayName,
                protocol = s.protocol,
                // Trim exactly as [save] does: otherwise a pasted trailing space makes Test fail
                // against an endpoint the saved profile would reach fine.
                baseUrl = s.baseUrl.trim(),
                modelId = s.modelId.trim(),
                credentialRef = null,
                customHeaders = emptyMap(),
                isDefault = false,
                connectTimeoutMs = 15_000,
                readTimeoutMs = 60_000,
                capabilities = ProviderCapabilities(),
                createdAt = 0,
                updatedAt = 0
            )
            val result = providerAdapter.testConnection(probe, key)
            update {
                copy(
                    testing = false,
                    testMessage = result.message + " (${result.latencyMs} ms)",
                    testSuccess = result.success
                )
            }
        }
    }

    fun save() {
        val errors = validate()
        if (errors.isNotEmpty()) {
            update { copy(fieldErrors = errors) }
            return
        }
        viewModelScope.launch {
            update { copy(saving = true, fieldErrors = emptyMap()) }
            val s = _state.value
            val id = s.profileId ?: UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val existing = s.profileId?.let { providerRepository.getProfile(it) }
            val profile = ProviderProfile(
                id = id,
                displayName = s.displayName.trim(),
                protocol = s.protocol,
                baseUrl = s.baseUrl.trim(),
                modelId = s.modelId.trim(),
                credentialRef = id,
                customHeaders = existing?.customHeaders ?: emptyMap(),
                isDefault = existing?.isDefault ?: false,
                connectTimeoutMs = existing?.connectTimeoutMs ?: 30_000,
                readTimeoutMs = existing?.readTimeoutMs ?: 120_000,
                capabilities = existing?.capabilities ?: ProviderCapabilities(),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
            providerRepository.upsert(profile)
            if (s.apiKeyInput.isNotBlank()) {
                credentialStore.saveApiKey(id, s.apiKeyInput.trim())
            }
            if (s.profileId == null && providerRepository.getDefaultProfile() == null) {
                providerRepository.setDefault(id)
            }
            update { copy(saving = false, saved = true) }
        }
    }

    private fun validateBaseUrl(url: String): String? {
        if (url.isBlank()) return "BASE_URL is required."
        val parsed = runCatching { URL(url) }.getOrNull() ?: return "Enter a valid URL."
        val protocol = parsed.protocol.lowercase()
        if (protocol != "http" && protocol != "https") return "URL must start with http:// or https://."
        if (protocol == "http" && !isLocalHost(parsed.host)) {
            return "Use HTTPS. Plain HTTP is allowed only for localhost."
        }
        return null
    }

    private fun isLocalHost(host: String?): Boolean {
        if (host == null) return false
        return host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" || host.endsWith(".local")
    }
}
