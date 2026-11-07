package com.chuatzeyee.deck.ui

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chuatzeyee.deck.DeckApp
import com.chuatzeyee.deck.core.Host
import com.chuatzeyee.deck.core.Question
import com.chuatzeyee.deck.core.StatusTracker
import com.chuatzeyee.deck.core.pickAutoStartHost
import com.chuatzeyee.deck.poll.LinkState
import com.chuatzeyee.deck.poll.PollResult
import com.chuatzeyee.deck.poll.Poller
import com.chuatzeyee.deck.poll.SyncTracker
import com.chuatzeyee.deck.ssh.HostKeyFirstUse
import com.chuatzeyee.deck.ssh.HostKeyMismatch
import com.chuatzeyee.deck.ssh.SshConnection
import com.chuatzeyee.deck.ssh.TerminalState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.userauth.keyprovider.KeyProvider

/** One `saveHost`/`manageSessions` attempt's outcome, for the host-setup/session-picker screens. */
sealed interface TestConnectionState {
    data object Idle : TestConnectionState
    data object Connecting : TestConnectionState
    data class Connected(val host: Host, val result: PollResult) : TestConnectionState
    data class Failed(val message: String) : TestConnectionState
}

/** The first-connection trust dialog's content while [AppViewModel.confirmFirstUse] is pending. */
data class PendingTrust(val address: String, val fingerprint: String, val keyType: String)

