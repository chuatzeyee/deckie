package com.chuatzeyee.deck.ui

import com.chuatzeyee.deck.core.Status
import com.chuatzeyee.deck.poll.LinkState
import com.chuatzeyee.deck.poll.PollResult
import com.chuatzeyee.deck.poll.SessionView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LinkStatusTest {

    private val hostName = "prod-box"
    private val result = PollResult(views = emptyList(), notAdded = emptyList(), alerts = emptyList(), noServer = false)
    private val oneView = result.copy(
        views = listOf(SessionView(hostId = "h1", name = "alpha", status = Status.Working, question = null, path = "~")),
    )

    @Test
    fun `connecting shows connecting`() {
        assertEquals("Connecting…", linkStatusLine(LinkState.Connecting, hostName))
    }

    @Test
    fun `up with running sessions shows connected`() {
        assertEquals("Connected", linkStatusLine(LinkState.Up(oneView, atMs = 0L), hostName))
    }

    @Test
    fun `up with no tmux server names the host exactly`() {
        val noServer = result.copy(noServer = true)
        assertEquals("No tmux sessions on prod-box", linkStatusLine(LinkState.Up(noServer, atMs = 0L), hostName))
    }

    @Test
    fun `down shows retry countdown without the raw reason`() {
        val reason = "ssh: connect to host 10.0.0.5 port 22: Connection refused"
        val line = linkStatusLine(LinkState.Down(reason = reason, retryInSec = 7), hostName)
        assertEquals("disconnected · retrying in 7s", line)
        assertFalse("raw Down.reason must not leak into the status line", line.contains(reason))
    }

    @Test
    fun `identity changed shows the not-connecting headline`() {
        val state = LinkState.IdentityChanged(expected = "SHA256:old", got = "SHA256:new", keyType = "ED25519")
        assertEquals("Server identity changed! Not connecting.", linkStatusLine(state, hostName))
    }

    @Test
    fun `rail message is null when up with running sessions`() {
        assertEquals(null, railMessage(LinkState.Up(oneView, atMs = 0L), hostName))
    }

    @Test
    fun `rail message names the host when up but no tmux server`() {
        val empty = result.copy(noServer = true)
        assertEquals("No tmux sessions on prod-box", railMessage(LinkState.Up(empty, atMs = 0L), hostName))
    }

    @Test
    fun `rail message flags an up host whose chosen sessions are all stopped`() {
        assertEquals("None of your chosen sessions are running", railMessage(LinkState.Up(result, atMs = 0L), hostName))
    }

    @Test
    fun `rail message mirrors the status line while connecting, down, or identity-changed`() {
        assertEquals("Connecting…", railMessage(LinkState.Connecting, hostName))
        val down = LinkState.Down(reason = "boom", retryInSec = 3)
        assertEquals(linkStatusLine(down, hostName), railMessage(down, hostName))
        val changed = LinkState.IdentityChanged(expected = "a", got = "b", keyType = "ED25519")
        assertEquals(linkStatusLine(changed, hostName), railMessage(changed, hostName))
    }

    @Test
    fun `an up result is fresh until it is older than the stale threshold`() {
        val up = LinkState.Up(oneView, atMs = 10_000L)
        assertFalse(isStale(up, nowMs = 10_000L, staleAfterMs = 4_000L))
        assertFalse(isStale(up, nowMs = 14_000L, staleAfterMs = 4_000L))
        assertEquals(true, isStale(up, nowMs = 14_001L, staleAfterMs = 4_000L))
    }

    @Test
    fun `a clock that went backwards never reads as stale`() =
        assertFalse(isStale(LinkState.Up(oneView, atMs = 10_000L), nowMs = 5_000L, staleAfterMs = 4_000L))

    @Test
    fun `only up results can be stale`() {
        assertFalse(isStale(LinkState.Connecting, nowMs = Long.MAX_VALUE, staleAfterMs = 1L))
        assertFalse(isStale(LinkState.Down("x", 1), nowMs = Long.MAX_VALUE, staleAfterMs = 1L))
    }
}
