package com.chuatzeyee.deck.ssh

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps [close] so it runs at most once, no matter how many times - or from how many threads -
 * the returned function is called. Needed because an exec's timeout/cancellation cleanup
 * (`onCancel` in [runBlockingCancellable], firing fire-and-forget on its own background thread)
 * and the normal `finally` close both race to close the same cmd/session on every timed-out exec.
 */
internal fun closeOnce(close: () -> Unit): () -> Unit {
    val closed = AtomicBoolean(false)
    return { if (closed.compareAndSet(false, true)) close() }
}