/**
 * Owns Deck's app-wide state: the configured [hosts], the single active host's [link] state and
 * the session [selected] for it, plus the host-setup/session-picker actions. The polling
 * [com.chuatzeyee.deck.poll.HostLink]/[com.chuatzeyee.deck.core.Sender] for the active host live in
 * [com.chuatzeyee.deck.watch.DeckRuntime] instead (ruling R24), shared with
 * [com.chuatzeyee.deck.watch.WatchService] - this class only asks [runtime] for them.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val runtime = getApplication<DeckApp>().runtime
    private val repo = runtime.repo
    private val keyVault = runtime.keyVault

    val hosts: StateFlow<List<Host>> =
        repo.hosts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The "Watch in background" toggle and alert-tap navigation - split out so this file stays
     * under the file-size limit (fix round 1, important #6). */
    private val watch = WatchController(viewModelScope, runtime)
    val watchEnabled: StateFlow<Boolean> = watch.watchEnabled
    fun setWatchEnabled(enabled: Boolean) = watch.setWatchEnabled(enabled)
    val pendingOpen: StateFlow<PendingOpen?> = watch.pendingOpen
    fun openFromNotification(intent: Intent) = watch.openFromNotification(intent)
    fun clearPendingOpen() = watch.clearPendingOpen()
    val watchAskState: StateFlow<WatchAskState?> = watch.watchAskState
    fun markPermissionsAsked() = watch.markPermissionsAsked()

    /** Owns the active host's TerminalConnection - split out of this class so each file stays
     * under the file-size limit (fix round 1, important #6). */
    private val deck = DeckController(
        scope = viewModelScope,
        keyProvider = { keyId -> keyVault.provider(keyId) },
        repo = repo,
        runtime = runtime,
        // Fix round 1, important: a session create/rename/kill mutates `sessions` on the
        // repository's copy of the host, not this ViewModel's own `_activeHost` snapshot - without
        // this, DeckScreen's `host` param (and any later restartLink) would keep using the stale
        // list even though the rail itself (driven by `link`, not `host`) already refreshed.
        onHostUpdated = { updated -> _activeHost.value = updated },
    )
    val link: StateFlow<LinkState> = deck.link
    val selected: StateFlow<String?> = deck.selected
    val terminalState: StateFlow<TerminalState> = deck.terminalState
    val answerRefusal: StateFlow<String?> = deck.answerRefusal
    val answerInFlight: StateFlow<Boolean> = deck.answerInFlight
    /** How old a poll result may get before the rail shows it as stale (B3). */
    val staleAfterMs: Long get() = runtime.staleAfterMs
    val menuState: StateFlow<SessionMenuState> = deck.menuState
    val actionInFlight: StateFlow<Boolean> = deck.actionInFlight

    private val _testState = MutableStateFlow<TestConnectionState>(TestConnectionState.Idle)
    val testState: StateFlow<TestConnectionState> = _testState.asStateFlow()

    /** The host-setup form's own state (id/keyId/key action) - see [Draft]'s doc for why this
     * lives here instead of a Composable's `remember`. */
    private val drafts = DraftController(viewModelScope, keyVault)
    val draft: StateFlow<Draft> = drafts.draft

    /** The most recent connection error per host id, shown next to that saved host's row. */
    private val _hostErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val hostErrors: StateFlow<Map<String, String>> = _hostErrors.asStateFlow()

    private val _pendingTrust = MutableStateFlow<PendingTrust?>(null)
    val pendingTrust: StateFlow<PendingTrust?> = _pendingTrust.asStateFlow()
    private var trustDecision: CompletableDeferred<Boolean>? = null

    /** Guards `saveHost`/`manageSessions`: only one connection attempt may run at a time (fix
     * round 1, important #3) - a second tap while one is in flight is ignored outright. */
    private var connectJob: Job? = null

    private val _activeHost = MutableStateFlow<Host?>(null)
    /** The host the Deck screen is (or will be) showing - set once connectAndPoll/chooseSessions
     * succeeds or R17 auto-starts one. */
    val activeHost: StateFlow<Host?> = _activeHost.asStateFlow()

    /** Set exactly once, only by R17's own auto-start in [init] - distinct from [activeHost],
     * which also changes on ordinary saveHost/manageSessions flows the UI must not react to by
     * jumping to the Deck screen before sessions are even chosen. */
    private val _autoStarted = MutableStateFlow<String?>(null)
    val autoStarted: StateFlow<String?> = _autoStarted.asStateFlow()

    /** Delete / re-trust for saved hosts (B5) - the UI calls it directly. */
    val hostAdmin = HostAdminController(
        scope = viewModelScope,
        repo = repo,
        keyVault = keyVault,
        runtime = runtime,
        onDeleted = { id ->
            _hostErrors.update { it - id }
            if (_activeHost.value?.id == id) _activeHost.value = null
        },
        reconnect = ::manageSessions,
    )

    init {
        // R17: auto-start a usable host (a pinned key and at least one chosen session),
        // preferring the one that was active last time - see pickAutoStartHost.
        viewModelScope.launch {
            val eligible = pickAutoStartHost(repo.hosts.first(), repo.lastHostId.first())
            if (eligible != null) {
                _activeHost.value = eligible
                restartLink(eligible)
                _autoStarted.value = eligible.id
            }
        }
    }

    /** Marks [name] as the selected session: focuses it on the poller (so its question/status is
     * tracked as "open"), marks it opened, and attaches the terminal to it. */
    fun select(name: String) = deck.select(name)

    /** Leaving the Deck screen (back to host setup/session picker): un-focuses the session and
     * closes the terminal's own SSH transport - R21, "leaving the screen". */
    fun leaveDeckScreen() = deck.leaveDeckScreen()

    /** The terminal pane's "tap to retry". */
    fun retryTerminal() = deck.retryTerminal()

    /** Writes raw bytes to the live terminal - the extra-keys row and Ctrl-sticky control bytes. */
    fun sendRawToTerminal(bytes: ByteArray) = deck.sendRawToTerminal(bytes)

    /** Swipe-to-scroll (R22): enters tmux copy-mode once per drag, then scrolls by [lines] rows -
     * both go over the polling link, never the terminal's own pty (see R21/R22 docs). */
    fun scrollStart() = deck.scrollStart()

    fun scroll(up: Boolean, lines: Int) = deck.scroll(up, lines)

    /** Answers [question]'s option [index] via the one shared Sender for the active host. */
    fun answer(question: Question, index: Int) = deck.answer(question, index)

    fun clearAnswerRefusal() = deck.clearAnswerRefusal()

    /** Opens the general session menu (`Sym+M` or the top bar's "≡"). */
    fun openMenu() = deck.openMenu()

    /** Opens the session menu for [session] (a rail row's long-press). */
    fun openMenuFor(session: String) = deck.openMenuFor(session)

    fun dismissMenu() = deck.dismissMenu()

    fun requestNewSession() = deck.requestNewSession()

    fun requestRename(old: String) = deck.requestRename(old)

    fun requestKill(name: String) = deck.requestKill(name)

    fun confirmNewSession(name: String, dir: String, startClaude: Boolean) =
        deck.confirmNewSession(name, dir, startClaude)

    fun confirmRename(new: String) = deck.confirmRename(new)

    fun confirmKill() = deck.confirmKill()

    /** Resets [testState] to [TestConnectionState.Idle] - called once the UI has acted on a
     * [TestConnectionState.Connected]/[TestConnectionState.Failed], so it isn't acted on twice. */
    fun clearTestState() {
        _testState.value = TestConnectionState.Idle
    }

    /** Generates a fresh Ed25519 key for the current draft - see [DraftController.generateKey]. */
    fun generateKey() = drafts.generateKey()

    /** Imports an existing private key for the current draft - see [DraftController.importKey]. */
    fun importKey(privateKeyPem: String, passphrase: CharArray?) = drafts.importKey(privateKeyPem, passphrase)

    private fun resetDraft() = drafts.reset()

    /**
     * Builds the [Host] from the setup form's fields and the current [draft] (one stable id across
     * retries - fix round 1, important #1), then connects - see [connectAndPoll]. The host is only
     * ever persisted once that connection actually succeeds; a failed/refused attempt saves
     * nothing. Ignored while another attempt is already running (important #3).
     */
    fun saveHost(name: String, address: String, port: String, user: String) {
        if (connectJob?.isActive == true) return
        val current = draft.value
        val host = hostFromForm(
            id = current.id,
            keyId = current.keyId,
            name = name,
            address = address,
            port = port,
            user = user,
            pinnedHostKey = null,
            sessions = emptyList(),
        )
        connectJob = viewModelScope.launch { connectAndPoll(host, resetDraftOnSuccess = true) }
    }

    /** Re-connects to an already-saved [host] to refresh its live session list for the picker. */
    fun manageSessions(host: Host) {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch { connectAndPoll(host, resetDraftOnSuccess = false) }
    }

    /** Saves the user's chosen session names for [hostId], then (re)starts its [HostLink]. */
    fun chooseSessions(hostId: String, names: List<String>) {
        viewModelScope.launch {
            repo.setSessions(hostId, names)
            val base = activeHost.value?.takeIf { it.id == hostId } ?: hosts.value.firstOrNull { it.id == hostId }
            val updated = base?.copy(sessions = names) ?: return@launch
            _activeHost.value = updated
            restartLink(updated)
        }
    }

    /** Shows the first-use trust dialog and suspends until the user answers via [resolveTrust].
     * Defensively completes (as not-trusted) any still-pending prior dialog first - [connectJob]
     * already keeps attempts single-flight, but this keeps the deferred itself safe either way. */
    suspend fun confirmFirstUse(address: String, fingerprint: String, keyType: String): Boolean {
        trustDecision?.let { if (!it.isCompleted) it.complete(false) }
        val decision = CompletableDeferred<Boolean>()
        trustDecision = decision
        _pendingTrust.value = PendingTrust(address, fingerprint, keyType)
        return try {
            decision.await()
        } finally {
            _pendingTrust.value = null
        }
    }

    /** Resolves the dialog [confirmFirstUse] is suspended on. */
    fun resolveTrust(trusted: Boolean) {
        trustDecision?.complete(trusted)
        trustDecision = null
    }

    /**
     * Connects to [host] (handling first-use trust and pinning per ruling R13, up to one retry),
     * persists it only once that connection actually succeeds, then runs a single poll tick for
     * the session picker's live list. Never leaves the one-shot connection open - the long-lived
     * [HostLink] is only started once sessions are actually chosen, by [chooseSessions].
     */
    private suspend fun connectAndPoll(host: Host, resetDraftOnSuccess: Boolean) {
        _testState.value = TestConnectionState.Connecting
        _activeHost.value = host
        val provider = resolveProvider(host.keyId)
        if (provider == null) {
            fail(host.id, "Key for this host is missing — generate or import a new one")
            return
        }
        var current = host
        repeat(2) {
            val connection = SshConnection(current, provider)
            when (val outcome = tryConnect(connection, current)) {
                is ConnectOutcome.Retry -> {
                    current = outcome.pinned
                    _activeHost.value = current
                    return@repeat
                }
                is ConnectOutcome.Failed -> {
                    fail(current.id, outcome.message)
                    return
                }
                ConnectOutcome.Connected -> {
                    repo.upsert(current)
                    _hostErrors.update { it - current.id }
                    if (resetDraftOnSuccess) resetDraft()
                    pollOnce(connection, current)
                    return
                }
            }
        }
        fail(current.id, "Could not verify host key.")
    }

    private fun fail(hostId: String, message: String) {
        _testState.value = TestConnectionState.Failed(message)
        _hostErrors.update { it + (hostId to message) }
    }

    /** Loads [keyId]'s key off the main thread; `null` (never a crash) if it's missing or
     * unreadable - fix round 1, critical fix. The real exception is logged, never shown raw. */
    private suspend fun resolveProvider(keyId: String): KeyProvider? = try {
        withContext(Dispatchers.IO) { keyVault.provider(keyId) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Deck", "Could not load key '$keyId'", e)
        null
    }

    private sealed interface ConnectOutcome {
        data class Retry(val pinned: Host) : ConnectOutcome
        data class Failed(val message: String) : ConnectOutcome
        data object Connected : ConnectOutcome
    }

    /** Always closes [connection] except on [ConnectOutcome.Connected], whose caller takes it over. */
    private suspend fun tryConnect(connection: SshConnection, current: Host): ConnectOutcome = try {
        connection.connect()
        ConnectOutcome.Connected
    } catch (e: CancellationException) {
        closeQuietly(connection)
        throw e
    } catch (e: HostKeyFirstUse) {
        closeQuietly(connection)
        if (!confirmFirstUse(current.address, e.fingerprint, e.keyType)) {
            ConnectOutcome.Failed("Connection not trusted.")
        } else {
            ConnectOutcome.Retry(current.copy(pinnedHostKey = e.fingerprint))
        }
    } catch (e: HostKeyMismatch) {
        closeQuietly(connection)
        hostAdmin.recordIdentityChange(current.id, IdentityChange(e.expected, e.got, e.keyType))
        ConnectOutcome.Failed(hostKeyMismatchMessage(e.expected, e.got))
    } catch (e: Exception) {
        closeQuietly(connection)
        Log.w("Deck", "Connect failed for ${current.address}", e)
        ConnectOutcome.Failed("Could not connect to ${current.address}. Check the address, port, and network.")
    }

    private suspend fun pollOnce(connection: SshConnection, current: Host) {
        try {
            val result = Poller(current.id, connection, SyncTracker(StatusTracker())).tick(current.sessions)
            _testState.value = TestConnectionState.Connected(current, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Deck", "Could not list sessions on ${current.address}", e)
            fail(current.id, "Could not list sessions on ${current.name}.")
        } finally {
            closeQuietly(connection)
        }
    }

    /** SSH teardown never runs on the caller's (Main) thread - fix round 1, important #2. */
    private suspend fun closeQuietly(connection: SshConnection) {
        withContext(Dispatchers.IO + NonCancellable) { runCatching { connection.close() } }
    }

    /** Stops any previous link and starts a fresh one for [host] - see [DeckController.restartLink]
     * for why a missing key file becomes an ordinary retried-with-backoff failure instead of a
     * crash, and how an already-selected session re-attaches onto the fresh terminal connection
     * (fix round 1, important #2). */
    private fun restartLink(host: Host) = deck.restartLink(host)

    override fun onCleared() {
        super.onCleared()
        deck.stop()
    }
}
