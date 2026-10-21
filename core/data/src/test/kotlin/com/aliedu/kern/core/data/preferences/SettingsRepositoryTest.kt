package com.aliedu.kern.core.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aliedu.kern.core.model.PermissionMode
import com.aliedu.kern.core.model.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Backs P1-03 / T1-11 / FR-002: the theme choice and related settings survive a restart because
 * they are written to DataStore, not held only in memory.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private fun repo(): DataStoreSettingsRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return DataStoreSettingsRepository(context)
    }

    @Test
    fun `theme mode round-trips through persistent storage`() = runBlocking {
        val repository = repo()

        // Fresh install defaults to following the system.
        assertThat(repository.themeMode.first()).isEqualTo(ThemeMode.SYSTEM)

        repository.setThemeMode(ThemeMode.DARK)
        assertThat(repository.themeMode.first()).isEqualTo(ThemeMode.DARK)

        repository.setThemeMode(ThemeMode.LIGHT)
        assertThat(repository.themeMode.first()).isEqualTo(ThemeMode.LIGHT)

        // A brand-new repository over the same store sees the persisted value (restart semantics).
        val reopened = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext())
        assertThat(reopened.themeMode.first()).isEqualTo(ThemeMode.LIGHT)
    }

    @Test
    fun `dynamic color and font size persist and font size is clamped`() = runBlocking {
        val repository = repo()

        repository.setDynamicColor(true)
        assertThat(repository.dynamicColor.first()).isTrue()

        repository.setEditorFontSizeSp(14f)
        assertThat(repository.editorFontSizeSp.first()).isEqualTo(14f)

        repository.setEditorFontSizeSp(999f)
        assertThat(repository.editorFontSizeSp.first()).isAtMost(22f)
    }

    @Test
    fun `last workspace id and onboarding flag persist`() = runBlocking {
        val repository = repo()

        repository.setLastWorkspaceId("ws-123")
        assertThat(repository.lastWorkspaceId.first()).isEqualTo("ws-123")

        repository.setOnboardingCompleted(true)
        assertThat(repository.onboardingCompleted.first()).isTrue()

        repository.setLastWorkspaceId(null)
        assertThat(repository.lastWorkspaceId.first()).isNull()
    }

    @Test
    fun `permission mode and chat preferences persist`() = runBlocking {
        val repository = repo()

        // Defaults are safe: ask before changes, show reasoning, auto-scroll on.
        assertThat(repository.permissionMode.first()).isEqualTo(PermissionMode.ASK_CHANGES)
        assertThat(repository.showReasoning.first()).isTrue()
        assertThat(repository.autoScroll.first()).isTrue()

        repository.setPermissionMode(PermissionMode.FULL_ACCESS)
        assertThat(repository.permissionMode.first()).isEqualTo(PermissionMode.FULL_ACCESS)

        repository.setShowReasoning(false)
        assertThat(repository.showReasoning.first()).isFalse()

        repository.setAutoScroll(false)
        assertThat(repository.autoScroll.first()).isFalse()

        // Restart semantics: a new repository over the same store sees the persisted values.
        val reopened = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext())
        assertThat(reopened.permissionMode.first()).isEqualTo(PermissionMode.FULL_ACCESS)
    }

    @Test
    fun `terminal font size and scrollback persist and are clamped`() = runBlocking {
        val repository = repo()

        // Fresh install: 12 sp text, 1000 lines of scrollback.
        assertThat(repository.terminalFontSizeSp.first()).isEqualTo(12f)
        assertThat(repository.terminalScrollback.first()).isEqualTo(1000)

        repository.setTerminalFontSizeSp(16f)
        assertThat(repository.terminalFontSizeSp.first()).isEqualTo(16f)

        repository.setTerminalScrollback(5000)
        assertThat(repository.terminalScrollback.first()).isEqualTo(5000)

        // Out-of-range values are clamped to the supported window.
        repository.setTerminalFontSizeSp(999f)
        assertThat(repository.terminalFontSizeSp.first()).isAtMost(22f)

        repository.setTerminalScrollback(5)
        assertThat(repository.terminalScrollback.first()).isAtLeast(200)

        repository.setTerminalScrollback(999_999)
        assertThat(repository.terminalScrollback.first()).isAtMost(10_000)

        // Restart semantics: a new repository over the same store sees the persisted values.
        val reopened = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext())
        assertThat(reopened.terminalFontSizeSp.first()).isAtMost(22f)
        assertThat(reopened.terminalScrollback.first()).isAtMost(10_000)
    }
}
