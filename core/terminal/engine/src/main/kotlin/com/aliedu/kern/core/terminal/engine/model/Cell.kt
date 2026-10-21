package com.aliedu.kern.core.terminal.engine.model

/**
 * One screen cell. [text] is normally a single character but may carry combining marks appended to
 * the base glyph. [continuation] marks the trailing half of a double-width glyph (rendered as nothing).
 */
data class Cell(
    val text: String = " ",
    val attrs: CellAttributes = CellAttributes.DEFAULT,
    val width: Int = 1,
    val continuation: Boolean = false
) {
    val isBlank: Boolean get() = !continuation && text == " " && attrs == CellAttributes.DEFAULT

    companion object {
        val EMPTY = Cell()
    }
}
