package com.aliedu.kern.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Screen container shared by every destination.
 *
 * System insets are handled by the individual bars (the top bar paints the status bar, the app
 * shell paints the navigation bar), so this scaffold consumes no window insets itself. That keeps
 * the layout identical across screens and prevents the double-padding that nested scaffolds cause.
 *
 * The one exception is the FAB: it is a fixed control pinned to the bottom, so it lifts itself above
 * the navigation bar — otherwise the bar covers it on screens that show no bottom bar.
 */
@Composable
fun KernScaffold(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: (@Composable () -> Unit)? = null,
    floatingActionButton: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = topBar,
        bottomBar = { bottomBar?.invoke() },
        floatingActionButton = {
            if (floatingActionButton != null) {
                Box(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                    floatingActionButton()
                }
            }
        },
        content = content
    )
}
