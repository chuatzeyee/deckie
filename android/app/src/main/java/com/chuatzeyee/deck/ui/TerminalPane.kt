package com.chuatzeyee.deck.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.chuatzeyee.deck.core.ExtraKey
import com.chuatzeyee.deck.ssh.TerminalState
import org.connectbot.terminal.Terminal

/** Swipe-to-scroll treats a drag of this many dp as one tmux history row - a fixed estimate (not
 * the emulator's real cell height) is simple and good enough for a touch gesture. */
private val SCROLL_ROW_HEIGHT = 18.dp

/**
 * The right-hand pane (mockup layout B): the live termlib [Terminal], or a Connecting/Failed
 * placeholder, plus R22's extra-keys row underneath.
 */
@Composable
fun TerminalPane(
    state: TerminalState,
    ctrlArmed: Boolean,
    onExtraKey: (ExtraKey) -> Unit,
    onCtrlToggle: () -> Unit,
    onRetry: () -> Unit,
    onScrollStart: () -> Unit,
    onScroll: (up: Boolean, lines: Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Shown between the terminal and the extra-keys row - the question bar, when the selected
     * session has a live question. */
    belowTerminal: @Composable () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        TerminalBody(
            state = state,
            onRetry = onRetry,
            onScrollStart = onScrollStart,
            onScroll = onScroll,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        belowTerminal()
        ExtraKeysRow(ctrlArmed = ctrlArmed, onExtraKey = onExtraKey, onCtrlToggle = onCtrlToggle)
    }
}

/** The terminal itself, or its Connecting/Failed placeholder. Owns a [FocusRequester] of its own
 * (distinct from the screen root's, which keeps Sym interception) and claims focus whenever a new
 * [TerminalState.Attached] arrives - termlib never focuses itself on attach, so hardware keys
 * would otherwise go nowhere until the user taps the pane (fix round 1, important #4). */
@Composable
private fun TerminalBody(
    state: TerminalState,
    onRetry: () -> Unit,
    onScrollStart: () -> Unit,
    onScroll: (up: Boolean, lines: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(state) {
        if (state is TerminalState.Attached) runCatching { focusRequester.requestFocus() }
    }
    Box(
        modifier = modifier.pointerInput(onScrollStart, onScroll) {
            detectTerminalScroll(rowPx = SCROLL_ROW_HEIGHT.toPx(), onScrollStart = onScrollStart, onScroll = onScroll)
        },
    ) {
        when (state) {
            TerminalState.Idle, TerminalState.Connecting ->
                Text("Connecting…", modifier = Modifier.align(Alignment.Center))
            is TerminalState.Failed ->
                Text(state.message, modifier = Modifier.align(Alignment.Center).clickable(onClick = onRetry))
            is TerminalState.Attached -> Terminal(
                terminalEmulator = state.emulator,
                modifier = Modifier.fillMaxSize(),
                keyboardEnabled = true,
                showSoftKeyboard = !hasHardwareKeyboard(),
                focusRequester = focusRequester,
            )
        }
    }
}

@Composable
private fun hasHardwareKeyboard(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.keyboard == Configuration.KEYBOARD_QWERTY ||
        configuration.keyboard == Configuration.KEYBOARD_12KEY
}

/** R22's extra-keys row: `[Esc] [Ctrl] [Tab] [↑] [↓] [←] [→]`. Ctrl is sticky - [ctrlArmed]
 * highlights it orange instead of sending a byte of its own; see [com.chuatzeyee.deck.core.ctrlByte]. */
@Composable
private fun ExtraKeysRow(ctrlArmed: Boolean, onExtraKey: (ExtraKey) -> Unit, onCtrlToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ExtraKeyButton("Esc") { onExtraKey(ExtraKey.Esc) }
        Button(
            onClick = onCtrlToggle,
            colors = if (ctrlArmed) {
                ButtonDefaults.buttonColors(containerColor = DeckColors.Attention, contentColor = DeckColors.Background)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) { Text("Ctrl") }
        ExtraKeyButton("Tab") { onExtraKey(ExtraKey.Tab) }
        ExtraKeyButton("↑") { onExtraKey(ExtraKey.Up) }
        ExtraKeyButton("↓") { onExtraKey(ExtraKey.Down) }
        ExtraKeyButton("←") { onExtraKey(ExtraKey.Left) }
        ExtraKeyButton("→") { onExtraKey(ExtraKey.Right) }
    }
}

@Composable
private fun ExtraKeyButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick) { Text(label) }
}
