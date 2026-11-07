package com.chuatzeyee.deck.ssh

import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Bridges a blocking [block] into this suspend function via a cancellable continuation: if this
 * coroutine is cancelled for any reason - a `withTimeout` firing, or its own caller cancelling it
 * - [onCancel] runs, unblocking [block]. That matters because most blocking I/O this app deals
 * with (sshj's channel stream reads) simply ignores `Thread.interrupt()`; the only thing that
 * actually unblocks it is closing the stream/channel it's reading from, which is what callers are
 * expected to do in [onCancel].
 *
 * `invokeOnCancellation` handlers must be fast and non-blocking - [onCancel] here is typically a
 * channel/session close, which does a blocking socket write and can't run inline on whatever
 * thread cancellation happens to fire on (which may not even permit blocking I/O, e.g. Android's
 * main thread). So it's dispatched to its own background thread, fire-and-forget.
 */
internal suspend fun <T> runBlockingCancellable(onCancel: () -> Unit, block: () -> T): T =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            Thread({ runCatching(onCancel) }, "deck-ssh-cancel-cleanup").apply { isDaemon = true; start() }
        }
        Thread({ cont.resumeWith(runCatching(block)) }, "deck-ssh-blocking-bridge")
            .apply { isDaemon = true; start() }
    }
