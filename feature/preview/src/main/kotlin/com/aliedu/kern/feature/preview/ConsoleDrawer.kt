package com.aliedu.kern.feature.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliedu.kern.core.designsystem.components.KernClickableSurface
import com.aliedu.kern.core.designsystem.components.KernDivider
import com.aliedu.kern.core.designsystem.components.KernIconButton
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.designsystem.theme.LocalKernSemanticColors
import com.aliedu.kern.core.designsystem.theme.kernCodeTextStyle
import com.aliedu.kern.core.tools.preview.ConsoleEntry
import com.aliedu.kern.core.tools.preview.ConsoleLevel

/**
 * The browser-devtools console (PRD §4.3 bullet 8).
 *
 * Shows what the page logged and what it threw, with the level filters and a clear button — the
 * parts of F12 that matter on a phone. The list is pinned to the newest entry, because a dev server
 * that logs on every request would otherwise leave the user reading the top of the log.
 */
@Composable
fun ConsoleDrawer(
    entries: List<ConsoleEntry>,
    levels: Set<ConsoleLevel>,
    onToggleLevel: (ConsoleLevel) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp, max = 340.dp)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        KernDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = KernSpacing.md, end = KernSpacing.xs, top = KernSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
        ) {
            Text(
                text = "Console",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (entries.isEmpty()) "no output" else "${entries.size} ${if (entries.size == 1) "line" else "lines"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(modifier = Modifier.weight(1f))
            KernIconButton(
                icon = KernIcons.Clear,
                contentDescription = "Clear console",
                onClick = onClear,
                enabled = entries.isNotEmpty()
            )
            KernIconButton(
                icon = KernIcons.Close,
                contentDescription = "Hide console",
                onClick = onClose
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KernSpacing.md, vertical = KernSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.xs)
        ) {
            ConsoleLevel.entries.forEach { level ->
                LevelChip(
                    level = level,
                    selected = level in levels,
                    onClick = { onToggleLevel(level) }
                )
            }
        }

        KernDivider()

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nothing logged yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        val listState = rememberLazyListState()
        LaunchedEffect(entries.size) {
            if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(horizontal = KernSpacing.md, vertical = KernSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(KernSpacing.xs)
        ) {
            items(entries, key = { it.id }) { entry -> ConsoleRow(entry) }
        }
    }
}

/** One console line: a level dot, the message, and where it came from. */
@Composable
private fun ConsoleRow(entry: ConsoleEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(6.dp)
                    .background(levelColor(entry.level), CircleShape)
            )
            Text(
                text = entry.message,
                style = kernCodeTextStyle(11f),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        // The page URL is often just the origin, so only keep a filename when there is one.
        val origin = consoleOrigin(entry.source, entry.line)
        if (origin.isNotBlank()) {
            Text(
                text = origin,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp)
            )
        }
    }
}

/**
 * Where a console line came from, as `file:line`. The browser reports the full script URL, and for
 * an inline script that is just the page origin — showing `:10` with nothing in front of it, or
 * `127.0.0.1:8000:10`, would be noise, so the file is dropped when the URL names no file.
 */
internal fun consoleOrigin(source: String?, line: Int): String = listOfNotNull(
    source?.let(::scriptFileName),
    line.takeIf { it > 0 }?.toString()
).joinToString(":")

/** The script's file name, or null when [source] names no file (an origin, or nothing at all). */
private fun scriptFileName(source: String): String? {
    // Drop the scheme and host first, so `http://host:8000` does not yield `host:8000`.
    val path = if (source.contains("://")) {
        source.substringAfter("://").substringAfter('/', "")
    } else {
        source
    }
    return path.substringAfterLast('/').takeIf { it.isNotBlank() }
}

/** A flat level toggle. Selected means the level is shown, exactly like a browser's console. */
@Composable
private fun LevelChip(level: ConsoleLevel, selected: Boolean, onClick: () -> Unit) {
    KernClickableSurface(
        onClick = onClick,
        shape = KernRadius.xsShape,
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.height(KernSizes.composerChipHeight)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = KernSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.xs)
        ) {
            Box(modifier = Modifier.size(6.dp).background(levelColor(level), CircleShape))
            Text(
                text = level.name,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
private fun levelColor(level: ConsoleLevel): Color = when (level) {
    ConsoleLevel.ERROR -> MaterialTheme.colorScheme.error
    ConsoleLevel.WARN -> LocalKernSemanticColors.current.warning
    ConsoleLevel.INFO -> MaterialTheme.colorScheme.primary
    ConsoleLevel.LOG, ConsoleLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
}
