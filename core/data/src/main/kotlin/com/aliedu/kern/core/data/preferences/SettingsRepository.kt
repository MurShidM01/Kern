package com.aliedu.kern.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aliedu.kern.core.model.PermissionMode
import com.aliedu.kern.core.model.TerminalTheme
import com.aliedu.kern.core.model.ThemeMode
import com.aliedu.kern.core.model.ThemePalette
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.kernSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "kern_settings"
)

/**
 * Lightweight app settings that are safe to store in plaintext (never secrets — PRD 14.1).
 * Theme mode persistence backs P1-03 / FR-002.
 */
interface SettingsRepository {
    val themeMode: Flow<ThemeMode>
    val themePalette: Flow<ThemePalette>
    val dynamicColor: Flow<Boolean>
    val editorFontSizeSp: Flow<Float>
    val chatFontSizeSp: Flow<Float>
    val terminalFontSizeSp: Flow<Float>
    val terminalTheme: Flow<TerminalTheme>
    val terminalScrollback: Flow<Int>
    val showReasoning: Flow<Boolean>
    val autoScroll: Flow<Boolean>
    /** Whether the workspace's memory notes are injected into a turn at all. */
    val memoryEnabled: Flow<Boolean>
    val permissionMode: Flow<PermissionMode>
    val lastWorkspaceId: Flow<String?>
    val onboardingCompleted: Flow<Boolean>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setThemePalette(palette: ThemePalette)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setEditorFontSizeSp(sizeSp: Float)
    suspend fun setChatFontSizeSp(sizeSp: Float)
    suspend fun setTerminalFontSizeSp(sizeSp: Float)
    suspend fun setTerminalTheme(theme: TerminalTheme)
    suspend fun setTerminalScrollback(lines: Int)
    suspend fun setShowReasoning(enabled: Boolean)
    suspend fun setAutoScroll(enabled: Boolean)
    suspend fun setMemoryEnabled(enabled: Boolean)
    suspend fun setPermissionMode(mode: PermissionMode)
    suspend fun setLastWorkspaceId(id: String?)
    suspend fun setOnboardingCompleted(completed: Boolean)
}

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private val store = context.kernSettingsDataStore

    override val themeMode: Flow<ThemeMode> = store.data.map { prefs ->
        val raw = prefs[KEY_THEME_MODE]
        runCatching { ThemeMode.valueOf(raw ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    override val dynamicColor: Flow<Boolean> = store.data.map { it[KEY_DYNAMIC_COLOR] ?: false }

    override val themePalette: Flow<ThemePalette> = store.data.map { prefs ->
        val raw = prefs[KEY_THEME_PALETTE]
        runCatching { ThemePalette.valueOf(raw ?: ThemePalette.INDIGO.name) }
            .getOrDefault(ThemePalette.INDIGO)
    }

    override val editorFontSizeSp: Flow<Float> = store.data.map { it[KEY_EDITOR_FONT] ?: DEFAULT_FONT }

    override val chatFontSizeSp: Flow<Float> = store.data.map { it[KEY_CHAT_FONT] ?: DEFAULT_CHAT_FONT }

    override val terminalFontSizeSp: Flow<Float> =
        store.data.map { it[KEY_TERMINAL_FONT] ?: DEFAULT_TERMINAL_FONT }

    override val terminalScrollback: Flow<Int> =
        store.data.map { it[KEY_TERMINAL_SCROLLBACK] ?: DEFAULT_SCROLLBACK }

    override val terminalTheme: Flow<TerminalTheme> = store.data.map { prefs ->
        val raw = prefs[KEY_TERMINAL_THEME]
        runCatching { TerminalTheme.valueOf(raw ?: TerminalTheme.FOLLOW_APP.name) }
            .getOrDefault(TerminalTheme.FOLLOW_APP)
    }

    override val showReasoning: Flow<Boolean> = store.data.map { it[KEY_SHOW_REASONING] ?: true }

    override val autoScroll: Flow<Boolean> = store.data.map { it[KEY_AUTO_SCROLL] ?: true }

    override val memoryEnabled: Flow<Boolean> = store.data.map { it[KEY_MEMORY] ?: true }

    override val permissionMode: Flow<PermissionMode> = store.data.map { prefs ->
        val raw = prefs[KEY_PERMISSION_MODE]
        runCatching { PermissionMode.valueOf(raw ?: PermissionMode.ASK_CHANGES.name) }
            .getOrDefault(PermissionMode.ASK_CHANGES)
    }

    override val lastWorkspaceId: Flow<String?> = store.data.map { it[KEY_LAST_WORKSPACE] }

    override val onboardingCompleted: Flow<Boolean> =
        store.data.map { it[KEY_ONBOARDING] ?: false }

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[KEY_THEME_MODE] = mode.name }
    }

    override suspend fun setThemePalette(palette: ThemePalette) {
        store.edit { it[KEY_THEME_PALETTE] = palette.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setEditorFontSizeSp(sizeSp: Float) {
        store.edit { it[KEY_EDITOR_FONT] = sizeSp.coerceIn(MIN_FONT, MAX_FONT) }
    }

    override suspend fun setChatFontSizeSp(sizeSp: Float) {
        store.edit { it[KEY_CHAT_FONT] = sizeSp.coerceIn(MIN_FONT, MAX_FONT) }
    }

    override suspend fun setTerminalFontSizeSp(sizeSp: Float) {
        store.edit { it[KEY_TERMINAL_FONT] = sizeSp.coerceIn(MIN_FONT, MAX_FONT) }
    }

    override suspend fun setTerminalScrollback(lines: Int) {
        store.edit { it[KEY_TERMINAL_SCROLLBACK] = lines.coerceIn(MIN_SCROLLBACK, MAX_SCROLLBACK) }
    }

    override suspend fun setTerminalTheme(theme: TerminalTheme) {
        store.edit { it[KEY_TERMINAL_THEME] = theme.name }
    }

    override suspend fun setShowReasoning(enabled: Boolean) {
        store.edit { it[KEY_SHOW_REASONING] = enabled }
    }

    override suspend fun setAutoScroll(enabled: Boolean) {
        store.edit { it[KEY_AUTO_SCROLL] = enabled }
    }

    override suspend fun setMemoryEnabled(enabled: Boolean) {
        store.edit { it[KEY_MEMORY] = enabled }
    }

    override suspend fun setPermissionMode(mode: PermissionMode) {
        store.edit { it[KEY_PERMISSION_MODE] = mode.name }
    }

    override suspend fun setLastWorkspaceId(id: String?) {
        store.edit { prefs ->
            if (id == null) prefs.remove(KEY_LAST_WORKSPACE) else prefs[KEY_LAST_WORKSPACE] = id
        }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        store.edit { it[KEY_ONBOARDING] = completed }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_THEME_PALETTE = stringPreferencesKey("theme_palette")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_EDITOR_FONT = floatPreferencesKey("editor_font_size_sp")
        val KEY_CHAT_FONT = floatPreferencesKey("chat_font_size_sp")
        val KEY_TERMINAL_FONT = floatPreferencesKey("terminal_font_size_sp")
        val KEY_TERMINAL_THEME = stringPreferencesKey("terminal_theme")
        val KEY_TERMINAL_SCROLLBACK = intPreferencesKey("terminal_scrollback")
        val KEY_SHOW_REASONING = booleanPreferencesKey("show_reasoning")
        val KEY_AUTO_SCROLL = booleanPreferencesKey("auto_scroll")
        val KEY_MEMORY = booleanPreferencesKey("workspace_memory")
        val KEY_PERMISSION_MODE = stringPreferencesKey("permission_mode")
        val KEY_LAST_WORKSPACE = stringPreferencesKey("last_workspace_id")
        val KEY_ONBOARDING = booleanPreferencesKey("onboarding_completed")
        const val DEFAULT_FONT = 13f
        const val DEFAULT_CHAT_FONT = 12f
        const val DEFAULT_TERMINAL_FONT = 12f
        const val DEFAULT_SCROLLBACK = 1000
        const val MIN_FONT = 10f
        const val MAX_FONT = 22f
        const val MIN_SCROLLBACK = 200
        const val MAX_SCROLLBACK = 10_000
    }
}
