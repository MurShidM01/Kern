package com.aliedu.kern.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernElevation
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.designsystem.theme.LocalKernSemanticColors

/** Semantic tone shared by status cards and info notes. Always paired with an icon + label. */
enum class KernTone { NEUTRAL, INFO, SUCCESS, WARNING, ERROR }

@Composable
private fun KernTone.container(): Color = when (this) {
    KernTone.NEUTRAL, KernTone.INFO -> MaterialTheme.colorScheme.surfaceVariant
    KernTone.SUCCESS -> LocalKernSemanticColors.current.successContainer
    KernTone.WARNING -> LocalKernSemanticColors.current.warningContainer
    KernTone.ERROR -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun KernTone.accent(): Color = when (this) {
    KernTone.NEUTRAL, KernTone.INFO -> MaterialTheme.colorScheme.primary
    KernTone.SUCCESS -> LocalKernSemanticColors.current.success
    KernTone.WARNING -> LocalKernSemanticColors.current.warning
    KernTone.ERROR -> MaterialTheme.colorScheme.error
}

private fun KernTone.defaultIcon(): ImageVector = when (this) {
    KernTone.SUCCESS -> KernIcons.Check
    KernTone.WARNING -> KernIcons.Warning
    KernTone.ERROR -> KernIcons.Error
    KernTone.NEUTRAL, KernTone.INFO -> KernIcons.Info
}

/**
 * Brand header used at the top of first-run and full-screen surfaces: the Kern mark, the wordmark
 * with an optional subtitle, and a trailing action slot (e.g. "Skip").
 */
@Composable
fun KernBrandHeader(
    modifier: Modifier = Modifier,
    title: String = "Kern",
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(KernSizes.topBarHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KernIconBadge(
            icon = KernIcons.Sparkle,
            container = MaterialTheme.colorScheme.primary,
            tint = MaterialTheme.colorScheme.onPrimary,
            size = 28.dp,
            iconSize = KernSizes.iconSm,
            shape = KernRadius.smShape
        )
        Spacer(Modifier.width(KernSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
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
        actions()
    }
}

/**
 * A titled group of settings rows. The title is the shared uppercase section label; the rows sit in
 * a single flat surface with hairline dividers so a settings screen reads as a small set of blocks
 * rather than a long undifferentiated list.
 */
@Composable
fun KernSettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            KernSectionLabel(text = title, modifier = Modifier.padding(bottom = KernSpacing.xs))
        }
        KernSurface(modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = KernSpacing.xs, start = KernSpacing.xs)
            )
        }
    }
}

/** Hairline divider used between rows inside a [KernSettingsGroup]. */
@Composable
fun KernSettingDivider(modifier: Modifier = Modifier) {
    KernDivider(modifier = modifier.padding(start = KernSpacing.md))
}

/**
 * A single settings row: an icon badge, a title with optional subtitle, and a trailing slot
 * (switch, value, chevron). Clickable when [onClick] is supplied.
 */
@Composable
fun KernSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    iconContainer: Color = MaterialTheme.colorScheme.primaryContainer,
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
            if (icon != null) {
                KernIconBadge(icon = icon, container = iconContainer, tint = iconTint)
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
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
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
        Box(modifier = modifier) { body() }
    }
}

/**
 * Action / choice card with an icon badge, a title + detail and a trailing affordance. The wizard's
 * primary building block, now shared so Home, Files, Settings and the terminal all match. Selecting
 * a card draws a primary hairline instead of a shadow.
 */
@Composable
fun KernOptionCard(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    val border = when {
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = KernRadius.mdShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        tonalElevation = KernElevation.none,
        shadowElevation = KernElevation.none,
        border = BorderStroke(KernElevation.hairline, border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KernSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.md)
        ) {
            KernIconBadge(
                icon = icon,
                container = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primaryContainer,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onPrimaryContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    imageVector = KernIcons.ArrowRight,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(KernSizes.iconSm)
                )
            }
        }
    }
}

/**
 * Tinted status panel: an icon badge, a title and a detail line. Used to confirm that a setup step
 * succeeded (or is still pending) without resorting to a shadow or a bare coloured dot.
 */
