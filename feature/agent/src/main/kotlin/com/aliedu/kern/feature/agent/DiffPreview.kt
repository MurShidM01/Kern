package com.aliedu.kern.feature.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aliedu.kern.core.designsystem.components.KernTextButton
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.designsystem.theme.LocalKernSemanticColors

/** What a line of a unified diff is, which decides how it is drawn. */
internal enum class DiffLineKind { ADDED, REMOVED, CONTEXT, HUNK, HEADER }

/** One rendered line of a diff. [text] keeps its leading marker, so copy/paste stays a diff. */
internal data class DiffRow(val kind: DiffLineKind, val text: String)

/**
 * Splits a unified diff into typed lines.
 *
 * The `---`/`+++` file header is classified before the +/- cases, because it starts with the same
 * characters: treating it as a removed/added line painted the file name green and red, which read
 * as a change that was never made. `\ No newline at end of file` is a marker, not a line of code.
 */
internal fun parseUnifiedDiff(diff: String): List<DiffRow> =
    diff.lineSequence().map { line ->
        val kind = when {
            line.startsWith("--- ") || line.startsWith("+++ ") -> DiffLineKind.HEADER
            line.startsWith("@@") -> DiffLineKind.HUNK
            line.startsWith("+") -> DiffLineKind.ADDED
            line.startsWith("-") -> DiffLineKind.REMOVED
            else -> DiffLineKind.CONTEXT
        }
        DiffRow(kind, line)
    }.toList()

/**
 * A reviewable diff: added lines on a green band, removed lines on a red one, hunk headers muted
 * between them — the shape every code review tool uses, so a change can be read at a glance.
 *
 * Only the first [collapsedRows] lines are painted until the user asks for the rest: an edit to a
 * large generated file can produce thousands of lines, and the approval sheet has to stay usable.
 */
@Composable
internal fun DiffPreview(
    diff: String,
    modifier: Modifier = Modifier,
    collapsedRows: Int = 160
) {
    val semantic = LocalKernSemanticColors.current
    val rows = remember(diff) { parseUnifiedDiff(diff) }
    var showAll by remember(diff) { mutableStateOf(false) }
    val visible = if (showAll) rows else rows.take(collapsedRows)
    val hidden = rows.size - visible.size

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .background(semantic.codeBackground, KernRadius.smShape)
                .horizontalScroll(rememberScrollState())
                .padding(vertical = KernSpacing.xxs)
        ) {
            visible.forEach { row ->
                val background = when (row.kind) {
                    DiffLineKind.ADDED -> semantic.diffAdd
                    DiffLineKind.REMOVED -> semantic.diffRemove
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
                val color = when (row.kind) {
                    DiffLineKind.HUNK -> MaterialTheme.colorScheme.primary
                    DiffLineKind.HEADER -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> semantic.codeText
                }
                Text(
                    text = row.text.ifEmpty { " " },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (row.kind == DiffLineKind.HUNK) FontWeight.Medium
                        else FontWeight.Normal
                    ),
                    color = color,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(background)
                        .padding(horizontal = KernSpacing.sm)
                )
            }
        }
        if (hidden > 0) {
            KernTextButton(onClick = { showAll = true }) {
                Text("Show " + hidden + " more line(s)")
            }
        }
    }
}
