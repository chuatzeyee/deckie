package com.chuatzeyee.deck.ssh

/**
 * Holds back terminal input while a one-tap answer is being sent to the same session (Review
 * Focus 5): keystrokes typed into the live terminal travel over the terminal's own pty, not the
 * [com.chuatzeyee.deck.core.Sender]'s per-session lock, so without this they could land between
 * the answer's cursor moves and its Enter. While [hold] is in effect, [offer] queues each write
 * (with the delivery target it was made for); [release] flushes the queue in order.
 *
 * Holds nest: the gate only opens once every [hold] has a matching [release]. Delivery happens
 * under the gate's lock so a write offered while the queue is flushing can never overtake it -
 * [offer]'s `deliver` must therefore be quick and non-blocking (e.g. a `launch` onto a writer).
 */
class InputGate {
    private val lock = Any()
    private var holds = 0
    private val queue = ArrayList<Pair<ByteArray, (ByteArray) -> Unit>>()

    fun hold() = synchronized(lock) { holds++ }

    fun release() = synchronized(lock) {
        holds = (holds - 1).coerceAtLeast(0)
        if (holds == 0) {
            queue.forEach { (bytes, deliver) -> deliver(bytes) }
            queue.clear()
        }
    }

    fun offer(bytes: ByteArray, deliver: (ByteArray) -> Unit) = synchronized(lock) {
        if (holds > 0) queue += bytes.copyOf() to deliver else deliver(bytes)
    }
}
