package com.chuatzeyee.deck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.chuatzeyee.deck.core.ExtraKey
import com.chuatzeyee.deck.core.Host
import com.chuatzeyee.deck.core.Question
import com.chuatzeyee.deck.core.extraKeyBytes
import com.chuatzeyee.deck.core.railLetters
import com.chuatzeyee.deck.poll.LinkState
import com.chuatzeyee.deck.poll.SessionView
import com.chuatzeyee.deck.ssh.TerminalState
import kotlinx.coroutines.delay

/**
 * Mockup layout B: a top bar (session + status word, back, menu), the [Rail] (~28% width) beside
 * the live [TerminalPane], and the [QuestionBar] under the terminal when the selected session has
 * one. Hardware Sym-chords (R20) are intercepted here, at the screen root, via
 * [Modifier.onPreviewKeyEvent] - before they ever reach the terminal. Key decoding itself lives in
 * [handleDeckKeyEvent] and the rail/terminal row in [DeckBody] (fix round 1, important #6: keep
 * this composable's own body small).
 */
@Composable
fun DeckScreen(
    host: Host,
    link: LinkState,
    selected: String?,
    terminalState: TerminalState,
    answerRefusal: String?,
    answerInFlight: Boolean,
    staleAfterMs: Long,
    menuState: SessionMenuState,
    menuActions: SessionMenuActions,
    actionInFlight: Boolean,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onSendRaw: (ByteArray) -> Unit,
    onRetryTerminal: () -> Unit,
    onScrollStart: () -> Unit,
    onScroll: (up: Boolean, lines: Int) -> Unit,
    onAnswer: (Question, Int) -> Unit,
    onClearAnswerRefusal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (views, order, letters, letterOf, railMessage, selectedView) =
        rememberDeckDerived(link, host.name, host.sessions, selected)
    val stale = rememberStale(link, staleAfterMs)
    var ctrlArmed by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val terminalAttached = terminalState is TerminalState.Attached
    DeckScreenEffects(selected, views, answerRefusal, terminalAttached, focusRequester, onSelect, onClearAnswerRefusal)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    onDeckRootKeyEvent(event, letters, order, selected, ctrlArmed, onSelect, menuActions.onOpen) { byte ->
                        onSendRaw(byteArrayOf(byte))
                        ctrlArmed = false
                    }
                },
        ) {
            TopBar(hostName = host.name, selectedView = selectedView, onBack = onBack, onMenu = menuActions.onOpen)
            DeckBody(
                modifier = Modifier.weight(1f),
                views = views,
                selected = selected,
                letterOf = letterOf,
                railMessage = railMessage,
                stale = stale,
                selectedView = selectedView,
                terminalState = terminalState,
                ctrlArmed = ctrlArmed,
                onSelect = onSelect,
                onLongPress = menuActions.onOpenFor,
                onSendRaw = onSendRaw,
                onCtrlToggle = { ctrlArmed = !ctrlArmed },
                onRetryTerminal = onRetryTerminal,
                onScrollStart = onScrollStart,
                onScroll = onScroll,
                answerInFlight = answerInFlight,
                onAnswer = onAnswer,
            )
        }

        answerRefusal?.let { reason -> AnswerRefusalBanner(reason, Modifier.align(Alignment.BottomCenter)) }
        SessionMenu(
            state = menuState,
            sessions = order,
            actionInFlight = actionInFlight,
            onNewSession = menuActions.onNewSessionRequest,
            onRenameRequest = menuActions.onRenameRequest,
            onKillRequest = menuActions.onKillRequest,
            onDismiss = menuActions.onDismiss,
            onCreate = menuActions.onCreate,
            onRename = menuActions.onRename,
            onKill = menuActions.onKill,
        )
    }
}

/** The derived, [remember]-cached values [DeckScreen] needs from [link]/[selected] - pulled out of
 * that composable's own body to keep it under the size limit (fix round 1, important #6). */
private data class DeckDerived(
    val views: List<SessionView>,
    val order: List<String>,
    val letters: Map<Char, String>,
    val letterOf: Map<String, Char>,
    val railMessage: String?,
    val selectedView: SessionView?,
)

