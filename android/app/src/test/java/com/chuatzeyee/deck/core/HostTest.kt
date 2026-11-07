package com.chuatzeyee.deck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTest {

    @Test fun valid_host() = assertTrue(validateHost("vm", "203.0.113.10", "22", "deck").isEmpty())

    @Test fun tailscale_hostname_ok() =
        assertTrue(validateHost("vm", "example-host.tailnet-example.ts.net", "22", "u").isEmpty())

    @Test fun ipv6_ok() = assertTrue(validateHost("vm", "fd7a:115c:a1e0::1", "22", "u").isEmpty())

    @Test fun ipv6_full_form_ok() =
        assertTrue(validateHost("vm", "2001:0db8:0000:0000:0000:8a2e:0370:7334", "22", "u").isEmpty())

    @Test fun bad_fields() {
        val e = validateHost("", "bad host!", "70000", "Root User")
        assertEquals(setOf("name", "address", "port", "user"), e.keys)
    }

    @Test fun name_too_long_is_rejected() {
        val e = validateHost("x".repeat(41), "203.0.113.10", "22", "u")
        assertEquals(setOf("name"), e.keys)
    }

    @Test fun port_zero_is_rejected() {
        val e = validateHost("vm", "203.0.113.10", "0", "u")
        assertEquals(setOf("port"), e.keys)
    }

    @Test fun port_non_numeric_is_rejected() {
        val e = validateHost("vm", "203.0.113.10", "abc", "u")
        assertEquals(setOf("port"), e.keys)
    }

    @Test fun user_starting_with_digit_is_rejected() {
        val e = validateHost("vm", "203.0.113.10", "22", "9bad")
        assertEquals(setOf("user"), e.keys)
    }

    @Test fun address_with_double_double_colon_is_rejected() {
        val e = validateHost("vm", "fd7a::115c::1", "22", "u")
        assertEquals(setOf("address"), e.keys)
    }
}
