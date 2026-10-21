package com.aliedu.kern.core.designsystem.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.aliedu.kern.core.designsystem.theme.KernTheme
import com.aliedu.kern.core.model.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UI tests for the shared state views (PRD 18.3: user-visible loading/empty/error states exist).
 * Runs on the JVM through Robolectric so it needs no device.
 */
@RunWith(RobolectricTestRunner::class)
class StateViewsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        KernTheme(themeMode = ThemeMode.LIGHT) { content() }
    }

    @Test
    fun `empty state shows its title and message`() {
        composeRule.setContent {
            Host {
                EmptyState(title = "No projects yet", message = "Open a folder to get started.")
            }
        }
        composeRule.onNodeWithText("No projects yet").assertIsDisplayed()
        composeRule.onNodeWithText("Open a folder to get started.").assertIsDisplayed()
    }

    @Test
    fun `error state shows its message`() {
        composeRule.setContent {
            Host { ErrorState(message = "Could not reach the provider.") }
        }
        composeRule.onNodeWithText("Could not reach the provider.").assertIsDisplayed()
    }

    @Test
    fun `loading state shows its label`() {
        composeRule.setContent {
            Host { LoadingState(label = "Loading Kern…") }
        }
        composeRule.onNodeWithText("Loading Kern…").assertIsDisplayed()
    }

    @Test
    fun `empty state renders a trailing action`() {
        composeRule.setContent {
            Host {
                EmptyState(
                    title = "No files",
                    message = "Create one to begin.",
                    action = { Text("New file") }
                )
            }
        }
        composeRule.onNodeWithText("New file").assertIsDisplayed()
    }
}
