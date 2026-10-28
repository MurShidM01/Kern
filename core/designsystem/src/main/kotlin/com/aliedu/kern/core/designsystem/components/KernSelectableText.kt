package com.aliedu.kern.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.aliedu.kern.core.designsystem.theme.KernRadius
import kotlin.math.roundToInt

/**
 * One heart-shaped selection handle, centred on [center].
 *
 * Two cubic lobes with the point at the bottom read as a heart at 14x12 dp, where a literal heart
 * outline would just look like a blob. Shared by the terminal renderer and [KernSelectableText] so
 * every selection affordance in the app is the same shape.
 */
fun DrawScope.drawHeartHandle(
    center: Offset,
    halfWidth: Float,
    halfHeight: Float,
    fill: Color,
    halo: Color
) {
    val path = Path().apply {
        moveTo(center.x, center.y + halfHeight)
        cubicTo(
            center.x - halfWidth * 1.45f, center.y - halfHeight * 0.25f,
            center.x - halfWidth * 0.65f, center.y - halfHeight * 1.25f,
            center.x, center.y - halfHeight * 0.45f
        )
        cubicTo(
            center.x + halfWidth * 0.65f, center.y - halfHeight * 1.25f,
            center.x + halfWidth * 1.45f, center.y - halfHeight * 0.25f,
            center.x, center.y + halfHeight
        )
        close()
    }
    // A hairline halo in the surrounding colour keeps the handle legible over any fill.
    drawPath(path, color = halo, style = Stroke(width = 2.5.dp.toPx()))
    drawPath(path, color = fill)
}

/**
 * Text the user can select by long-pressing and dragging, with Kern's own heart-shaped handles.
 *
 * Compose's selection container paints platform-styled teardrops from inside the foundation
 * library, and neither their shape nor their colour is themeable, so the agent transcript draws its
 * own selection here: highlight and handles both come from the app palette, and the handles match
 * the terminal's.
 *
 * Selection is per text block — dragging inside one paragraph (or one list item) selects a range of
 * it, and a Copy chip follows the end handle so a partial selection is actually useful.
 */
@Composable
fun KernSelectableText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    /** Colour behind the text: the halo around the handles must match it to stay invisible. */
    containerColor: Color = MaterialTheme.colorScheme.surface
) {
    val accent = MaterialTheme.colorScheme.primary
    val highlight = accent.copy(alpha = 0.26f)
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var selection by remember { mutableStateOf<TextRange?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    // A new message body (streaming, edited, a recycled row) invalidates any old offsets.
    LaunchedEffect(text) { selection = null }

    val active = selection?.takeIf { !it.collapsed }
    val chipGapPx = with(density) { 26.dp.toPx() }

    Box(modifier = modifier) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier = Modifier
                .onGloballyPositioned { origin = it.positionInRoot() }
                // One gesture loop, not two competing detectors: a tap clears the selection, a
                // long press starts one, and the drag that follows extends it. (Two separate
                // pointerInput modifiers fight over the down event, and the tap detector wins,
                // which is why the handles never appeared.)
                .pointerInput(text) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val result = layout
                        val pressed = awaitLongPressOrCancellation(down.id)
                        if (result == null || pressed == null) {
                            selection = null
                            return@awaitEachGesture
                        }
                        val anchor = result.getOffsetForPosition(pressed.position)
                        selection = TextRange(anchor, anchor)
                        drag(pressed.id) { change ->
                            change.consume()
                            selection = TextRange(
                                anchor,
                                result.getOffsetForPosition(change.position)
                            )
                        }
                    }
                }
                .drawWithContent {
                    val range = active
                    val result = layout
                    if (range != null && result != null) {
                        drawPath(result.getPathForRange(range.min, range.max), highlight)
                    }
                    drawContent()
                    if (range != null && result != null) {
                        val start = result.getBoundingBox(range.min)
                        val end = result.getBoundingBox(range.max - 1)
                        val halfWidth = 6.dp.toPx()
                        val halfHeight = 5.dp.toPx()
                        drawHeartHandle(
                            center = Offset(start.left, start.top - halfHeight * 0.6f),
                            halfWidth = halfWidth,
                            halfHeight = halfHeight,
                            fill = accent,
                            halo = containerColor
                        )
                        drawHeartHandle(
                            center = Offset(end.right, end.bottom + halfHeight * 0.6f),
                            halfWidth = halfWidth,
                            halfHeight = halfHeight,
                            fill = accent,
                            halo = containerColor
                        )
                    }
                }
        )

        val range = active
        val result = layout
        if (range != null && result != null) {
            val end = result.getBoundingBox(range.max - 1)
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(
                    x = (origin.x + end.right).roundToInt(),
                    y = (origin.y + end.bottom + chipGapPx).roundToInt()
                ),
                properties = PopupProperties(focusable = false),
                onDismissRequest = { selection = null }
            ) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, KernRadius.smShape)
                        .clickable {
                            val body = text.text.substring(range.min, range.max)
                            clipboard.setText(AnnotatedString(body))
                            selection = null
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Copy",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
