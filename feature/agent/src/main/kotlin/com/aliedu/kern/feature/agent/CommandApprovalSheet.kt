package com.aliedu.kern.feature.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aliedu.kern.core.designsystem.components.KernButton
import com.aliedu.kern.core.designsystem.components.KernIconBadge
import com.aliedu.kern.core.designsystem.components.KernInfoNote
import com.aliedu.kern.core.designsystem.components.KernOutlinedButton
import com.aliedu.kern.core.designsystem.components.KernStatusCard
import com.aliedu.kern.core.designsystem.components.KernTone
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernElevation
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.designsystem.theme.LocalKernSemanticColors
import com.aliedu.kern.core.tools.CommandImpact
import com.aliedu.kern.core.tools.CommandRequest
import com.aliedu.kern.core.tools.OutputPolicy

/**
 * Approval sheet for a proposed shell command (PRD FR-017). Shows the exact command, where it runs,
 * its impact, the timeout and how output is used, so the user can make an informed decision.
 */
@Composable
fun CommandApprovalSheetContent(
    request: CommandRequest,
    reason: String?,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val semantic = LocalKernSemanticColors.current
    val impactTone = when (request.impact) {
        CommandImpact.READ_ONLY -> KernTone.NEUTRAL
        CommandImpact.MUTATING -> KernTone.WARNING
        CommandImpact.DESTRUCTIVE -> KernTone.ERROR
        CommandImpact.NETWORK -> KernTone.INFO
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(KernSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(KernSpacing.md)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.md)
        ) {
            KernIconBadge(
                icon = KernIcons.Terminal,
                container = impactContainer(request.impact),
                tint = impactAccent(request.impact)
            )
            Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.xxs)) {
                Text(
                    "Run command",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    request.cwd.ifBlank { "workspace root" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ImpactBadge(request.impact)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(semantic.codeBackground, KernRadius.smShape)
                .horizontalScroll(rememberScrollState())
                .padding(KernSpacing.sm)
        ) {
            Text(
                text = "$ ${request.command}",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = semantic.codeText
            )
        }

        Surface(
            shape = KernRadius.mdShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(KernElevation.hairline, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(KernSpacing.md)) {
                DetailRow("Working directory", request.cwd.ifBlank { "workspace root" })
                DetailRow("Timeout", "None — runs until it exits")
                DetailRow(
                    "Output",
                    if (request.outputPolicy == OutputPolicy.CAPTURE) "Captured and shared with the model"
                    else "Shown to you only"
                )
            }
        }

        reason?.let {
            KernStatusCard(
                title = "Why the agent wants this",
                detail = it,
                tone = KernTone.INFO,
                icon = KernIcons.Reasoning
            )
        }

        if (request.impact == CommandImpact.DESTRUCTIVE) {
            KernInfoNote(
                text = "This command is classified destructive and can delete or overwrite data. " +
                    "It will never run without your approval.",
                tone = KernTone.ERROR
            )
        } else if (request.impact == CommandImpact.MUTATING) {
            KernInfoNote(
                text = "This command changes files in your workspace.",
                tone = impactTone
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)) {
            KernOutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("Reject") }
            KernButton(
                onClick = onApprove,
                modifier = Modifier.weight(1f),
                colors = if (request.impact == CommandImpact.DESTRUCTIVE) {
                    androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    androidx.compose.material3.ButtonDefaults.buttonColors()
                }
            ) {
                Text(if (request.impact == CommandImpact.DESTRUCTIVE) "Run anyway" else "Run")
            }
        }
    }
}

@Composable
private fun impactAccent(impact: CommandImpact): Color = when (impact) {
    CommandImpact.READ_ONLY -> MaterialTheme.colorScheme.onSurfaceVariant
    CommandImpact.MUTATING -> LocalKernSemanticColors.current.warning
    CommandImpact.DESTRUCTIVE -> MaterialTheme.colorScheme.error
    CommandImpact.NETWORK -> MaterialTheme.colorScheme.primary
}

@Composable
private fun impactContainer(impact: CommandImpact): Color = when (impact) {
    CommandImpact.READ_ONLY -> MaterialTheme.colorScheme.surfaceVariant
    CommandImpact.MUTATING -> LocalKernSemanticColors.current.warningContainer
    CommandImpact.DESTRUCTIVE -> MaterialTheme.colorScheme.errorContainer
    CommandImpact.NETWORK -> MaterialTheme.colorScheme.primaryContainer
}

@Composable
private fun ImpactBadge(impact: CommandImpact) {
    val (label, color) = when (impact) {
        CommandImpact.READ_ONLY -> "Read-only" to MaterialTheme.colorScheme.onSurfaceVariant
        CommandImpact.MUTATING -> "Mutating" to LocalKernSemanticColors.current.warning
        CommandImpact.DESTRUCTIVE -> "Destructive" to MaterialTheme.colorScheme.error
        CommandImpact.NETWORK -> "Network" to MaterialTheme.colorScheme.primary
    }
    Surface(shape = KernRadius.pillShape, color = color.copy(alpha = 0.14f)) {
        Row(
            modifier = Modifier.padding(horizontal = KernSpacing.sm, vertical = KernSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.xs)
        ) {
            Icon(
                imageVector = when (impact) {
                    CommandImpact.READ_ONLY -> KernIcons.Privacy
                    CommandImpact.MUTATING -> KernIcons.Warning
                    CommandImpact.DESTRUCTIVE -> KernIcons.Error
                    CommandImpact.NETWORK -> KernIcons.Globe
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(KernSizes.iconXs)
            )
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = KernSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
