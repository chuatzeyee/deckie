package com.chuatzeyee.deck.ssh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fake [Pty] with no real SSH behind it - just enough shape to exercise [isLivePty] without a
 * live connection (fix round 1 minor: "a small TerminalConnection test with a fake Pty for the
 * stale-reader rule"). */
private class FakePty : Pty {
    override val output: InputStream = ByteArrayInputStream(ByteArray(0))
    override val input: OutputStream = ByteArrayOutputStream()
    var resized: Pair<Int, Int>? = null
    override fun resize(cols: Int, rows: Int) {
        resized = cols to rows
    }
    override fun close() {}
}

class TerminalConnectionTest {

    @Test
    fun `a reader still owns its pty while it is the live one`() {
        val pty = FakePty()
        assertTrue(isLivePty(currentPty = pty, ownerPty = pty))
    }

    @Test
    fun `a stale reader bound to a replaced pty is not the owner - important #3`() {
        val oldPty = FakePty()
        val newPty = FakePty()
        // The connection has since switched sessions: `pty` (the live field) now points at
        // newPty, but a reader coroutine started under oldPty - in flight on a blocking read when
        // the switch happened - must recognise it no longer owns the live state.
        assertFalse(isLivePty(currentPty = newPty, ownerPty = oldPty))
    }

    @Test
    fun `identity, not structure, decides ownership - two otherwise-identical fakes differ`() {
        val a = FakePty()
        val b = FakePty()
        assertFalse(isLivePty(currentPty = b, ownerPty = a))
    }

    @Test
    fun `no live pty at all means nothing is the owner`() {
        val oldPty = FakePty()
        assertFalse(isLivePty(currentPty = null, ownerPty = oldPty))
    }
}
