package com.chuatzeyee.deck.poll

import com.chuatzeyee.deck.core.Host
import com.chuatzeyee.deck.core.Tmux
import com.chuatzeyee.deck.ssh.Exec
import com.chuatzeyee.deck.ssh.ExecResult
import com.chuatzeyee.deck.ssh.HostKeyMismatch
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostLinkTest {

    private val host = Host(id = "h1", name = "vm", address = "1.2.3.4", user = "dev", keyId = "k1")

    /**
     * A minimal, always-succeeding [Exec] for the poller's combined date+list command (so every
     * tick sees an empty, valid session list) that starts throwing from its [failAfter]th call
     * onward, and tracks [closed] so tests can verify [HostLink] closes it.
     */
    private class FakeExec(private val failAfter: Int = Int.MAX_VALUE) : Exec, Closeable {
        var calls = 0
        var closed = false

        override suspend fun run(command: String, stdin: ByteArray?): ExecResult {
            calls++
            if (calls > failAfter) throw IOException("ssh broke")
            return ExecResult(0, "1700000000\n", "")
        }

        override fun close() {
            closed = true
        }
    }

    @Test fun up_records_when_its_poll_completed() = runTest {
        val link = HostLink(
            host, { FakeExec() }, this, intervalMs = { 2000L }, chosen = { emptyList() },
            ioDispatcher = StandardTestDispatcher(testScheduler), clock = { 1_234L },
        )
        link.start()
        runCurrent()
        assertEquals(1_234L, (link.state.value as LinkState.Up).atMs)
        link.stop()
        runCurrent()
    }

    @Test fun failure_goes_down_with_backoff_then_recovers() = runTest {
        var attempt = 0
        val connect: suspend () -> Exec = {
            attempt++
            if (attempt < 3) throw IOException("connect failed $attempt") else FakeExec()
        }
        val link = HostLink(host, connect, this, intervalMs = { 2000L }, chosen = { emptyList() }, ioDispatcher = StandardTestDispatcher(testScheduler))

        link.start()
        runCurrent()
        val first = link.state.value as LinkState.Down
        assertEquals(1, first.retryInSec)

        advanceTimeBy(1000)
        runCurrent()
        val second = link.state.value as LinkState.Down
        assertEquals(2, second.retryInSec)

        advanceTimeBy(2000)
        runCurrent()
        assertTrue(link.state.value is LinkState.Up)

        link.stop()
        runCurrent()
    }

    @Test fun down_never_reports_working() = runTest {
        val exec = FakeExec(failAfter = 1)
        val link = HostLink(host, connect = { exec }, scope = this, intervalMs = { 2000L }, chosen = { emptyList() }, ioDispatcher = StandardTestDispatcher(testScheduler))

        link.start()
        runCurrent()
        assertTrue(link.state.value is LinkState.Up) // first tick succeeds

        advanceTimeBy(2000)
        runCurrent()
        // Second tick throws; the state must become Down, never an Up carrying stale views -
        // guaranteed by LinkState.Down having no result/views field at all.
        assertTrue(link.state.value is LinkState.Down)

        link.stop()
        runCurrent()
    }

    @Test fun kick_retries_immediately_instead_of_waiting_out_the_backoff() = runTest {
        var attempt = 0
        val connect: suspend () -> Exec = {
            attempt++
            if (attempt == 1) throw IOException("boom") else FakeExec()
        }
        val link = HostLink(host, connect, this, intervalMs = { 2000L }, chosen = { emptyList() }, ioDispatcher = StandardTestDispatcher(testScheduler))

        link.start()
        runCurrent()
        assertTrue(link.state.value is LinkState.Down) // would otherwise wait out a 1s backoff

        link.kick()
        runCurrent()

        assertTrue(link.state.value is LinkState.Up)
        assertEquals(0L, currentTime) // retried without waiting any of the 1s backoff out

        link.stop()
        runCurrent()
    }

    /** Always reports one live tmux session, "api", on the host - so whether it shows up in a
     * tick's [PollResult.views] depends only on whether `chosen` named it that tick. */
    private class OneSessionExec : Exec {
        override suspend fun run(command: String, stdin: ByteArray?): ExecResult {
            if (command == "date +%s && " + Tmux.LIST) {
                val row = listOf("api", "1699999900", "80", "24", "/tmp", "claude").joinToString("\u001f")
                return ExecResult(0, "1700000000\n$row", "")
            }
            return ExecResult(0, "", "") // capture-pane: no question
        }
    }

    @Test fun chosen_is_re_read_every_tick_so_a_changed_result_is_picked_up_after_kick() = runTest {
        var chosenNames = emptyList<String>()
        val link = HostLink(host, connect = { OneSessionExec() }, scope = this, intervalMs = { 2000L }, chosen = { chosenNames }, ioDispatcher = StandardTestDispatcher(testScheduler))

        link.start()
        runCurrent()
        assertEquals(emptyList<String>(), (link.state.value as LinkState.Up).result.views.map { it.name })

        // Mutating what the supplier returns, by itself, changes nothing until the next tick -
        // this is the bug fix round 1 closes: HostLink must call `chosen()` fresh, not reuse a
        // list it captured once at construction.
        chosenNames = listOf("api")
        link.kick() // forces that next tick to happen now, instead of waiting out the 2s interval
        runCurrent()

        assertEquals(listOf("api"), (link.state.value as LinkState.Up).result.views.map { it.name })

        link.stop()
        runCurrent()
    }

    @Test fun closes_previous_connection_before_reconnecting_and_on_stop() = runTest {
        val execs = mutableListOf<FakeExec>()
        val connect: suspend () -> Exec = { FakeExec(failAfter = 1).also { execs.add(it) } }
        val link = HostLink(host, connect, this, intervalMs = { 10L }, chosen = { emptyList() }, ioDispatcher = StandardTestDispatcher(testScheduler))

        link.start()
        runCurrent() // connect #1, tick #1 succeeds -> Up, waits 10ms
        assertEquals(execs[0], link.exec)

        advanceTimeBy(10)
        runCurrent() // tick #2 fails (its 2nd call on the same exec) -> Down, waits out backoff
        assertTrue(execs[0].closed) // closed immediately on tick failure, so `exec` is null while Down
        assertEquals(null, link.exec)

        advanceTimeBy(1000)
        runCurrent() // backoff elapses -> reconnect
        assertEquals(2, execs.size)
        assertEquals(execs[1], link.exec)

        link.stop()
        runCurrent()
        assertTrue(execs[1].closed)
    }

    /** The exact contract `DeckRuntime.ensureLink`'s reuse path depends on (fix round 2,
     * important): pausing (`stop()`) and resuming (`start()`) the SAME [HostLink] instance - not a
     * freshly built one - must work, so a caller that cached the instance (and anything reading
     * through it, like `DeckRuntime`'s `Exec`/`Sender` wrappers) stays valid across a
     * background/toggle-off pause. */
    @Test fun stop_then_start_on_the_same_instance_resumes_polling() = runTest {
        val exec = FakeExec()
        val connect: suspend () -> Exec = { exec }
        val link = HostLink(host, connect, this, intervalMs = { 10L }, chosen = { emptyList() }, ioDispatcher = StandardTestDispatcher(testScheduler))

        link.start()
        runCurrent()
        assertTrue(link.state.value is LinkState.Up)
        assertEquals(exec, link.exec)

        link.stop()
        runCurrent()
        assertEquals(null, link.exec) // connection closed while paused

        link.start() // same `link` instance - no new HostLink built
        runCurrent()
        assertTrue(link.state.value is LinkState.Up) // resumes polling on its own
        assertEquals(exec, link.exec) // reconnected via the same `connect` lambda

        link.stop()
        runCurrent()
    }

    @Test fun stop_while_connect_is_suspended_does_not_publish_down_and_closes_the_exec_returned_later() = runTest {
        // A connect() that suspends via a plain (non-cancellable) continuation: resuming it later
        // simulates a real connect finishing its work right after stop() already requested
        // cancellation, before the coroutine's next cooperative cancellation check.
        var resumeConnect: Continuation<Exec>? = null
        val exec = FakeExec()
        val connect: suspend () -> Exec = { suspendCoroutine { cont -> resumeConnect = cont } }
        val link = HostLink(host, connect, this, intervalMs = { 2000L }, chosen = { emptyList() }, ioDispatcher = StandardTestDispatcher(testScheduler))

        link.start()
        runCurrent() // publishes Connecting, then suspends inside connect()
        assertTrue(link.state.value is LinkState.Connecting)

        link.stop()
        runCurrent()
        assertTrue(link.state.value !is LinkState.Down) // no spurious Down from the cancelled attempt
        assertFalse(exec.closed) // connect() hasn't handed it over yet

        resumeConnect?.resume(exec) // connect() "returns" after stop() already ran
        runCurrent()

        assertTrue(link.state.value is LinkState.Connecting) // still not Down, and not a stale Up either
        assertTrue(exec.closed) // the exec a cancelled loop picked up right before dying is still closed
    }

    @Test fun stop_then_start_does_not_let_the_old_loop_overwrite_the_new_states() = runTest {
        var resumeOldConnect: Continuation<Exec>? = null
        val staleExec = FakeExec()
        var useOld = true
        val connect: suspend () -> Exec = {
            if (useOld) suspendCoroutine { cont -> resumeOldConnect = cont } else FakeExec()
        }
        val link = HostLink(host, connect, this, intervalMs = { 2000L }, chosen = { emptyList() }, ioDispatcher = StandardTestDispatcher(testScheduler))

        link.start()
        runCurrent() // generation 1 suspended inside connect()
        link.stop()
        runCurrent()

        useOld = false
        link.start()
        runCurrent() // generation 2 connects and ticks successfully
        assertTrue(link.state.value is LinkState.Up)
        val liveExec = link.exec

        resumeOldConnect?.resume(staleExec) // generation 1's connect() finally completes
        runCurrent()

        assertTrue(link.state.value is LinkState.Up) // generation 2's Up survives untouched
        assertEquals(liveExec, link.exec) // generation 1 never got to adopt its stale exec
        assertTrue(staleExec.closed)

        link.stop()
        runCurrent()
    }

    @Test fun backoff_caps_at_30_seconds() = runTest {
        val connect: suspend () -> Exec = { throw IOException("down") }
        val link = HostLink(host, connect, this, intervalMs = { 2000L }, chosen = { emptyList() }, ioDispatcher = StandardTestDispatcher(testScheduler))

        link.start()
        runCurrent()

        val seen = mutableListOf<Int>()
        repeat(8) {
            val down = link.state.value as LinkState.Down
            seen.add(down.retryInSec)
            advanceTimeBy(down.retryInSec * 1000L)
            runCurrent()
        }

        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30, 30), seen)

        link.stop()
        runCurrent()
    }

    @Test fun identity_changed_stops_retrying_and_only_a_fresh_start_reconnects() = runTest {
        var attempt = 0
        val connect: suspend () -> Exec = {
            attempt++
            if (attempt == 1) throw HostKeyMismatch("SHA256:old", "SHA256:new", "ED25519") else FakeExec()
        }
        val link = HostLink(host, connect, this, intervalMs = { 2000L }, chosen = { emptyList() }, ioDispatcher = StandardTestDispatcher(testScheduler))

        link.start()
        runCurrent()
        val changed = link.state.value as LinkState.IdentityChanged
        assertEquals("SHA256:old", changed.expected)
        assertEquals("SHA256:new", changed.got)
        assertEquals("ED25519", changed.keyType)

        advanceTimeBy(60_000) // well past any backoff - it must not have silently retried
        runCurrent()
        assertTrue(link.state.value is LinkState.IdentityChanged)
        assertEquals(1, attempt)

        link.start() // only an explicit fresh start reconnects
        runCurrent()
        assertTrue(link.state.value is LinkState.Up)
        assertEquals(2, attempt)

        link.stop()
        runCurrent()
    }

    @Test fun stop_closes_the_connection_off_the_calling_thread() = runTest {
        val closeThreadName = AtomicReference<String>()
        val closedLatch = CountDownLatch(1)
        val exec = object : Exec, Closeable {
            override suspend fun run(command: String, stdin: ByteArray?) = ExecResult(0, "1700000000\n", "")
            override fun close() {
                closeThreadName.set(Thread.currentThread().name)
                closedLatch.countDown()
            }
        }
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "deck-test-io") }
        val link = HostLink(host, connect = { exec }, scope = this, intervalMs = { 2000L }, chosen = { emptyList() }, ioDispatcher = executor.asCoroutineDispatcher())

        link.start()
        runCurrent()
        assertTrue(link.state.value is LinkState.Up)
        val callingThreadName = Thread.currentThread().name

        link.stop() // must return without running close() itself
        assertTrue("close() never ran", closedLatch.await(2, TimeUnit.SECONDS))
        // coroutines debug mode suffixes the thread name with " @coroutine#N", so compare by prefix
        assertTrue(closeThreadName.get().startsWith("deck-test-io"))
        assertNotEquals(callingThreadName, closeThreadName.get())

        executor.shutdown()
    }
}
