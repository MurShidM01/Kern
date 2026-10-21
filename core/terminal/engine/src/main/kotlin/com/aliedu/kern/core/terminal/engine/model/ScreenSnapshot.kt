package com.aliedu.kern.core.terminal.engine.model

enum class CursorShape { BLOCK, UNDERLINE, BAR }

data class Cursor(
    val row: Int,
    val col: Int,
    val visible: Boolean,
    val shape: CursorShape = CursorShape.BLOCK
)

/**
 * An immutable view of the terminal at a point in time: the visible viewport, cursor, title and
 * mode flags. [viewportOffset] > 0 means the user has scrolled up into scrollback.
 */
data class ScreenSnapshot(
    val columns: Int,
    val rows: Int,
    val cells: List<List<Cell>>,
    val cursor: Cursor,
    val title: String?,
    val altScreen: Boolean,
    val scrollbackSize: Int,
    val viewportOffset: Int,
    val applicationCursorKeys: Boolean,
    val bracketedPaste: Boolean
)
