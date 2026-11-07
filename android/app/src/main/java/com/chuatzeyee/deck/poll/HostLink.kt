package com.chuatzeyee.deck.poll

import com.chuatzeyee.deck.core.Host
import com.chuatzeyee.deck.core.StatusTracker
import com.chuatzeyee.deck.ssh.Exec
import com.chuatzeyee.deck.ssh.HostKeyMismatch
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** [HostLink]'s current connection state, in the order a session rail should reflect it. */
sealed interface LinkState {
    /** Connecting (or reconnecting) - no previous [Up] result should be shown while in this state. */
    data object Connecting : LinkState

    /** The last tick succeeded; [result] is this host's current rail content, as of [atMs] (the
     * phone's wall clock when that tick completed - see [com.chuatzeyee.deck.ui.isStale]). */
    data class Up(val result: PollResult, val atMs: Long) : LinkState

    /**
     * The link is down: [reason] is shown to the user, [retryInSec] is when the next retry fires.
     * Deliberately carries no [PollResult]/views - once down, the rail must never keep showing a
     * stale "working" from before the link dropped (see Review Focus 3).
     */
    data class Down(val reason: String, val retryInSec: Int) : LinkState

    /**
     * The server's host key no longer matches [Host.pinnedHostKey] (ruling R18) - never a retry
     * target: [HostLink] stops its loop entirely on this state and only a fresh [HostLink.start]
     * call reconnects. [expected]/[got]/[keyType] are shown in full, never auto-repinned.
     */
    data class IdentityChanged(val expected: String, val got: String, val keyType: String) : LinkState
}

/** Backoff schedule in seconds for consecutive failures (connect or tick): 1, 2, 4, 8, 16, 30, 30, … */
private val BACKOFF_SECONDS = listOf(1, 2, 4, 8, 16, 30)

/**
 * Owns one host's SSH connection and poll loop, reconnecting with backoff on any failure.
 *
 * [connect] is called fresh on every (re)connect attempt; if the [Exec] it returns is also
 * [Closeable] (true for a real `SshConnection`), [HostLink] closes it before reconnecting and on
 * [stop] (ruling R1) - no connection is ever leaked across a retry, including when [stop] cancels
 * a loop that is mid-connect or mid-tick: [loop] closes the current connection from a `finally`
 * block that always runs, even on cancellation. That close is always dispatched onto [ioDispatcher]
 * via a fire-and-forget coroutine on [scope] (with [NonCancellable] standing in for the launched
 * coroutine's own Job, so it survives [scope] itself being cancelled) - so no caller, including
 * [stop] called from the UI thread, ever blocks on SSH teardown (fix round 1, important #2).
 *
 * Every call [loop] makes into [tracker] is serialized by running only in [loop]'s own coroutine;
 * [opened] and [focus] go through the same [SyncTracker], which synchronizes internally, so they
 * are safe to call from any thread/coroutine - e.g. the UI thread - while a poll is in flight.
 * [tracker] itself survives reconnects (a dropped SSH session doesn't forget which sessions are
 * Waiting/Finished).
 *
 * [start]/[stop] are synchronized and generation-counted: cancelling a loop (via [stop], or an
 * implicit stop before a fresh [start]) only *requests* cancellation - cooperative cancellation
 * means the loop's coroutine can still run synchronous code (e.g. publish one more state) before
 * it actually unwinds. Every state publish is tagged with the generation the publishing loop was
 * started under and is dropped if a newer generation (a later [stop] or [start]) has since begun,
 * so a stale, already-cancelled loop can never overwrite a fresher loop's state, and [stop] alone
 * can never result in a spurious [LinkState.Down] appearing after it returns.
 *
 * [chosen] is called fresh on every tick rather than read once from [Host.sessions] at
 * construction (fix round 1, important): a caller that mutates the chosen-sessions list in its own
 * store (session create/rename/kill) needs the very next tick - not just the next full reconnect -
 * to reflect it, and [kick] exists precisely to force that next tick to happen immediately.
 */