@Composable
private fun rememberDeckDerived(link: LinkState, hostName: String, chosen: List<String>, selected: String?): DeckDerived {
    val views = (link as? LinkState.Up)?.result?.views ?: emptyList()
    val order = remember(views) { views.map { it.name } }
    // B6: letters come from the user's chosen order, not the status-sorted rail, so a session keeps
    // its letter when it changes status; only sessions actually on the rail are jump targets.
    val letters = remember(chosen, order) { railLetters(chosen, order) }
    val letterOf = remember(letters) { letters.entries.associate { (letter, name) -> name to letter } }
    val railMessage = remember(link, hostName) { railMessage(link, hostName) }
    val selectedView = views.firstOrNull { it.name == selected }
    return DeckDerived(views, order, letters, letterOf, railMessage, selectedView)
}

/** True once [link]'s [LinkState.Up] result is older than [staleAfterMs] - re-checked every
 * second, but only changes (and so only recomposes) when staleness actually flips. */
@Composable
private fun rememberStale(link: LinkState, staleAfterMs: Long): Boolean {
    val stale by produceState(initialValue = false, link, staleAfterMs) {
        while (true) {
            value = isStale(link, System.currentTimeMillis(), staleAfterMs)
            delay(1_000)
        }
    }
    return stale
}

/** [DeckScreen]'s three `LaunchedEffect`s, bundled into one call for the same reason as
 * [rememberDeckDerived]: claim focus whenever the terminal isn't attached (so Sym shortcuts keep
 * working after it drops), default-select the first session when none is picked
 * yet, and auto-clear a transient [answerRefusal] after a few seconds. */
@Composable
private fun DeckScreenEffects(
    selected: String?,
    views: List<SessionView>,
    answerRefusal: String?,
    terminalAttached: Boolean,
    focusRequester: FocusRequester,
    onSelect: (String) -> Unit,
    onClearAnswerRefusal: () -> Unit,
) {
    LaunchedEffect(terminalAttached) { if (!terminalAttached) focusRequester.requestFocus() }
    LaunchedEffect(selected, views.isNotEmpty()) {
        if (selected == null) views.firstOrNull()?.let { onSelect(it.name) }
    }
    LaunchedEffect(answerRefusal) {
        if (answerRefusal != null) {
            delay(3_000)
            onClearAnswerRefusal()
        }
    }
}

@Composable
private fun AnswerRefusalBanner(reason: String, modifier: Modifier = Modifier) {
    Text(
        text = reason,
        color = DeckColors.Text,
        modifier = modifier
            .padding(16.dp)
            .background(DeckColors.SelectedRow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** The [Rail] (~28% width) beside the live [TerminalPane] (~72%) - split out of [DeckScreen] so
 * that composable's own body stays under the size limit (fix round 1, important #6). */
@Composable
private fun DeckBody(
    views: List<SessionView>,
    selected: String?,
    letterOf: Map<String, Char>,
    railMessage: String?,
    stale: Boolean,
    selectedView: SessionView?,
    terminalState: TerminalState,
    ctrlArmed: Boolean,
    onSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onSendRaw: (ByteArray) -> Unit,
    onCtrlToggle: () -> Unit,
    onRetryTerminal: () -> Unit,
    onScrollStart: () -> Unit,
    onScroll: (up: Boolean, lines: Int) -> Unit,
    answerInFlight: Boolean,
    onAnswer: (Question, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth()) {
        Rail(
            sessions = views,
            selected = selected,
            letters = letterOf,
            message = railMessage,
            stale = stale,
            onSelect = onSelect,
            onLongPress = onLongPress,
            modifier = Modifier.weight(0.28f),
        )
        TerminalPane(
            state = terminalState,
            ctrlArmed = ctrlArmed,
            onExtraKey = { key -> onSendRaw(extraKeyBytes(key)) },
            onCtrlToggle = onCtrlToggle,
            onRetry = onRetryTerminal,
            onScrollStart = onScrollStart,
            onScroll = onScroll,
            modifier = Modifier.weight(0.72f),
            belowTerminal = {
                selectedView?.question?.let { question ->
                    QuestionBar(question = question, enabled = !answerInFlight, onAnswer = { index -> onAnswer(question, index) })
                }
            },
        )
    }
}

@Composable
private fun TopBar(hostName: String, selectedView: SessionView?, onBack: () -> Unit, onMenu: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DeckColors.RailBackground)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Text("‹", modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 4.dp), color = DeckColors.Text)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(selectedView?.name ?: hostName, color = DeckColors.Text)
            if (selectedView != null) {
                Text(selectedView.status.name, color = dotColor(selectedView.status))
            }
        }
        Text("≡", modifier = Modifier.clickable(onClick = onMenu).padding(horizontal = 4.dp), color = DeckColors.Text)
    }
}
