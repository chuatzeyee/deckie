// R21: the terminal's own SSH connection, kept entirely separate from HostLink's polling
// connection. Mirrors the reviewed Task 2 spike's wiring (android/app/src/debug/.../spike/
// SshTerminalSession.kt) but goes through the production Exec/Pty/SshConnection abstractions
// instead of talking to sshj directly, and reuses one SshConnection across session switches
// instead of opening a new transport per session - unless that connection has died.
package com.chuatzeyee.deck.ssh

import com.chuatzeyee.deck.core.Host
import com.chuatzeyee.deck.core.Tmux
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator

/** What [TerminalConnection] has to show in the terminal pane right now. */
sealed interface TerminalState {
    /** Nothing attached yet. */
    data object Idle : TerminalState
    data object Connecting : TerminalState
    data class Attached(val emulator: TerminalEmulator) : TerminalState
    data class Failed(val message: String) : TerminalState
}

/**
 * Builds one attach's [TerminalEmulator], given its initial size and the callbacks it must wire
 * straight through to the real factory call unchanged. Supplied by the UI layer so this ssh-layer
 * file never depends on Compose's `Color` (fix round 1 minor) - the caller decides colors.
 */
typealias TerminalEmulatorFactoryFn = (
    rows: Int,
    cols: Int,
    onKeyboardInput: (ByteArray) -> Unit,
    onResize: (TerminalDimensions) -> Unit,
) -> TerminalEmulator

/**
 * One terminal connection for [host]: at most one live SSH transport, reused across
 * [attach]-to-a-different-session calls (only the pty and emulator are recreated - "Switching
 * sessions closes old Pty, opens new Pty on the same terminal connection"). [close] tears down the
 * transport too, for leaving the Deck screen or `onCleared`. Every pty write/resize runs on
 * [writeDispatcher], a single-threaded, order-preserving view of IO: termlib's
 * `onKeyboardInput`/`onResize` callbacks fire on the main looper, and writing to a socket there
 * would throw `NetworkOnMainThreadException` or ANR.
 *
 * [pty] is `@Volatile` and every background callback captures its own attach's pty into a local
 * val up front, rather than re-reading this field inside a launched coroutine: a stale reader or a
 * queued keystroke from an attach that has since been replaced must act on (or fail against) the
 * pty it was actually created for, never silently redirect to whatever pty happens to be live by
 * the time it runs (fix round 1, important #3).
 */
