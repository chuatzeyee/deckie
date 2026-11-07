package com.chuatzeyee.deck.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Pipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CancellableBlockingTest {

    // Nothing is ever written to this pipe, so a plain read() on it blocks forever until the
    // stream itself is closed - the same shape as an sshj channel stream read during an exec.
    @Test fun timeout_closes_the_resource_which_unblocks_the_read() = runTest {
        val input = PipedInputStream(PipedOutputStream())
        val closed = AtomicBoolean(false)

        try {
            withTimeout(50) {
                runBlockingCancellable(onCancel = { closed.set(true); input.close() }) { input.read() }
            }
            throw AssertionError("expected TimeoutCancellationException")
        } catch (expected: TimeoutCancellationException) {
            // expected
        }
        assertTrue(closed.get())
    }

    @Test fun completes_normally_when_never_cancelled() = runTest {
        val output = PipedOutputStream()
        val input = PipedInputStream(output)
        output.write(42)
        val result = runBlockingCancellable(onCancel = { throw AssertionError("should not cancel") }) { input.read() }
        assertEquals(42, result)
    }

    /**
     * Real wall-clock test (runBlocking, not runTest's virtual time): proves onCancel actually
     * runs off the cancelling thread and that its close() genuinely unblocks the background
     * thread stuck in [block] - not just that the coroutine itself gets promptly cancelled, which
     * would happen regardless of whether onCancel ever ran.
     *
     * Uses a [Pipe.SourceChannel], not [PipedInputStream]: closing the latter from another thread
     * doesn't actually wake a thread blocked in its read() (it only checks the *writer* side
     * closing), so it can't tell a working close()-unblocks-it fix from a no-op one. An
     * interruptible NIO channel genuinely throws out of a blocked read when closed concurrently -
     * the same contract sshj's own channel streams rely on.
     */
    @Test fun onCancel_runs_off_thread_and_really_unblocks_the_blocked_read() = runBlocking {
        val source = Pipe.open().source()
        val readReturned = CountDownLatch(1)
        val onCancelThreadName = AtomicReference<String>()

        val job = launch(Dispatchers.Default) {
            runBlockingCancellable(
                onCancel = {
                    onCancelThreadName.set(Thread.currentThread().name)
                    source.close()
                },
            ) {
                try {
                    source.read(ByteBuffer.allocate(1))
                } finally {
                    readReturned.countDown()
                }
            }
        }

        delay(50) // let the read actually start blocking before cancelling
        job.cancel()

        // Real, bounded wait for the background thread to notice the close() and return - if
        // onCancel ran inline/blocked instead of firing on its own thread, or never actually
        // closed the channel, this would time out.
        assertTrue(readReturned.await(2, TimeUnit.SECONDS))
        assertNotEquals(Thread.currentThread().name, onCancelThreadName.get())
    }
}
