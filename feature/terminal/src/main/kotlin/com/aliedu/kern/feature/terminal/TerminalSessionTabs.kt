package com.aliedu.kern.feature.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.aliedu.kern.core.designsystem.components.KernClickableSurface
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSpacing

/**
 * Flat, colour-only session tabs (same pattern as the settings theme selector — no pill indicator).
 * A dead session is shown muted so the state is conveyed by more than colour (PRD 6.3).
 */
@Composable
fun TerminalSessionTabs(
    sessions: List<TerminalSessionUi>,
    activeId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = KernSpacing.screenH, vertical = KernSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(KernSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        sessions.forEach { session ->
            val selected = session.id == activeId
            KernClickableSurface(
                onClick = { onSelect(session.id) },
                shape = KernRadius.smShape,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = session.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                        !session.alive -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = KernSpacing.md, vertical = KernSpacing.sm)
                )
            }
        }
    }
}
