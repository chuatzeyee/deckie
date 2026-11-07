package com.chuatzeyee.deck.watch

import com.chuatzeyee.deck.core.Question
import com.chuatzeyee.deck.core.Status
import com.chuatzeyee.deck.poll.SessionView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AlertsTest {

    private fun question(title: String) = Question(
        session = "alpha",
        title = title,
        options = listOf("Yes", "No"),
        selected = 0,
        progress = "",
    )

    private fun view(
        hostId: String = "host1",
        name: String = "alpha",
        status: Status,
        question: Question? = null,
    ) = SessionView(hostId = hostId, name = name, status = status, question = question, path = "~")

    @Test
    fun `waiting alert titles the session and needs-you`() {
        val spec = alertFor(view(status = Status.Waiting, question = question("Run this command?")))
        assertEquals("alpha needs you", spec.title)
    }

    @Test
    fun `waiting alert text is the question's first title line`() {
        val spec = alertFor(view(status = Status.Waiting, question = question("Run this command?\nrm -rf build/")))
        assertEquals("Run this command?", spec.text)
    }

    @Test
    fun `finished alert copy is exact`() {
        val spec = alertFor(view(status = Status.Finished))
        assertEquals("alpha finished", spec.title)
        assertEquals("Claude is waiting for your next prompt", spec.text)
    }

    @Test
    fun `same host and session produce the same id`() {
        val waiting = alertFor(view(status = Status.Waiting, question = question("Q?")))
        val finished = alertFor(view(status = Status.Finished))
        assertEquals(waiting.id, finished.id)
    }

    @Test
    fun `different session produces a different id`() {
        val alpha = alertFor(view(name = "alpha", status = Status.Finished))
        val beta = alertFor(view(name = "beta", status = Status.Finished))
        assertNotEquals(alpha.id, beta.id)
    }

    @Test
    fun `different host produces a different id`() {
        val host1 = alertFor(view(hostId = "host1", status = Status.Finished))
        val host2 = alertFor(view(hostId = "host2", status = Status.Finished))
        assertNotEquals(host1.id, host2.id)
    }

    @Test
    fun `non-notifying status falls back to a plain session title`() {
        val spec = alertFor(view(status = Status.Working))
        assertEquals("alpha", spec.title)
        assertEquals("", spec.text)
    }

    @Test
    fun `watching text reports the session count`() {
        assertEquals("Watching 3 sessions", watchingText(3))
    }

    @Test
    fun `watching text is singular for exactly one session`() {
        assertEquals("Watching 1 session", watchingText(1))
    }
}
