package com.chuatzeyee.deck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoStartTest {

    private fun host(id: String, pinned: Boolean = true, sessions: List<String> = listOf("api")) =
        Host(id = id, name = id, address = "203.0.113.10", user = "deck", keyId = "k$id",
            pinnedHostKey = if (pinned) "SHA256:x" else null, sessions = sessions)

    @Test fun prefers_the_last_active_host() =
        assertEquals("b", pickAutoStartHost(listOf(host("a"), host("b")), lastHostId = "b")?.id)

    @Test fun falls_back_to_the_first_eligible_host_without_a_last_one() =
        assertEquals("a", pickAutoStartHost(listOf(host("a"), host("b")), lastHostId = null)?.id)

    @Test fun falls_back_when_the_last_host_is_gone() =
        assertEquals("a", pickAutoStartHost(listOf(host("a")), lastHostId = "deleted")?.id)

    @Test fun skips_a_last_host_that_is_no_longer_eligible() =
        assertEquals("a", pickAutoStartHost(listOf(host("a"), host("b", pinned = false)), lastHostId = "b")?.id)

    @Test fun ineligible_hosts_are_never_picked() =
        assertNull(pickAutoStartHost(listOf(host("a", sessions = emptyList()), host("b", pinned = false)), "a"))
}
