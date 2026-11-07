package com.chuatzeyee.deck.watch

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.chuatzeyee.deck.core.Host
import com.chuatzeyee.deck.core.Sender
import com.chuatzeyee.deck.core.pickAutoStartHost
import com.chuatzeyee.deck.data.HostRepository
import com.chuatzeyee.deck.data.resetOnCorruption
import com.chuatzeyee.deck.poll.HostLink
import com.chuatzeyee.deck.poll.LinkState
import com.chuatzeyee.deck.poll.SessionView
import com.chuatzeyee.deck.ssh.Exec
import com.chuatzeyee.deck.ssh.KeyVault
import com.chuatzeyee.deck.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.hostDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "deck_hosts",
    corruptionHandler = resetOnCorruption { Log.w("Deck", "Host store unreadable - reset to empty", it) },
)

private const val VISIBLE_INTERVAL_MS = 2_000L
private const val BACKGROUND_INTERVAL_MS = 5_000L
private const val STALE_AFTER_INTERVALS = 2

/**
 * App-scoped home for the polling side of Deck (ruling R24): one [HostLink]/[Sender] pair for the
 * active host, [HostRepository] and [KeyVault], all owned here instead of by a ViewModel so
 * [com.chuatzeyee.deck.ui.AppViewModel]/[com.chuatzeyee.deck.ui.DeckController] and [WatchService]
 * share the same live connection rather than each opening their own. Survives configuration
 * changes and the activity going away entirely - it's held by [com.chuatzeyee.deck.DeckApp], not
 * any `ViewModel`.
 *
 * The active [HostLink]'s poll interval is [VISIBLE_INTERVAL_MS] while [visible] (the app has a
 * resumed activity) and [BACKGROUND_INTERVAL_MS] otherwise. The link itself only runs at all while
 * [visible] or the "Watch in background" toggle ([watchPrefs]) is on - see the `init` block -
 * closing the SSH connection outright once neither is true, rather than just polling it slower.
 */
class DeckRuntime(context: Context) {
    private val appContext = context.applicationContext

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repo: HostRepository = HostRepository(appContext.hostDataStore, onCorrupt = { Log.w("Deck", "Corrupt host store", it) })
    val keyVault: KeyVault = KeyVault(appContext)
    val watchPrefs: WatchPrefs = WatchPrefs(appContext)

    private val _visible = MutableStateFlow(false)
    /** Whether `MainActivity` is currently resumed - set by `DeckApp`'s activity-lifecycle callbacks. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _link = MutableStateFlow<LinkState>(LinkState.Connecting)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _alerts = MutableSharedFlow<List<SessionView>>(replay = 0, extraBufferCapacity = 16)
    /** Emits once per poll tick whose [com.chuatzeyee.deck.poll.PollResult.alerts] is non-empty - a
     * [SharedFlow], not [link]'s conflated [StateFlow], so [WatchService] never drops a batch (a
     * fast run of ticks overwriting [link]'s value before it's collected) and never re-posts a
     * stale batch just from resubscribing (fix round 1, important #5). */
    val alerts: SharedFlow<List<SessionView>> = _alerts.asSharedFlow()

    private var currentLink: HostLink? = null
    private var currentExec: Exec? = null
    private var currentSender: Sender? = null
    private var currentHostId: String? = null
    /** The exact [Host] [currentLink] was built from - [ensureLink] only reuses that link while the
     * host's connection details (address, user, key, pinned host key) are unchanged. */
    private var currentHost: Host? = null
    private var linkStateJob: Job? = null

    /** The Deck screen's currently-selected session, recorded by
     * [com.chuatzeyee.deck.ui.DeckController] via [setFocusedSession] - restored onto the link
     * whenever the app becomes visible again, see [setVisible] (fix round 1, important #3). */
    private var focusedSession: String? = null

    /** The live link's [Sender], or `null` while nothing is connected. */
    val sender: Sender? get() = synchronized(this) { currentSender }

