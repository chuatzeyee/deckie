package com.chuatzeyee.deck.ui

import android.util.Log
import com.chuatzeyee.deck.core.AnswerResult
import com.chuatzeyee.deck.core.Host
import com.chuatzeyee.deck.core.Question
import com.chuatzeyee.deck.core.Sender
import com.chuatzeyee.deck.data.HostRepository
import com.chuatzeyee.deck.poll.HostLink
import com.chuatzeyee.deck.poll.LinkState
import com.chuatzeyee.deck.poll.SessionView
import com.chuatzeyee.deck.ssh.TerminalConnection
import com.chuatzeyee.deck.ssh.TerminalState
import com.chuatzeyee.deck.watch.DeckRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.connectbot.terminal.TerminalEmulatorFactory

private const val TERMINAL_FALLBACK_COLS = 80
private const val TERMINAL_FALLBACK_ROWS = 24

/**
 * Owns the Deck screen's live-session plumbing: the active host's [TerminalConnection] (the live
 * terminal's own SSH transport, started/stopped here) - split out of [AppViewModel] so each file
 * stays under the size limit (fix round 1, important #6). [AppViewModel] delegates every
 * Deck-screen method here unchanged.
 *
 * The polling [HostLink]/[Sender] themselves are no longer owned here (ruling R24): [restartLink]
 * asks [runtime] for them, so this screen and [com.chuatzeyee.deck.watch.WatchService] always
 * share the one live connection to a host rather than each opening its own.
 */
