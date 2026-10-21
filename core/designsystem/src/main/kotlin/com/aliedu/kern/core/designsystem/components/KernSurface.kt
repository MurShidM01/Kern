package com.aliedu.kern.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliedu.kern.core.designsystem.theme.KernElevation
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing

/**
 * Flat surface with an optional hairline border. Kern uses no elevation — grouping is expressed by
 * the container colour and a subtle border, never a shadow.
 */
@Composable
fun KernSurface(
    modifier: Modifier = Modifier,
    shape: Shape = KernRadius.mdShape,
    color: Color = MaterialTheme.colorScheme.surface,
    bordered: Boolean = true,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        tonalElevation = KernElevation.none,
        shadowElevation = KernElevation.none,
        border = if (bordered) {
            BorderStroke(KernElevation.hairline, MaterialTheme.colorScheme.outlineVariant)
        } else null,
        content = content
    )
}

/** Flat, clickable surface used for list rows. */
@Composable
fun KernClickableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = KernRadius.mdShape,
    color: Color = MaterialTheme.colorScheme.surface,
    bordered: Boolean = true,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        color = color,
        tonalElevation = KernElevation.none,
        shadowElevation = KernElevation.none,
        border = if (bordered) {
            BorderStroke(KernElevation.hairline, MaterialTheme.colorScheme.outlineVariant)
        } else null,
        content = content
    )
}

/** Hairline divider that matches the flat surface system. */
@Composable
fun KernDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = KernElevation.hairline,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/** Uppercase section label (PRD 6.4 label token). */
@Composable
fun KernSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * Standard dense list row: optional leading icon, a title with optional subtitle, and a trailing
 * slot. Used for workspaces, files, sessions, providers — so every list looks the same.
 */
@Composable
fun KernListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: ImageVector? = null,
    leadingTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KernSpacing.md, vertical = KernSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.md)
        ) {
            if (leading != null) {
                Icon(
                    imageVector = leading,
                    contentDescription = null,
                    tint = leadingTint,
                    modifier = Modifier.size(KernSizes.iconMd)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.size(1.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            trailing?.invoke()
        }
    }

    if (onClick != null) {
        KernClickableSurface(onClick = onClick, modifier = modifier, content = body)
    } else {
        KernSurface(modifier = modifier, content = body)
    }
}