    /** The id of the host whose link is (or was last) running, or `null` if none. */
    val activeHostId: String? get() = synchronized(this) { currentHostId }

    /** The live link's polling [Exec] (the same channel [sender] uses), for [com.chuatzeyee.deck.ui.SessionActions]. */
    fun pollingExec(): Exec? = synchronized(this) { currentExec }

    init {
        // Keeps the link running whenever either reason to poll is true, and closes it the
        // moment neither is - see this class's own doc. eligibleHost() is suspend (awaits a real
        // HostRepository emission) so this never races a cold DataStore read with "no host yet"
        // (fix round 1, important #2).
        scope.launch {
            combine(visible, watchPrefs.enabled) { v, w -> v || w }.distinctUntilChanged().collect { shouldRun ->
                val host = if (shouldRun) eligibleHost() else null
                if (host != null) ensureLink(host) else pauseCurrentLink()
            }
        }
    }

    /** Marks whether `MainActivity` is resumed - adjusts the live link's poll interval immediately
     * via [intervalMs] (read fresh on every tick), may start/stop the link (see the `init` block),
     * and un-focuses the live link's session while backgrounded so it can still alert (a focused
     * session never notifies - see [com.chuatzeyee.deck.core.StatusTracker.focus]'s doc) -
     * restoring [focusedSession] once visible again (fix round 1, important #3). */
    fun setVisible(value: Boolean) {
        val changed = _visible.value != value
        _visible.value = value
        if (changed) applyFocusForVisibility(value)
        // Coming back to the front: don't sit out a backoff/interval wait from while we were away.
        if (changed && value) kick()
    }

    /** Makes the live link retry/poll now instead of waiting out its backoff or interval - on
     * resume, and whenever a network becomes available (see `DeckApp`'s network callback). */
    @Synchronized
    fun kick() {
        currentLink?.kick()
    }

    /** How old a [LinkState.Up] may get before the rail shows it as stale: two poll intervals. */
    val staleAfterMs: Long get() = STALE_AFTER_INTERVALS * intervalMs()

    @Synchronized
    private fun applyFocusForVisibility(visible: Boolean) {
        currentLink?.focus(if (visible) focusedSession else null)
    }

    /** Records the Deck screen's selected session (called by `DeckController` on select/
     * leaveDeckScreen/restartLink) and applies it to the live link immediately while [visible] -
     * see [setVisible]'s doc for why it's withheld while backgrounded. */
    @Synchronized
    fun setFocusedSession(name: String?) {
        focusedSession = name
        if (_visible.value) currentLink?.focus(name)
    }

    /** The host whose [HostLink] is (or should be) running: whichever host is already active, if
     * it still exists, else the most recently saved host with a pinned key and chosen sessions -
     * the same rule `AppViewModel`'s R17 auto-start uses, reused so [WatchService] can resume
     * watching the right host even with no Activity alive. Suspends on [HostRepository.hosts]'s
     * real first emission rather than reading a cache that may still be empty, so a cold process
     * start (e.g. [WatchService] restarted by the OS) never sees "no host yet" before the
     * DataStore has actually loaded (fix round 1, important #2). With no active host, prefers the
     * last active one ([HostRepository.lastHostId]) - see [pickAutoStartHost]. */
    suspend fun eligibleHost(): Host? {
        val hosts = repo.hosts.first()
        val activeId = synchronized(this) { currentHostId }
        return hosts.firstOrNull { it.id == activeId } ?: pickAutoStartHost(hosts, repo.lastHostId.first())
    }