class HostLink(
    private val host: Host,
    private val connect: suspend () -> Exec,
    private val scope: CoroutineScope,
    private val intervalMs: () -> Long,
    private val chosen: suspend () -> List<String>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val tracker = SyncTracker(StatusTracker())
    private val _state = MutableStateFlow<LinkState>(LinkState.Connecting)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /** Signals [loop] to stop waiting out its current backoff/interval and retry immediately. */
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var currentExec: Exec? = null

    /** The live connection's [Exec], or `null` while [Connecting]/[Down]/[IdentityChanged]. */
    val exec: Exec? get() = currentExec

    /** Guards [job] and [generation]: both [start] and [stop] must be atomic read-modify-write. */
    private val lock = Any()
    private var job: Job? = null

    /** Bumped by every [start] and [stop]; gates which loop is still allowed to publish [_state]. */
    private var generation = 0

    /** Starts the poll loop, if not already running. */
    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            val myGeneration = ++generation
            job = scope.launch { loop(myGeneration) }
        }
    }

    /** Stops the poll loop and closes the current connection, if any. */
    fun stop() {
        synchronized(lock) {
            generation++ // invalidate the running loop's right to publish, even before it notices cancellation
            job?.cancel()
            job = null
        }
        closeExecAsync()
    }

    /** Cuts short the current backoff/interval wait so the next attempt happens now. */
    fun kick() {
        kicks.trySend(Unit)
    }

    /** Forwards to the shared, thread-safe tracker; see [StatusTracker.opened]. Safe from any thread. */
    fun opened(session: String) = tracker.opened(session)

    /** Forwards to the shared, thread-safe tracker; see [StatusTracker.focus]. Safe from any thread. */
    fun focus(session: String?) = tracker.focus(session)

    private suspend fun loop(generation: Int) {
        var failures = 0
        try {
            while (true) {
                if (!publish(generation, LinkState.Connecting)) return
                closeExecAsync()
                val connected = attempt { connect() }
                val freshExec = connected.getOrNull()
                if (freshExec == null) {
                    val cause = connected.exceptionOrNull()
                    if (cause is HostKeyMismatch) {
                        // Never a retry target (R18): publish once and stop the loop entirely -
                        // only a fresh start() (after the user has seen the dialog) retries.
                        publish(generation, LinkState.IdentityChanged(cause.expected, cause.got, cause.keyType))
                        return
                    }
                    failures = enterDown(generation, cause, failures)
                    continue
                }
                if (!adopt(generation, freshExec)) return
                val poller = Poller(host.id, freshExec, tracker)

                tickLoop@ while (true) {
                    val polled = attempt { poller.tick(chosen()) }
                    val result = polled.getOrNull()
                    if (result == null) {
                        closeExecAsync() // so `exec` is null while Down, matching its doc
                        failures = enterDown(generation, polled.exceptionOrNull(), failures)
                        break@tickLoop
                    }
                    failures = 0
                    if (!publish(generation, LinkState.Up(result, clock()))) return
                    waitInterval(intervalMs())
                }
            }
        } finally {
            // Gated by generation: a newer generation (started after this one went stale) owns
            // `currentExec` now and must not have it ripped out from under it. This only ever
            // does real work when the loop's own scope is cancelled from outside HostLink (e.g. a
            // ViewModel's scope tearing down) without `stop()` having run first - `stop()` and
            // `adopt()` already handle every other cleanup path themselves.
            releaseIfOwned(generation)
        }
    }

    /** Closes and clears `currentExec` only if [generation] still owns it - see [loop]'s `finally`. */
    private fun releaseIfOwned(generation: Int) {
        val toClose = synchronized(lock) {
            if (generation != this.generation) return@synchronized null
            currentExec.also { currentExec = null }
        }
        closeAsync(toClose)
    }

    /** Publishes the [failures]-th consecutive failure as [LinkState.Down] and waits out its
     * backoff (or until [kick]); returns the incremented failure count for the next call. */
    private suspend fun enterDown(generation: Int, cause: Throwable?, failures: Int): Int {
        val retryInSec = BACKOFF_SECONDS.getOrElse(failures) { BACKOFF_SECONDS.last() }
        publish(generation, LinkState.Down(cause?.message ?: "Connection failed", retryInSec))
        waitInterval(retryInSec * 1000L)
        return failures + 1
    }

    /**
     * Adopts [freshExec] as the live connection if [generation] still owns [HostLink]'s state;
     * otherwise a stale/cancelled loop just produced it too late - close it immediately instead
     * of leaving it as (or leaking past) the live connection. Returns whether it was adopted.
     */
    private fun adopt(generation: Int, freshExec: Exec): Boolean {
        val adopted = synchronized(lock) {
            if (generation != this.generation) return@synchronized false
            currentExec = freshExec
            true
        }
        if (!adopted) closeAsync(freshExec)
        return adopted
    }

    /** Writes [newState] if [generation] is still the current one; returns whether it did. */
    private fun publish(generation: Int, newState: LinkState): Boolean = synchronized(lock) {
        if (generation != this.generation) return@synchronized false
        _state.value = newState
        true
    }

    /** Suspends for [ms], or until [kick] is called, whichever comes first. */
    private suspend fun waitInterval(ms: Long) {
        withTimeoutOrNull(ms) { kicks.receive() }
    }

    /** Atomically takes ownership of `currentExec` (so no racing caller can see or double-close
     * it) and closes it - see [closeAsync]. */
    private fun closeExecAsync() {
        val toClose = synchronized(lock) { currentExec.also { currentExec = null } }
        closeAsync(toClose)
    }

    /** Closes [exec] (if [Closeable]) on [ioDispatcher], never on the caller's thread. */
    private fun closeAsync(exec: Exec?) {
        val closeable = exec as? Closeable ?: return
        scope.launch(ioDispatcher + NonCancellable) { runCatching { closeable.close() } }
    }

    /** Like `runCatching`, but never swallows [CancellationException] - it must always propagate. */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
