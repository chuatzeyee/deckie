package com.chuatzeyee.deck.ssh

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class CloseOnceTest {

    @Test fun two_concurrent_callers_run_the_close_lambda_exactly_once() {
        val calls = AtomicInteger(0)
        val close = closeOnce { calls.incrementAndGet() }

        val bothReady = CountDownLatch(2)
        val go = CountDownLatch(1)
        val threads = List(2) {
            Thread {
                bothReady.countDown()
                go.await()
                close()
            }
        }

        threads.forEach { it.start() }
        bothReady.await()
        go.countDown() // release both threads at (as close to) the same instant as possible
        threads.forEach { it.join() }

        assertEquals(1, calls.get())
    }

    @Test fun sequential_calls_also_run_the_close_lambda_exactly_once() {
        val calls = AtomicInteger(0)
        val close = closeOnce { calls.incrementAndGet() }

        close()
        close()
        close()

        assertEquals(1, calls.get())
    }
}
