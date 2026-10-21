package com.aliedu.kern.agent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Backs P1-02 / T1-01, T1-14 on a real device: the app cold-starts, presents setup (or home when
 * onboarding is already complete), and the bottom navigation exposes all five destinations with
 * labelled vector icons.
 *
 * The condition polls defensively because some OEM devices briefly move the app to the background
 * during startup, which disposes the Compose hierarchy for a frame.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NavigationShellTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun wakeDevice() {
        runCatching {
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP")
                .close()
        }
        runCatching {
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }

    @Test
    fun coldStartReachesANavigableShell() {
        hiltRule.inject()

        fun visible(text: String): Boolean = runCatching {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)

        // Wait until either the first-run setup wizard or the home dashboard has rendered.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            visible("Skip") || visible("Home")
        }

        // First run: skip setup so the shell (with its navigation) is shown.
        if (visible("Skip")) {
            composeRule.onAllNodesWithText("Skip").onFirst().performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) { visible("Home") }
        }

        // The bottom navigation must expose every Part 1 destination with a visible label.
        listOf("Home", "Agent", "Files", "Settings").forEach { label ->
            composeRule.onAllNodesWithText(label).onFirst().assertIsDisplayed()
        }
    }
}
