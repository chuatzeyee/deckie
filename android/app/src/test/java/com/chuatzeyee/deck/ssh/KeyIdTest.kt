package com.chuatzeyee.deck.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KeyIdTest {

    @Test fun accepts_plain_ids() = assertEquals("key-1_A", requireValidKeyId("key-1_A"))

    @Test fun rejects_path_traversal_and_separators() {
        listOf("../keys", "a/b", ".", "", "x".repeat(65), "a b").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { requireValidKeyId(bad) }
        }
    }
}