@Composable
fun KernStatusCard(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    tone: KernTone = KernTone.NEUTRAL,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = KernRadius.mdShape,
        color = tone.container(),
        tonalElevation = KernElevation.none,
        shadowElevation = KernElevation.none,
        border = BorderStroke(KernElevation.hairline, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KernSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
        ) {
            Icon(
                imageVector = icon ?: tone.defaultIcon(),
                contentDescription = null,
                tint = tone.accent(),
                modifier = Modifier.size(KernSizes.iconMd)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            trailing?.invoke()
        }
    }
}

/**
 * Inline note: a small tinted icon plus a line of supporting copy. The quiet way to explain an
 * optional step or warn about a precondition (PRD: state is never colour-only).
 */
@Composable
fun KernInfoNote(
    text: String,
    modifier: Modifier = Modifier,
    tone: KernTone = KernTone.NEUTRAL,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
    ) {
        Icon(
            imageVector = icon ?: tone.defaultIcon(),
            contentDescription = null,
            tint = tone.accent(),
            modifier = Modifier
                .padding(top = 1.dp)
                .size(KernSizes.iconSm)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (tone == KernTone.ERROR) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Generic segmented control for a small set of mutually exclusive options (theme mode, package tab).
 *
 * Matches Material 3's segmented button exactly: one continuous hairline-outlined bar with a small
 * (8 dp) corner radius on the outer ends only, segments laid out flush and separated by hairline
 * dividers, and the selected segment filled with the theme's secondary container plus an accent
 * outline. Labels are upper-cased and selection reads from colour alone — no shadow, no check icon.
 * Every colour is a theme role, so the control adopts whatever palette is active.
 */
@Composable
fun <T> KernSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = KernRadius.smShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = KernElevation.none,
        border = BorderStroke(KernElevation.hairline, MaterialTheme.colorScheme.outline)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(KernSizes.segmentHeight)) {
            options.forEachIndexed { index, option ->
                val isSelected = option == selected
                // A selected neighbour draws its own outline, so no divider is needed on that edge.
                val previousSelected = index > 0 && options[index - 1] == selected
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (index > 0 && !isSelected && !previousSelected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .width(KernElevation.hairline)
                                .background(MaterialTheme.colorScheme.outline)
                        )
                    }
                    Surface(
                        onClick = { if (enabled) onSelect(option) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxSize(),
                        shape = segmentShape(index, options.size),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                        tonalElevation = KernElevation.none,
                        border = if (isSelected) {
                            BorderStroke(SegmentOutlineWidth, MaterialTheme.colorScheme.primary)
                        } else null
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = label(option).uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = KernSpacing.xs)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Segments are rounded only on the group's outer ends; inner edges stay square so they abut. */
private fun segmentShape(index: Int, count: Int): Shape = when {
    count <= 1 -> KernRadius.smShape
    index == 0 -> RoundedCornerShape(topStart = KernRadius.sm, bottomStart = KernRadius.sm)
    index == count - 1 -> RoundedCornerShape(topEnd = KernRadius.sm, bottomEnd = KernRadius.sm)
    else -> RectangleShape
}

/** Accent outline drawn around the selected segment (slightly heavier than a hairline). */
private val SegmentOutlineWidth = 1.5.dp

/**
 * Colour-scheme swatch for the Settings appearance grid: a small preview of the palette's key
 * colours, a label, and a primary ring + check when selected.
 */
@Composable
fun KernPaletteSwatch(
    label: String,
    preview: List<Color>,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KernSpacing.xs)
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.35f),
            shape = KernRadius.mdShape,
            color = Color.Transparent,
            tonalElevation = KernElevation.none,
            border = BorderStroke(
                width = if (selected) 2.dp else KernElevation.hairline,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Box(modifier = Modifier.fillMaxSize().clip(KernRadius.mdShape)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        SwatchCell(preview.getOrNull(0))
                        SwatchCell(preview.getOrNull(1))
                    }
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        SwatchCell(preview.getOrNull(2))
                        SwatchCell(preview.getOrNull(3))
                    }
                }
                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(20.dp)
                            .clip(KernRadius.pillShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = KernIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(KernSizes.iconXs)
                        )
                    }
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RowScope.SwatchCell(color: Color?) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize()
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
    )
}
