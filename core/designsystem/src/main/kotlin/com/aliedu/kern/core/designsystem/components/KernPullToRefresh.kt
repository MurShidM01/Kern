package com.aliedu.kern.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The pull-to-refresh container used across every screen.
 *
 * This is Material 3's stock pull-to-refresh — the same gesture, threshold and indicator animation as
 * the platform — with the container and spinner colours themed. It is deliberately not customised:
 * an earlier hand-drawn indicator made the gesture feel unreliable (a long pull was needed before
 * anything appeared), so the stock behaviour is the contract.
 *
 * [content] must be scrollable (a `LazyColumn`, or a `Column` with `verticalScroll`) for the
 * gesture to reach this container; [KernRefreshableState] provides that for centred state views.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KernPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                color = MaterialTheme.colorScheme.primary
            )
        },
        content = content
    )
}

/**
 * Hosts a centred state view (empty / error / loading) inside a [KernPullToRefresh].
 *
 * A plain centred `Column` is not scrollable, so a pull gesture over it would never reach the refresh
 * container. This wraps the content in a scrollable, viewport-tall box so the gesture still works.
 */
@Composable
fun KernRefreshableState(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight),
                content = content
            )
        }
    }
}