    /**
     * Starts (or reuses) [host]'s [HostLink]/[Sender] - shared by the UI and [WatchService] so
     * neither ever opens a second polling connection to the same host (ruling R24). Reuses the
     * existing link - rather than tearing it down and reconnecting - when [host]'s id is already
     * the active one; [HostLink.start] is itself idempotent/restartable, so this is safe whether
     * the existing link is already running or was previously stopped by the `init` watcher above.
     */
    @Synchronized
    fun ensureLink(host: Host): HostLink {
        val existing = currentLink
        if (existing != null && currentHostId == host.id && sameConnection(currentHost, host)) {
            existing.start()
            return existing
        }
        return newLink(host)
    }

    @Synchronized
    private fun newLink(host: Host): HostLink {
        linkStateJob?.cancel()
        currentLink?.stop()
        currentHostId = host.id
        currentHost = host
        scope.launch { repo.setLastHostId(host.id) }
        val hostLink = HostLink(
            host = host,
            connect = {
                val provider = withContext(Dispatchers.IO) { keyVault.provider(host.keyId) }
                SshConnection(host, provider).also { it.connect() }
            },
            scope = scope,
            intervalMs = ::intervalMs,
            chosen = { repo.hosts.first().firstOrNull { it.id == host.id }?.sessions ?: emptyList() },
        )
        currentLink = hostLink
        val exec = Exec { command, stdin -> checkNotNull(hostLink.exec) { "Not connected" }.run(command, stdin) }
        currentExec = exec
        currentSender = Sender(exec)
        linkStateJob = scope.launch {
            hostLink.state.collect { state ->
                _link.value = state
                (state as? LinkState.Up)?.result?.alerts?.takeIf { it.isNotEmpty() }?.let { _alerts.emit(it) }
            }
        }
        hostLink.start()
        // A fresh/reconnected link starts un-focused; restore the screen's current selection onto
        // it right away if the app is visible (fix round 1, important #3).
        applyFocusForVisibility(_visible.value)
        return hostLink
    }

    /**
     * Pauses the active link - called once neither [visible] nor the watch toggle is on any more.
     * Stops [HostLink]'s poll loop/connection but deliberately keeps the same instance (and
     * [currentExec]/[currentSender], which read through it, and [linkStateJob], which keeps
     * collecting it) alive rather than discarding them: [com.chuatzeyee.deck.ui.DeckController]
     * caches these same references for as long as the Deck screen is showing and never re-fetches
     * them just because the app regained visibility, so [ensureLink]'s reuse path depends on
     * [currentLink] staying non-null here - only [newLink] (an actual host change) may replace it
     * (fix round 2, important - discarding them here left a resumed app with a frozen Deck screen:
     * a fresh [HostLink]/[Sender] running underneath while the screen's cached references still
     * pointed at the stopped ones, so answers/session actions all failed "Not connected").
     *
     * Resets [link] back to [LinkState.Connecting] since [HostLink.stop] itself leaves its last
     * published state (e.g. [LinkState.Up]) in place - fix round 1, important #5's "no stale state
     * after stop" still holds.
     */
    @Synchronized
    private fun pauseCurrentLink() {
        currentLink?.stop()
        _link.value = LinkState.Connecting
    }

    /**
     * Drops host [id]'s link if it is the active one (deleting that host): stops it, forgets its
     * [Exec]/[Sender], and resets [link]. A later [ensureLink] for any host starts fresh.
     */
    @Synchronized
    fun forgetHost(id: String) {
        if (currentHostId != id) return
        linkStateJob?.cancel()
        linkStateJob = null
        currentLink?.stop()
        currentLink = null
        currentExec = null
        currentSender = null
        currentHostId = null
        currentHost = null
        _link.value = LinkState.Connecting
    }

    private fun intervalMs(): Long = if (visible.value) VISIBLE_INTERVAL_MS else BACKGROUND_INTERVAL_MS
}

/** Whether a link built for [old] can keep serving [new]: same host and connection details. The
 * chosen sessions don't matter - the link re-reads them every tick. */
private fun sameConnection(old: Host?, new: Host): Boolean =
    old != null && old.copy(sessions = emptyList()) == new.copy(sessions = emptyList())
