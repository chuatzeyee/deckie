package com.chuatzeyee.deck.ui

import com.chuatzeyee.deck.poll.LinkState
import com.chuatzeyee.deck.poll.PollResult
import com.chuatzeyee.deck.ssh.TerminalState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRecoveryTest {

    private val up = LinkState.Up(PollResult(emptyList(), emptyList(), emptyList(), noServer = false), atMs = 0L)
    private val down = LinkState.Down("lost", 1)
    private val failed = TerminalState.Failed("terminal disconnected — tap to retry")

    @Test fun failed_terminal_retries_when_the_link_comes_back_up() {
        assertTrue(shouldRetryTerminalOnLink(failed, down, up))
        assertTrue(shouldRetryTerminalOnLink(failed, LinkState.Connecting, up))
    }

    @Test fun no_retry_while_the_link_merely_stays_up() =
        assertFalse(shouldRetryTerminalOnLink(failed, up, up.copy(atMs = 1L)))

    @Test fun no_retry_when_the_link_goes_down() = assertFalse(shouldRetryTerminalOnLink(failed, up, down))

    @Test fun a_healthy_terminal_is_left_alone() {
        assertFalse(shouldRetryTerminalOnLink(TerminalState.Connecting, down, up))
        assertFalse(shouldRetryTerminalOnLink(TerminalState.Idle, down, up))
    }

    @Test fun failed_terminal_retries_on_resume_only() {
        assertTrue(shouldRetryTerminalOnResume(failed, visible = true))
        assertFalse(shouldRetryTerminalOnResume(failed, visible = false))
        assertFalse(shouldRetryTerminalOnResume(TerminalState.Idle, visible = true))
    }
}
