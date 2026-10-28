package com.aliedu.kern.feature.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliedu.kern.core.designsystem.components.drawHeartHandle
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import kotlinx.coroutines.withTimeoutOrNull
import com.aliedu.kern.core.designsystem.theme.kernCodeTextStyle
import com.aliedu.kern.core.terminal.engine.model.ScreenSnapshot

/**
 * Renders a [ScreenSnapshot] as a grid of monospace rows on the code-background surface. The cell grid
 * is measured from the available space so the emulator's column/row count always matches the view.
 *
 * Gestures: a tap focuses the input (raises the keyboard), a vertical drag scrolls through history, a
 * long-press starts a text selection that a following drag extends, and a mouse wheel scrolls like a
 * desktop terminal. There is no custom scroll chrome — history is reached purely by scrolling.
 */
@Composable
fun TerminalRenderer(
    snapshot: ScreenSnapshot,
    fontSizeSp: Float,
    appearance: TerminalAppearance,
    modifier: Modifier = Modifier,
    selection: TerminalSelection? = null,
    onResize: (columns: Int, rows: Int) -> Unit = { _, _ -> },
    onScroll: (delta: Int) -> Unit = {},
    onTap: () -> Unit = {},
    onSelectionStart: (CellPos) -> Unit = {},
    onSelectionExtend: (CellPos) -> Unit = {}
) {
    val background = appearance.background
    val defaultFg = appearance.foreground
    val base = appearance.ansi
    val cursorColor = appearance.cursor
    val selectionColor = appearance.selection
    val textStyle = remember(fontSizeSp) { kernCodeTextStyle(fontSizeSp) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(background)
    ) {
        val density = LocalDensity.current
        // Measure a real row instead of assuming fontSize x 1.5: the code style's line height and
        // the font's own metrics differ from that guess, which made the grid shorter than the space
        // it was drawn in and left a blank band under the prompt.
        val measurer = rememberTextMeasurer()
        val cellWidthPx = remember(fontSizeSp, measurer) {
            val probe = measurer.measure(
                text = AnnotatedString("M".repeat(10)),
                style = textStyle,
                maxLines = 1,
                softWrap = false
            )
            probe.size.width / 10f
        }.coerceAtLeast(1f)
        val cellHeightPx = remember(fontSizeSp, measurer) {
            measurer.measure(
                text = AnnotatedString("M"),
                style = textStyle,
                maxLines = 1,
                softWrap = false
            ).size.height.toFloat()
        }.coerceAtLeast(1f)

        val availableWidthPx = with(density) { maxWidth.toPx() }
        val availableHeightPx = with(density) { maxHeight.toPx() }
        val columns = (availableWidthPx / cellWidthPx).toInt().coerceAtLeast(1)
        val rows = (availableHeightPx / cellHeightPx).toInt().coerceAtLeast(1)

        LaunchedEffect(columns, rows) { onResize(columns, rows) }

        // The screen is laid out top-to-bottom, like a real terminal: line 1 at the top, the prompt
        // where the shell left it, and empty rows below. Anchoring it to the bottom made short output
        // float up from the key row, which reads as the terminal being upside down.
        val cellAt: (Offset) -> CellPos = { offset ->
            val col = (offset.x / cellWidthPx).toInt().coerceIn(0, columns - 1)
            val row = (offset.y / cellHeightPx).toInt().coerceIn(0, rows - 1)
            CellPos(row, col)
        }

        var wheelAccumulator by remember { mutableFloatStateOf(0f) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = KernSpacing.xs, vertical = KernSpacing.xxs)
                // Touch: tap to focus, drag to scroll history, long-press to select.
                .pointerInput(cellWidthPx, cellHeightPx, columns, rows) {
                    val touchSlop = viewConfiguration.touchSlop
                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Race the long-press timer against movement. Selection may only start on a
                        // hold that stays put: as soon as the finger travels past touch slop this is
                        // a scroll, and it starts scrolling immediately instead of waiting out the
                        // timer (which made every drag feel stuck and, past ~500 ms, select text).
                        var moved = false
                        var released = false
                        val longPressed = withTimeoutOrNull(longPressTimeout) {
                            var waiting = true
                            while (waiting) {
                                val change = awaitPointerEvent(PointerEventPass.Main)
                                    .changes.firstOrNull { it.id == down.id }
                                val travelled = change?.let { (it.position - down.position).getDistance() }
                                when {
                                    change == null || !change.pressed -> {
                                        released = true
                                        waiting = false
                                    }

                                    travelled != null && travelled > touchSlop -> {
                                        moved = true
                                        waiting = false
                                    }
                                }
                            }
                            false
                        } == null

                        when {
                            longPressed -> {
                                onSelectionStart(cellAt(down.position))
                                while (true) {
                                    val change = awaitPointerEvent(PointerEventPass.Main)
                                        .changes.firstOrNull { it.id == down.id } ?: break
                                    onSelectionExtend(cellAt(change.position))
                                    change.consume()
                                    if (!change.pressed) break
                                }
                            }

                            released || !moved -> onTap()

                            else -> {
                                // Whole lines are applied incrementally from the last applied
                                // position, so a coarse event burst neither drifts nor stalls: the
                                // view follows the finger one row at a time.
                                var anchorY = down.position.y
                                while (true) {
                                    val change = awaitPointerEvent(PointerEventPass.Main)
                                        .changes.firstOrNull { it.id == down.id } ?: break
                                    val lines = ((change.position.y - anchorY) / cellHeightPx).toInt()
                                    if (lines != 0) {
                                        onScroll(lines)
                                        anchorY += lines * cellHeightPx
                                    }
                                    change.consume()
                                    if (!change.pressed) break
                                }
                            }
                        }
                    }
                }
                // Mouse / trackpad wheel: a desktop-style scroll through history.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (delta != 0f) {
                                // A positive scroll delta reveals newer output, so it maps to a
                                // negative viewport step (history is offset 0..size above the live view).
                                wheelAccumulator += delta
                                while (wheelAccumulator >= 1f) {
                                    onScroll(-1)
                                    wheelAccumulator -= 1f
                                }
                                while (wheelAccumulator <= -1f) {
                                    onScroll(1)
                                    wheelAccumulator += 1f
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
        ) {
            snapshot.cells.forEachIndexed { rowIndex, rowCells ->
                val cursorCol = if (snapshot.cursor.visible && snapshot.cursor.row == rowIndex) {
                    snapshot.cursor.col
                } else {
                    null
                }
                val selectedCols = selection?.columnsFor(rowIndex, rowCells.size)
                val text = remember(rowCells, cursorCol, selectedCols, base, defaultFg, background, cursorColor, selectionColor) {
                    AnnotatedStringBuilder.buildRow(
                        cells = rowCells,
                        base = base,
                        defaultFg = defaultFg,
                        defaultBg = background,
                        cursorCol = cursorCol,
                        cursorColor = cursorColor,
                        selection = selectedCols,
                        selectionColor = selectionColor
                    )
                }
                Text(
                    text = text,
                    style = textStyle,
                    color = defaultFg,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            }
        }

        // Selection handles. Drawn as small hearts in the app's accent colour instead of the
        // platform teardrops: the terminal paints its own cells, so it also owns the affordances
        // around them, and this keeps them on-palette in every scheme.
        if (selection != null) {
            val accent = MaterialTheme.colorScheme.primary
            val halo = background
            Canvas(modifier = Modifier.fillMaxSize()) {
                val padH = KernSpacing.xs.toPx()
                val padV = KernSpacing.xxs.toPx()
                val halfWidth = 7.dp.toPx()
                val halfHeight = 6.dp.toPx()

                val startX = padH + selection.start.col * cellWidthPx
                val startY = padV + selection.start.row * cellHeightPx - halfHeight
                drawHeartHandle(Offset(startX, startY), halfWidth, halfHeight, accent, halo)

                val endX = padH + (selection.end.col + 1) * cellWidthPx
                val endY = padV + (selection.end.row + 1) * cellHeightPx + halfHeight
                drawHeartHandle(Offset(endX, endY), halfWidth, halfHeight, accent, halo)
            }
        }
    }
}

/** Lightweight empty body used before the first frame arrives. */
@Composable
fun TerminalPlaceholder(appearance: TerminalAppearance, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(appearance.background)
    )
}
