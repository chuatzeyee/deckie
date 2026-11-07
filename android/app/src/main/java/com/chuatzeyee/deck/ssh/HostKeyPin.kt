package com.chuatzeyee.deck.ssh

/** The outcome of comparing a host's presented SSH host-key fingerprint against what's pinned. */
sealed interface PinResult {
    /** [host.pinnedHostKey][com.chuatzeyee.deck.core.Host.pinnedHostKey] was `null`: nothing pinned yet. */
    data class FirstUse(val fingerprint: String) : PinResult

    /** The presented fingerprint matches what's pinned. */
    data object Match : PinResult

    /** The presented fingerprint contradicts what's pinned - never auto-repinned. */
    data class Mismatch(val expected: String, val got: String) : PinResult
}

/** Pure comparison of a stored [pinned] fingerprint (if any) against the one the server [presented]. */
fun checkPin(pinned: String?, presented: String): PinResult = when {
    pinned == null -> PinResult.FirstUse(presented)
    pinned == presented -> PinResult.Match
    else -> PinResult.Mismatch(expected = pinned, got = presented)
}