class DeckController(
    private val scope: CoroutineScope,
    private val keyProvider: suspend (keyId: String) -> KeyProvider,
    private val repo: HostRepository,
    private val runtime: DeckRuntime,
    /** Called with the repository's fresh copy of the active host right after a session create/
     * rename/kill succeeds (fix round 1, important), so a caller holding its own snapshot (e.g.
     * [AppViewModel.activeHost]) can stay current without polling the repository itself. */
    private val onHostUpdated: (Host) -> Unit = {},
) {

    private var currentHostLink: HostLink? = null
    private var currentSender: Sender? = null
    private var currentSessionActions: SessionActions? = null
    private var linkStateJob: Job? = null

    /** The host [restartLink] is currently serving - only its `id` is used, to re-read a fresh
     * copy from [repo] after a session action succeeds (see [onHostUpdated]). */
    private var currentHostId: String? = null

    private var terminalConnection: TerminalConnection? = null
    private var terminalStateJob: Job? = null

    /** The terminal pane's last known real size (termlib's own resize callback) - the next
     * session attached opens at that size instead of a hardcoded fallback (fix round 1 minor). */
    @Volatile private var lastSize: Pair<Int, Int> = TERMINAL_FALLBACK_COLS to TERMINAL_FALLBACK_ROWS

    private val _link = MutableStateFlow<LinkState>(LinkState.Connecting)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _terminalState = MutableStateFlow<TerminalState>(TerminalState.Idle)
    val terminalState: StateFlow<TerminalState> = _terminalState.asStateFlow()

    /** Set when [answer] gets back an [AnswerResult.Refused] - the Deck screen surfaces it as a
     * snackbar, then clears it via [clearAnswerRefusal]. */
    private val _answerRefusal = MutableStateFlow<String?>(null)
    val answerRefusal: StateFlow<String?> = _answerRefusal.asStateFlow()

    /** True while one [answer] is running - the question bar disables its buttons, and a second
     * tap is ignored, so a stale double tap can never send a second answer. */
    private val _answerInFlight = MutableStateFlow(false)
    val answerInFlight: StateFlow<Boolean> = _answerInFlight.asStateFlow()

    /** The link's latest state as [HostLink] published it, before [hideSuppressed]. */
    private var rawLink: LinkState = LinkState.Connecting

    /** Answered questions still hidden from the bar - see [pruneSuppressed]. Main thread only. */
    private var suppressed: Map<String, String> = emptyMap()

    /** Sessions this screen put in copy-mode (swipe-to-scroll) since their last answer: the next
     * [answer] there leaves copy-mode first. Main thread only. */
    private val scrolledSessions = mutableSetOf<String>()

    /** The session-menu dialog state and its create/rename/kill actions - see
     * [SessionMenuController]'s own doc for why this is a separate class. */
    private val sessionMenu = SessionMenuController(
        scope = scope,
        repo = repo,
        currentHostId = { currentHostId },
        currentSessionActions = { currentSessionActions },
        kickHostLink = { currentHostLink?.kick() },
        onHostUpdated = onHostUpdated,
        selected = { selected.value },
        select = ::select,
        clearSelection = { _selected.value = null },
    )
    val menuState: StateFlow<SessionMenuState> = sessionMenu.menuState
    val actionInFlight: StateFlow<Boolean> = sessionMenu.actionInFlight

    init {
        // B4: a terminal that broke while the app was away re-attaches when it comes back.
        scope.launch {
            runtime.visible.collect { visible ->
                if (shouldRetryTerminalOnResume(_terminalState.value, visible)) retryTerminal()
            }
        }
    }

    /** Marks [name] as the selected session: focuses it on the poller (so its question/status is
     * tracked as "open"), marks it opened, and attaches the terminal to it. */
    fun select(name: String) {
        if (_selected.value == name) return
        _selected.value = name
        attachTerminal(name)
    }

    private fun attachTerminal(name: String) {
        runtime.setFocusedSession(name)
        currentHostLink?.opened(name)
        val (cols, rows) = lastSize
        scope.launch { terminalConnection?.attach(name, cols, rows) }
    }

    /** Leaving the Deck screen (back to host setup/session picker): un-focuses the session and
     * closes the terminal's own SSH transport - R21, "leaving the screen". */
    fun leaveDeckScreen() {
        runtime.setFocusedSession(null)
        _selected.value = null
        sessionMenu.dismissMenu()
        val terminal = terminalConnection
        scope.launch { terminal?.close() }
    }

    /** The terminal pane's "tap to retry". */
    fun retryTerminal() {
        scope.launch { terminalConnection?.retry() }
    }

    /** Writes raw bytes to the live terminal - the extra-keys row and Ctrl-sticky control bytes. */
    fun sendRawToTerminal(bytes: ByteArray) {
        terminalConnection?.sendRaw(bytes)
    }

    /** Swipe-to-scroll (R22): enters tmux copy-mode once per drag, then scrolls by [lines] rows -
     * both go over the polling [currentSender], never the terminal's own pty. No UI surface for a
     * failure here, so it is swallowed rather than crashing the app (fix round 1, important #1). */
    fun scrollStart() {
        val session = selected.value ?: return
        val sender = currentSender ?: return
        scrolledSessions += session
        scope.launch { runSafely("enterCopyMode") { sender.enterCopyMode(session) } }
    }

    fun scroll(up: Boolean, lines: Int) {
        val session = selected.value ?: return
        val sender = currentSender ?: return
        scope.launch { runSafely("scroll") { sender.scroll(session, up, lines) } }
    }

    /** Answers [question]'s option [index] via the one shared [Sender] for the active host.
     * [Sender.answer] can throw (`IllegalStateException("Not connected")`, `IOException`) when the
     * polling link is down between retries - caught here and surfaced as [answerRefusal] instead of
     * crashing (fix round 1, important #1). */
    fun answer(question: Question, index: Int) {
        if (_answerInFlight.value) return
        val sender = currentSender ?: return
        _answerInFlight.value = true
        // Review Focus 5: typing into the terminal attached to this session waits for the answer.
        val gate = terminalConnection?.inputGate?.takeIf { question.session == _selected.value }
        gate?.hold()
        val cancelCopyMode = scrolledSessions.remove(question.session)
        scope.launch {
            try {
                when (val result = sender.answer(question, index, cancelCopyMode)) {
                    is AnswerResult.Refused -> _answerRefusal.value = result.reason
                    AnswerResult.Sent -> suppress(question)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Deck", "answer() failed", e)
                _answerRefusal.value = "Couldn't send the answer — connection lost."
            } finally {
                gate?.release()
                _answerInFlight.value = false
            }
        }
    }

    private fun suppress(question: Question) {
        suppressed = suppressed + (question.session to question.fingerprint)
        _link.value = hideSuppressed(rawLink, suppressed)
    }

    private fun onLinkState(state: LinkState) {
        if (shouldRetryTerminalOnLink(_terminalState.value, rawLink, state)) retryTerminal()
        rawLink = state
        suppressed = pruneSuppressed(suppressed, state)
        _link.value = hideSuppressed(state, suppressed)
    }

    fun clearAnswerRefusal() {
        _answerRefusal.value = null
    }

    private suspend fun runSafely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Deck", "$what failed", e)
        }
    }

    /**
     * Starts (or reuses - ruling R24) [host]'s shared [HostLink]/[Sender] via [runtime], and always
     * gives the Deck screen a fresh [TerminalConnection]. [runtime]'s `chosen` lambda re-reads the
     * host's sessions from the repository on every poll tick, so a new session choice is already
     * picked up by a reused link - no need to recreate it, unlike before ruling R24.
     *
     * If a session is already selected (switching sessions, not the first start), re-attaches it
     * directly on the fresh [TerminalConnection] here rather than going through [select]: that
     * method's own `if (_selected.value == name) return` guard (needed for its ordinary
     * idempotency - it must stay in place) would otherwise make this a no-op and leave the pane
     * stuck on "Connecting…" forever (fix round 1, important #2).
     */
    fun restartLink(host: Host) {
        linkStateJob?.cancel()
        if (currentHostId != host.id) {
            suppressed = emptyMap()
            scrolledSessions.clear()
        }
        currentHostId = host.id
        val hostLink = runtime.ensureLink(host)
        currentHostLink = hostLink
        val pollingExec = checkNotNull(runtime.pollingExec()) { "DeckRuntime has no exec right after ensureLink" }
        currentSender = runtime.sender
        currentSessionActions = SessionActions(pollingExec, repo, host.id)
        linkStateJob = scope.launch { hostLink.state.collect(::onLinkState) }

        // R21: the terminal gets its own SSH connection, entirely separate from the one above -
        // closing the stale one is fire-and-forget teardown, nothing downstream awaits it.
        terminalStateJob?.cancel()
        val staleTerminal = terminalConnection
        terminalConnection = null
        _terminalState.value = TerminalState.Idle
        if (staleTerminal != null) scope.launch(Dispatchers.IO + NonCancellable) { runCatching { staleTerminal.close() } }
        val terminal = TerminalConnection(
            host = host,
            // Called from inside TerminalConnection's own withContext(Dispatchers.IO) - no need to
            // hop dispatchers again here.
            keys = { keyProvider(host.keyId) },
            scope = scope,
            createEmulator = { rows, cols, onKeyboardInput, onResize ->
                TerminalEmulatorFactory.create(
                    initialRows = rows,
                    initialCols = cols,
                    defaultForeground = DeckColors.Text,
                    defaultBackground = DeckColors.Background,
                    onKeyboardInput = onKeyboardInput,
                    onBell = {},
                    onResize = onResize,
                    onClipboardCopy = {},
                    onProgressChange = { _, _ -> },
                    autoDetectUrls = true,
                    boldAsBright = true,
                )
            },
            onDimensionsChanged = { cols, rows -> lastSize = cols to rows },
        )
        terminalConnection = terminal
        terminalStateJob = scope.launch { terminal.state.collect { _terminalState.value = it } }

        _selected.value?.let { session ->
            runtime.setFocusedSession(session)
            currentHostLink?.opened(session)
            val (cols, rows) = lastSize
            scope.launch { terminal.attach(session, cols, rows) }
        }
    }

    // --- Session menu (Task 12: create/rename/kill) - delegates to sessionMenu -------------------

    fun openMenu() = sessionMenu.openMenu()

    /** Long-press on a rail row: opens the same menu, with [session] noted as where it was opened
     * from. */
    fun openMenuFor(session: String) = sessionMenu.openMenuFor(session)

    fun dismissMenu() = sessionMenu.dismissMenu()

    /** Defaults the new session's working directory to the selected session's own path - always
     * absolute, tmux's own `pane_current_path` (see [SessionView.path]) - or `""` when nothing is
     * selected yet, which [com.chuatzeyee.deck.core.Tmux.newSession] treats as "use tmux's own
     * default directory" rather than risk an unexpanded literal `"~"`. */
    fun requestNewSession() {
        val path = selected.value?.let { name -> currentViews().firstOrNull { it.name == name }?.path }
        sessionMenu.requestNewSession(defaultDir = path ?: "")
    }

    fun requestRename(old: String) = sessionMenu.requestRename(old)

    fun requestKill(name: String) = sessionMenu.requestKill(name)

    fun confirmNewSession(name: String, dir: String, startClaude: Boolean) =
        sessionMenu.confirmNewSession(name, dir, startClaude)

    fun confirmRename(new: String) = sessionMenu.confirmRename(new)

    fun confirmKill() = sessionMenu.confirmKill()

    private fun currentViews(): List<SessionView> = (link.value as? LinkState.Up)?.result?.views ?: emptyList()

    /** Tears down this screen's terminal - call from `onCleared`. Not the polling [HostLink]:
     * that's shared, [runtime]-owned (ruling R24), and keeps running for
     * [com.chuatzeyee.deck.watch.WatchService] independent of this ViewModel's lifecycle. */
    fun stop() {
        val terminal = terminalConnection
        if (terminal != null) scope.launch(Dispatchers.IO + NonCancellable) { runCatching { terminal.close() } }
    }
}
