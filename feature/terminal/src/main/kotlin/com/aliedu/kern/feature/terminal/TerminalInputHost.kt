package com.aliedu.kern.feature.terminal

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Bridges the soft/hardware keyboard to the terminal. This is the Termux mechanism: a focusable
 * [View] with `inputType = TYPE_NULL` reports itself as a text editor (so the IME shows) but receives
 * raw key events, which we forward to the PTY as terminal input — no separate command box.
 */
class TerminalInputController {
    private var view: View? = null

    internal fun attach(view: View) {
        this.view = view
    }

    /** Focus the terminal input, which raises the soft keyboard. */
    fun focus() {
        val target = view ?: return
        target.requestFocus()
        val imm = target.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Hide the keyboard and drop focus, e.g. when leaving the terminal screen. */
    fun dismiss() {
        val target = view ?: return
        val imm = target.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(target.windowToken, 0)
        target.clearFocus()
    }

    /** Toggle the soft keyboard without moving focus away from the terminal. */
    fun toggleKeyboard(visible: Boolean) {
        val target = view ?: return
        val imm = target.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        if (visible) {
            imm.hideSoftInputFromWindow(target.windowToken, 0)
        } else {
            focus()
        }
    }
}

/** Maps Android key events to the byte sequences a terminal expects. */
internal object TerminalKeys {

    fun sequenceFor(keyCode: Int, metaState: Int, applicationCursorKeys: Boolean): String? {
        val ctrl = (metaState and KeyEvent.META_CTRL_ON) != 0
        if (ctrl && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            return (keyCode - KeyEvent.KEYCODE_A + 1).toChar().toString()
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_DPAD_UP -> if (applicationCursorKeys) "\u001bOA" else "\u001b[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> if (applicationCursorKeys) "\u001bOB" else "\u001b[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (applicationCursorKeys) "\u001bOC" else "\u001b[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> if (applicationCursorKeys) "\u001bOD" else "\u001b[D"
            KeyEvent.KEYCODE_MOVE_HOME -> if (applicationCursorKeys) "\u001bOH" else "\u001b[H"
            KeyEvent.KEYCODE_MOVE_END -> if (applicationCursorKeys) "\u001bOF" else "\u001b[F"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
            else -> null
        }
    }
}

@Composable
fun TerminalInputHost(
    controller: TerminalInputController,
    onText: (String) -> Unit,
    onSequence: (String) -> Unit,
    applicationCursorKeys: Boolean,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TerminalInputView(context).also(controller::attach)
        },
        update = { view ->
            view.onText = onText
            view.onSequence = onSequence
            view.applicationCursorKeys = applicationCursorKeys
        }
    )
}

private class TerminalInputView(context: Context) : View(context) {

    var onText: ((String) -> Unit)? = null
    var onSequence: ((String) -> Unit)? = null

    @Volatile
    var applicationCursorKeys: Boolean = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    /**
     * Hardware keyboards and `adb shell input` deliver key events to the view hierarchy rather than
     * through the input connection, so we handle them here too. Without this, synthetic key events
     * would be dropped (the view is not an EditText).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKey(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun handleKey(event: KeyEvent): Boolean {
        val sequence = TerminalKeys.sequenceFor(event.keyCode, event.metaState, applicationCursorKeys)
        if (sequence != null) {
            onSequence?.invoke(sequence)
            return true
        }
        val unicode = event.unicodeChar
        if (unicode != 0) {
            onText?.invoke(String(Character.toChars(unicode)))
            return true
        }
        return false
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI

        return object : BaseInputConnection(this@TerminalInputView, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) onText?.invoke(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(0)) { onSequence?.invoke("\u007f") }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                val sequence = TerminalKeys.sequenceFor(event.keyCode, event.metaState, applicationCursorKeys)
                if (sequence != null) {
                    onSequence?.invoke(sequence)
                } else {
                    val unicode = event.unicodeChar
                    if (unicode != 0) onText?.invoke(String(Character.toChars(unicode)))
                }
                return true
            }
        }
    }
}

/** Remember a controller across recompositions. */
@Composable
fun rememberTerminalInputController(): TerminalInputController = remember { TerminalInputController() }
