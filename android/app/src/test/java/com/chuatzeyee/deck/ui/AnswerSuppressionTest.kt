package com.chuatzeyee.deck.ui

import com.chuatzeyee.deck.core.Question
import com.chuatzeyee.deck.core.Status
import com.chuatzeyee.deck.poll.LinkState
import com.chuatzeyee.deck.poll.PollResult
import com.chuatzeyee.deck.poll.SessionView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AnswerSuppressionTest {

    private val asked = Question("api", "Do you want to proceed?", listOf("Yes", "No"), 0, "")
    private val other = asked.copy(title = "Do you want to make this edit?")

    private fun view(name: String, question: Question?) =
        SessionView(hostId = "h", name = name, status = Status.Waiting, question = question, path = "/")

    private fun up(vararg views: SessionView) =
        LinkState.Up(PollResult(views.toList(), emptyList(), emptyList(), noServer = false), atMs = 0L)

    @Test fun answered_question_still_on_screen_stays_suppressed() {
        val suppressed = mapOf("api" to asked.fingerprint)
        assertEquals(suppressed, pruneSuppressed(suppressed, up(view("api", asked))))
    }

    @Test fun suppression_ends_once_a_poll_sees_the_question_gone() =
        assertEquals(emptyMap<String, String>(), pruneSuppressed(mapOf("api" to asked.fingerprint), up(view("api", null))))

    @Test fun suppression_ends_on_a_different_question() =
        assertEquals(emptyMap<String, String>(), pruneSuppressed(mapOf("api" to asked.fingerprint), up(view("api", other))))

    @Test fun suppression_ends_when_the_session_left_the_rail() =
        assertEquals(emptyMap<String, String>(), pruneSuppressed(mapOf("api" to asked.fingerprint), up(view("x", null))))

    @Test fun a_poll_that_did_not_happen_keeps_suppression() {
        val suppressed = mapOf("api" to asked.fingerprint)
        assertEquals(suppressed, pruneSuppressed(suppressed, LinkState.Down("lost", 1)))
        assertEquals(suppressed, pruneSuppressed(suppressed, LinkState.Connecting))
    }

    @Test fun suppressed_question_is_hidden_from_its_session_only() {
        val state = up(view("api", asked), view("b", asked.copy(session = "b")))
        val hidden = hideSuppressed(state, mapOf("api" to asked.fingerprint)) as LinkState.Up
        assertNull(hidden.result.views[0].question)
        assertEquals("b", hidden.result.views[1].question?.session)
    }

    @Test fun a_new_question_in_the_same_session_is_shown() {
        val hidden = hideSuppressed(up(view("api", other)), mapOf("api" to asked.fingerprint)) as LinkState.Up
        assertEquals(other, hidden.result.views[0].question)
    }

    @Test fun nothing_suppressed_returns_the_same_state() {
        val state = up(view("api", asked))
        assertSame(state, hideSuppressed(state, emptyMap()))
    }
}
