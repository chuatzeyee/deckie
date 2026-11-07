package com.chuatzeyee.deck.ui

import android.view.KeyEvent.KEYCODE_SYM
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import com.chuatzeyee.deck.core.KeyAction
import com.chuatzeyee.deck.core.ctrlByte
import com.chuatzeyee.deck.core.cycleSession
import com.chuatzeyee.deck.core.keyAction
import com.chuatzeyee.deck.core.letterForKeyCode

/** [DeckScreen]'s whole `onPreviewKeyEvent` entry point: filters out the bare Sym press itself and
 * key-up events before deferring to [handleDeckKeyEvent] - split out purely to keep that
 * composable's own body under the size limit (fix round 1, important #6). */
fun onDeckRootKeyEvent(
    event: KeyEvent,
    letters: Map<Char, String>,
    order: List<String>,
    selected: String?,
    ctrlArmed: Boolean,
    onSelect: (String) -> Unit,
    onMenu: () -> Unit,
    onCtrlByte: (Byte) -> Unit,
): Boolean {
    val native = event.nativeKeyEvent
    if (native.keyCode == KEYCODE_SYM) return true
    if (event.type != KeyEventType.KeyDown) return false
    return handleDeckKeyEvent(native.keyCode, native.metaState, letters, order, selected, ctrlArmed, onSelect, onMenu, onCtrlByte)
}

/**
 * Decides what one hardware key press does on the Deck screen root: Sym-chords (R20) first -
 * jump to a session, cycle, open the menu, or (an unassigned Sym+letter) just consume it and do
 * nothing (fix round 1 minor: never let it fall through and type into the terminal) - then, only
 * if it wasn't a Sym-chord at all, a Ctrl-armed sticky control byte. Pure aside from the callbacks
 * it's handed, so it's unit-testable without Compose; the only Compose-aware caller is
 * [DeckScreen]'s `onPreviewKeyEvent`. Returns whether the event was consumed.
 */
fun handleDeckKeyEvent(
    keyCode: Int,
    meta: Int,
    letters: Map<Char, String>,
    order: List<String>,
    selected: String?,
    ctrlArmed: Boolean,
    onSelect: (String) -> Unit,
    onMenu: () -> Unit,
    onCtrlByte: (Byte) -> Unit,
): Boolean {
    when (val action = keyAction(keyCode, meta, letters)) {
        is KeyAction.Jump -> onSelect(action.session)
        KeyAction.Next -> cycleSession(selected, order, forward = true)?.let(onSelect)
        KeyAction.Prev -> cycleSession(selected, order, forward = false)?.let(onSelect)
        KeyAction.OpenMenu -> onMenu()
        KeyAction.Ignore -> Unit
        null -> {
            if (ctrlArmed) {
                val byte = letterForKeyCode(keyCode)?.let(::ctrlByte)
                if (byte != null) {
                    onCtrlByte(byte)
                    return true
                }
            }
            return false
        }
    }
    return true
}
