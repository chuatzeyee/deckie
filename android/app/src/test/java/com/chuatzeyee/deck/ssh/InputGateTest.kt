package com.chuatzeyee.deck.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class InputGateTest {

    private val delivered = mutableListOf<String>()
    private fun deliver(tag: String): (ByteArray) -> Unit = { bytes -> delivered += tag + ":" + String(bytes) }

    @Test fun open_gate_delivers_immediately() {
        val gate = InputGate()
        gate.offer("a".toByteArray(), deliver("p"))
        assertEquals(listOf("p:a"), delivered)
    }

    @Test fun held_gate_queues_until_release_then_flushes_in_order() {
        val gate = InputGate()
        gate.hold()
        gate.offer("a".toByteArray(), deliver("p"))
        gate.offer("b".toByteArray(), deliver("p"))
        assertEquals(emptyList<String>(), delivered)
        gate.release()
        gate.offer("c".toByteArray(), deliver("p"))
        assertEquals(listOf("p:a", "p:b", "p:c"), delivered)
    }

    @Test fun queued_input_keeps_the_target_it_was_typed_for() {
        val gate = InputGate()
        gate.hold()
        gate.offer("a".toByteArray(), deliver("old"))
        gate.release()
        assertEquals(listOf("old:a"), delivered)
    }

    @Test fun queued_bytes_are_copied_so_a_reused_buffer_cannot_change_them() {
        val gate = InputGate()
        val buffer = "a".toByteArray()
        gate.hold()
        gate.offer(buffer, deliver("p"))
        buffer[0] = 'z'.code.toByte()
        gate.release()
        assertEquals(listOf("p:a"), delivered)
    }

    @Test fun nested_holds_release_only_when_all_are_released() {
        val gate = InputGate()
        gate.hold()
        gate.hold()
        gate.offer("a".toByteArray(), deliver("p"))
        gate.release()
        assertEquals(emptyList<String>(), delivered)
        gate.release()
        assertEquals(listOf("p:a"), delivered)
    }

    @Test fun extra_release_is_harmless() {
        val gate = InputGate()
        gate.release()
        gate.offer("a".toByteArray(), deliver("p"))
        assertEquals(listOf("p:a"), delivered)
    }
}