class TerminalConnection(
    private val host: Host,
    private val keys: suspend () -> KeyProvider,
    private val scope: CoroutineScope,
    private val createEmulator: TerminalEmulatorFactoryFn,
    /** Fires whenever termlib resizes the emulator (rotation, layout change) so the owner can
     * remember the pane's real size - the next session attached opens at that size instead of a
     * hardcoded fallback (fix round 1 minor). */
    private val onDimensionsChanged: (cols: Int, rows: Int) -> Unit = { _, _ -> },
) {
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var connection: SshConnection? = null
    @Volatile private var pty: Pty? = null
    private var emulator: TerminalEmulator? = null
    private var readerJob: Job? = null
    private var lastAttempt: Triple<String, Int, Int>? = null

    /** Serializes [attach]/[retry]/[close] against each other - [select]-triggered attaches can
     * fire in quick succession (fast session switching) and must not interleave their teardown/
     * setup of [connection]/[pty]. */
    private val opLock = Mutex()

    /** Holds keyboard and extra-keys input back while a one-tap answer is in flight - see
     * [InputGate]. Every write to the pty goes through it. */
    val inputGate = InputGate()

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Idle)
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    /** Opens [session] sized [cols]x[rows]: reuses the live SSH connection if there is one
     * (closing only the previous pty/emulator), connects fresh otherwise. */
    suspend fun attach(session: String, cols: Int, rows: Int): Unit = opLock.withLock { attachLocked(session, cols, rows) }

    /** The terminal pane's "tap to retry": re-attempts the most recent [attach], reconnecting the
     * transport from scratch. No-op if nothing has been attached yet. */
    suspend fun retry(): Unit = opLock.withLock {
        val attempt = lastAttempt ?: return@withLock
        dropConnection()
        attachLocked(attempt.first, attempt.second, attempt.third)
    }

    private suspend fun attachLocked(session: String, cols: Int, rows: Int) {
        lastAttempt = Triple(session, cols, rows)
        _state.value = TerminalState.Connecting
        closePty()
        try {
            val newPty = openAttachPty(session, cols, rows)
            pty = newPty
            startEmulator(newPty, cols, rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail()
        }
    }

    /**
     * Opens the attach pty on the live transport when there is one; a transport that is already
     * dead (phone slept, network switched) - or that turns out dead when the pty open fails on it -
     * is dropped and replaced by a fresh connection, rather than reused into a permanent failure.
     */
    private suspend fun openAttachPty(session: String, cols: Int, rows: Int): Pty {
        val reusable = connection?.takeIf { it.isConnected }
        if (reusable != null) {
            try {
                return withContext(Dispatchers.IO) { reusable.openPty(Tmux.attach(session), cols, rows) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fall through: reconnect once below.
            }
        }
        dropConnection()
        val fresh = SshConnection(host, keys())
        try {
            withContext(Dispatchers.IO) { fresh.connect() }
        } catch (e: Exception) {
            withContext(Dispatchers.IO + NonCancellable) { runCatching { fresh.close() } }
            throw e
        }
        connection = fresh
        return withContext(Dispatchers.IO) { fresh.openPty(Tmux.attach(session), cols, rows) }
    }

    private suspend fun dropConnection() {
        val stale = connection ?: return
        connection = null
        withContext(Dispatchers.IO + NonCancellable) { runCatching { stale.close() } }
    }

    /** Tells the remote pty the pane resized; the emulator itself is resized by termlib. */
    suspend fun resize(cols: Int, rows: Int) {
        val target = pty
        withContext(writeDispatcher) { runCatching { target?.resize(cols, rows) } }
    }

    /** Writes raw bytes to the pty that was live at call time - same path typed input takes; used
     * for the extra-keys row and Ctrl-sticky control bytes. Captured before dispatching so a
     * session switch racing the write can't redirect it onto the next session's pty. */
    fun sendRaw(bytes: ByteArray) {
        val target = pty
        inputGate.offer(bytes) { data -> scope.launch(writeDispatcher) { writeInternal(target, data) } }
    }

    /** Closes the pty and the whole SSH transport. Call when leaving the Deck screen or from
     * `onCleared` - never on session switch alone (see [attach]). */
    suspend fun close(): Unit = opLock.withLock {
        closePty()
        val toClose = connection
        connection = null
        withContext(Dispatchers.IO) { runCatching { toClose?.close() } }
        _state.value = TerminalState.Idle
    }

    private fun startEmulator(newPty: Pty, cols: Int, rows: Int) {
        val newEmulator = createEmulator(
            rows,
            cols,
            { input ->
                inputGate.offer(input) { data -> scope.launch(writeDispatcher) { writeInternal(newPty, data) } }
            },
            { dims ->
                onDimensionsChanged(dims.columns, dims.rows)
                scope.launch(writeDispatcher) { runCatching { newPty.resize(dims.columns, dims.rows) } }
            },
        )
        emulator = newEmulator
        _state.value = TerminalState.Attached(newEmulator)
        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            try {
                while (isActive) {
                    val read = newPty.output.read(buffer)
                    if (read == -1) {
                        showClosed(newPty, newEmulator, "EOF from server")
                        break
                    }
                    newEmulator.writeInput(buffer, 0, read)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Any failure - a dropped socket, or the emulator closed mid-read by a detach race -
                // ends this reader; showClosed only reports it while this pty is still the live one.
                showClosed(newPty, newEmulator, "connection lost")
            }
        }
    }

    /** Only publishes [TerminalState.Failed] if [ownerPty] is still the live pty - an old reader
     * whose pty was already replaced by a newer attach must never clobber that newer attach's live
     * state (fix round 1, important #3). */
    private fun showClosed(ownerPty: Pty, emulator: TerminalEmulator, reason: String) {
        if (!isLivePty(pty, ownerPty)) return
        val message = "\r\n[connection closed: $reason]\r\n".toByteArray()
        runCatching { emulator.writeInput(message, 0, message.size) }
        fail()
    }

    private fun fail() {
        _state.value = TerminalState.Failed("terminal disconnected — tap to retry")
    }

    private fun writeInternal(target: Pty?, bytes: ByteArray) {
        try {
            target?.input?.write(bytes)
            target?.input?.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (target != null && isLivePty(pty, target)) fail()
        }
    }

    private suspend fun closePty() {
        readerJob?.cancel()
        readerJob = null
        val closingPty = pty
        val closingEmulator = emulator
        pty = null
        emulator = null
        withContext(Dispatchers.IO) { runCatching { closingPty?.close() } }
        runCatching { closingEmulator?.close() }
    }
}

/** Whether [ownerPty] - the pty a background reader/writer was created for - is still the one
 * [currentPty] live on the connection: reference identity, never structural equality, since two
 * opens must never be treated as "the same pty" even if a fake happened to compare equal (fix
 * round 1, important #3; see `TerminalConnectionTest`). */
internal fun isLivePty(currentPty: Pty?, ownerPty: Pty): Boolean = currentPty === ownerPty
