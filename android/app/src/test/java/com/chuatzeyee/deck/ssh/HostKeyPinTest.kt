package com.chuatzeyee.deck.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class HostKeyPinTest {

    @Test fun first_use() = assertEquals(PinResult.FirstUse("SHA256:abc"), checkPin(null, "SHA256:abc"))

    @Test fun match() = assertEquals(PinResult.Match, checkPin("SHA256:abc", "SHA256:abc"))

    @Test fun mismatch() =
        assertEquals(PinResult.Mismatch("SHA256:abc", "SHA256:xyz"), checkPin("SHA256:abc", "SHA256:xyz"))
}
