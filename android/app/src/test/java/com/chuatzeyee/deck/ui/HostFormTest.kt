package com.chuatzeyee.deck.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostFormTest {

    @Test
    fun `effectivePort blank means 22`() {
        assertEquals("22", effectivePort(""))
        assertEquals("2222", effectivePort("2222"))
    }

    @Test
    fun `hostFromForm trims fields and parses port`() {
        val host = hostFromForm(
            id = "abc",
            keyId = "host-abc",
            name = "  My Box  ",
            address = " 10.0.0.5 ",
            port = "",
            user = " pi ",
            pinnedHostKey = null,
            sessions = listOf("work"),
        )
        assertEquals("abc", host.id)
        assertEquals("My Box", host.name)
        assertEquals("10.0.0.5", host.address)
        assertEquals(22, host.port)
        assertEquals("pi", host.user)
        assertEquals("host-abc", host.keyId)
        assertEquals(null, host.pinnedHostKey)
        assertEquals(listOf("work"), host.sessions)
    }

    @Test
    fun `hostFromForm keeps an explicit port`() {
        val host = hostFromForm(
            id = "abc", keyId = "host-abc", name = "n", address = "a",
            port = "2222", user = "u", pinnedHostKey = "fp", sessions = emptyList(),
        )
        assertEquals(2222, host.port)
        assertEquals("fp", host.pinnedHostKey)
    }

    @Test
    fun `randomHostKeyId is prefixed and dash-free`() {
        val id = randomHostKeyId()
        assertTrue(id.startsWith("host-"))
        assertTrue(Regex("[A-Za-z0-9_-]{1,64}").matches(id))
        assertEquals(-1, id.indexOf('-', 5))
    }

    @Test
    fun `firstUseMessage includes address key type and fingerprint`() {
        val message = firstUseMessage("10.0.0.5", "ED25519", "SHA256:abc")
        assertEquals(
            "First connection to 10.0.0.5. Server fingerprint (ED25519): SHA256:abc. Trust it?",
            message,
        )
    }

    @Test
    fun `hostKeyMismatchMessage includes expected and got`() {
        val message = hostKeyMismatchMessage("SHA256:old", "SHA256:new")
        assertEquals(
            "Server identity changed! Expected SHA256:old, got SHA256:new. Not connecting.",
            message,
        )
    }
}
