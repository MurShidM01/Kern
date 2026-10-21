package com.aliedu.kern.core.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aliedu.kern.core.designsystem.theme.KernElevation
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing

/**
 * A left slide-in panel (drawer) with a scrim, matching the flat Kern surface system. Emits a scrim
 * and the sliding panel as siblings, so the caller must place it inside a [Box] that fills the screen
 * — the same pattern the Files folder tree used.
 *
 * The panel is not a Material [androidx.compose.material3.NavigationDrawer]; it is a plain bordered
 * surface so it reads as a dense, professional side panel rather than a floating sheet.
 *
 * When [imeAware] is set the panel rises above the soft keyboard instead of being covered by it, so
 * bottom rows (e.g. the terminal's Linux-environment entry) stay reachable while the IME is open.
 */
@Composable
fun KernSidePanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.82f,
    maxWidth: Dp = 340.dp,
    imeAware: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val panelWidth = (LocalConfiguration.current.screenWidthDp * widthFraction)
        .coerceAtMost(maxWidth.value)
        .dp

    if (visible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.36f))
                .clickable(onClick = onDismiss)
        )
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInHorizontally(animationSpec = tween(220)) { -it } + fadeIn(tween(220)),
        exit = slideOutHorizontally(animationSpec = tween(180)) { -it } + fadeOut(tween(180))
    ) {
        Surface(
            modifier = Modifier
                .width(panelWidth)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(KernElevation.hairline, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        if (imeAware) {
                            WindowInsets.ime.union(WindowInsets.navigationBars)
                        } else {
                            WindowInsets.navigationBars
                        }
                    ),
                content = content
            )
        }
    }
}

/** Standard panel header: leading icon, title/subtitle, and a close button. */
@Composable
fun KernSidePanelHeader(
    title: String,
    icon: ImageVector,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = KernSpacing.md, vertical = KernSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(KernSizes.iconMd)
        )
        Spacer(Modifier.width(KernSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (actions != null) {
            actions()
            Spacer(Modifier.width(KernSpacing.xxs))
        }
        KernIconButton(
            icon = com.aliedu.kern.core.designsystem.icons.KernIcons.Close,
            contentDescription = "Close panel",
            onClick = onClose
        )
    }
}
