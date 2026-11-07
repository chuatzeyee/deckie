package com.chuatzeyee.deck.ui

import com.chuatzeyee.deck.core.Status
import com.chuatzeyee.deck.poll.PollResult
import com.chuatzeyee.deck.poll.SessionView
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPickerLogicTest {

    private fun view(name: String) = SessionView(hostId = "h", name = name, status = Status.Idle, question = null, path = "~")

    @Test
    fun `pickerEntries checks chosen sessions and leaves others unchecked`() {
        val result = PollResult(
            views = listOf(view("work"), view("logs")),
            notAdded = listOf("scratch"),
            alerts = emptyList(),
            noServer = false,
        )
        assertEquals(
            listOf(
                PickerEntry("work", checked = true, running = true),
                PickerEntry("logs", checked = true, running = true),
                PickerEntry("scratch", checked = false, running = true),
            ),
            pickerEntries(listOf("work", "logs"), result),
        )
    }

    @Test
    fun `a chosen session absent from this tick stays checked and marked not running`() {
        val result = PollResult(
            views = listOf(view("work")),
            notAdded = emptyList(),
            alerts = emptyList(),
            noServer = false,
        )
        assertEquals(
            listOf(
                PickerEntry("work", checked = true, running = true),
                PickerEntry("logs", checked = true, running = false),
            ),
            pickerEntries(listOf("work", "logs"), result),
        )
    }

    @Test
    fun `toggleEntry flips only the targeted row`() {
        val entries = listOf(PickerEntry("a", true, true), PickerEntry("b", false, true))
        val toggled = toggleEntry(entries, 1)
        assertEquals(listOf(PickerEntry("a", true, true), PickerEntry("b", true, true)), toggled)
    }

    @Test
    fun `moveEntry swaps adjacent rows`() {
        val entries = listOf(PickerEntry("a", true, true), PickerEntry("b", false, true), PickerEntry("c", true, true))
        assertEquals(
            listOf(PickerEntry("b", false, true), PickerEntry("a", true, true), PickerEntry("c", true, true)),
            moveEntry(entries, 0, 1),
        )
    }

    @Test
    fun `moveEntry is a no-op past either end`() {
        val entries = listOf(PickerEntry("a", true, true), PickerEntry("b", false, true))
        assertEquals(entries, moveEntry(entries, 0, -1))
        assertEquals(entries, moveEntry(entries, 1, 1))
    }

    @Test
    fun `chosenNames returns only checked rows in order`() {
        val entries = listOf(PickerEntry("a", true, true), PickerEntry("b", false, true), PickerEntry("c", true, true))
        assertEquals(listOf("a", "c"), chosenNames(entries))
    }
}
