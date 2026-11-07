package com.chuatzeyee.deck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTrackerTest {

    private val t = StatusTracker()

    private val q1 = Question("a", "Proceed?", listOf("Yes", "No"), 0, "")
    private val q2 = Question("a", "Allow this edit?", listOf("Yes", "No"), 0, "")

    private fun obs(
        name: String,
        now: Long,
        activity: Long,
        size: Pair<Int, Int> = 80 to 24,
        question: Question? = null,
        cmd: String = "claude",
    ): Observation =
        Observation(TmuxSession(name, activity, size.first, size.second, "/tmp", cmd), now, question)

    @Test fun not_claude_is_off() = assertEquals(Status.Off, t.observe(obs("a", 100, 99, cmd = "bash")).status)

    @Test fun recent_output_is_working() = assertEquals(Status.Working, t.observe(obs("a", 100, 97)).status)

    @Test fun working_then_quiet_is_finished_and_notifies_once() {
        t.observe(obs("a", 100, 99)); val f = t.observe(obs("a", 120, 99))
        assertEquals(Status.Finished, f.status); assertTrue(f.notify)
        val again = t.observe(obs("a", 130, 99)); assertEquals(Status.Finished, again.status); assertFalse(again.notify)
    }

    @Test fun finished_clears_when_work_resumes_and_notifies_on_next_finish() {
        t.observe(obs("a", 100, 99)); t.observe(obs("a", 120, 99))
        assertEquals(Status.Working, t.observe(obs("a", 130, 129)).status)
        assertTrue(t.observe(obs("a", 150, 129)).notify)
    }

    @Test fun opened_clears_finished() {
        t.observe(obs("a", 100, 99)); t.observe(obs("a", 120, 99)); t.opened("a")
        assertEquals(Status.Idle, t.observe(obs("a", 130, 99)).status)
    }

    @Test fun question_is_waiting_and_notifies_per_fingerprint() {
        assertTrue(t.observe(obs("a", 100, 50, question = q1)).notify)
        assertFalse(t.observe(obs("a", 102, 50, question = q1)).notify)
        assertTrue(t.observe(obs("a", 104, 50, question = q2)).notify)
    }

    @Test fun focused_session_never_notifies() {
        t.focus("a"); assertFalse(t.observe(obs("a", 100, 50, question = q1)).notify)
    }

    @Test fun resize_redraw_is_not_work() {
        t.observe(obs("a", 100, 50, size = 80 to 24))
        assertEquals(Status.Idle, t.observe(obs("a", 101, 101, size = 59 to 53)).status)
    }

    @Test fun unknown_session_starts_idle_not_finished() = assertEquals(Status.Idle, t.observe(obs("a", 100, 10)).status)

    // --- Fix round 1: ruling 1 - notify fires again on a repeated question once work intervenes.

    @Test fun repeated_question_after_work_notifies_again() {
        assertTrue(t.observe(obs("a", 100, 50, question = q1)).notify)
        assertEquals(Status.Working, t.observe(obs("a", 102, 101)).status)
        assertTrue(t.observe(obs("a", 104, 101, question = q1)).notify)
    }

    // --- Fix round 1: ruling 2 - a poll gap wider than workingWindow must not hide finished work.

    @Test fun waiting_question_vanishes_after_work_is_finished_and_notifies() {
        t.observe(obs("a", 100, 50, question = q1))
        val f = t.observe(obs("a", 140, 130))
        assertEquals(Status.Finished, f.status); assertTrue(f.notify)
    }

    @Test fun finished_with_activity_advance_during_gap_notifies_again() {
        t.observe(obs("a", 100, 99)); t.observe(obs("a", 120, 99)) // Working, then Finished
        val again = t.observe(obs("a", 200, 190))
        assertEquals(Status.Finished, again.status); assertTrue(again.notify)
    }

    @Test fun idle_with_activity_advance_during_gap_is_finished_and_notifies() {
        t.observe(obs("a", 100, 10)) // Idle (seeds only)
        val f = t.observe(obs("a", 200, 150))
        assertEquals(Status.Finished, f.status); assertTrue(f.notify)
    }

    @Test fun finished_stays_quiet_with_unchanged_activity_no_notify() {
        t.observe(obs("a", 100, 99)); t.observe(obs("a", 120, 99)) // Working, then Finished
        val quiet = t.observe(obs("a", 130, 99))
        assertEquals(Status.Finished, quiet.status); assertFalse(quiet.notify)
    }

    // --- Minors.

    @Test fun off_has_null_question() {
        val r = t.observe(obs("a", 100, 50, question = q1, cmd = "bash"))
        assertEquals(Status.Off, r.status)
        assertEquals(null, r.question)
    }

    @Test fun off_leaves_prior_state_untouched() {
        t.observe(obs("a", 100, 99)); t.observe(obs("a", 120, 99)) // Working, then Finished
        assertEquals(Status.Off, t.observe(obs("a", 125, 99, cmd = "bash")).status)
        val after = t.observe(obs("a", 130, 99)) // back to claude, still quiet
        assertEquals(Status.Finished, after.status); assertFalse(after.notify)
    }

    @Test fun focus_suppresses_finished_alert_then_unfocus_notifies_again() {
        t.focus("a")
        t.observe(obs("a", 100, 99)) // Working, focused
        val finished = t.observe(obs("a", 120, 99)) // Finished, but focused
        assertEquals(Status.Finished, finished.status); assertFalse(finished.notify)

        t.focus(null)
        t.observe(obs("a", 130, 129)) // Working again
        val again = t.observe(obs("a", 150, 129)) // Finished again, unfocused
        assertEquals(Status.Finished, again.status); assertTrue(again.notify)
    }

    @Test fun forget_drops_tracked_state() {
        t.observe(obs("a", 100, 99)); t.observe(obs("a", 120, 99)) // Working, then Finished
        t.forget(emptySet())
        val fresh = t.observe(obs("a", 130, 99)) // first observation again: seeds only
        assertEquals(Status.Idle, fresh.status)
    }

    @Test fun activity_after_redraw_grace_expires_is_accepted() {
        t.observe(obs("a", 100, 50, size = 80 to 24))
        t.observe(obs("a", 101, 101, size = 59 to 53)) // resize: redraw suppressed, until = 104
        val accepted = t.observe(obs("a", 110, 105, size = 59 to 53)) // past until, genuinely newer
        assertEquals(Status.Working, accepted.status)
    }
}
