package com.chuatzeyee.deck.core

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeysTest {
    private val letters = mapOf('a' to "alpha", 'b' to "beta")

    @Test fun `plain A is null`() = assertNull(keyAction(KeyEvent.KEYCODE_A, 0, letters))

    @Test fun `Sym+J is Next`() =
        assertEquals(KeyAction.Next, keyAction(KeyEvent.KEYCODE_J, KeyEvent.META_SYM_ON, letters))

    @Test fun `Sym+K is Prev`() =
        assertEquals(KeyAction.Prev, keyAction(KeyEvent.KEYCODE_K, KeyEvent.META_SYM_ON, letters))

    @Test fun `Sym+M is OpenMenu`() =
        assertEquals(KeyAction.OpenMenu, keyAction(KeyEvent.KEYCODE_M, KeyEvent.META_SYM_ON, letters))

    @Test fun `Sym+A jumps to the session assigned a`() =
        assertEquals(KeyAction.Jump("alpha"), keyAction(KeyEvent.KEYCODE_A, KeyEvent.META_SYM_ON, letters))

    @Test fun `Sym+unassigned letter is Ignore - consumed, never typed`() =
        assertEquals(KeyAction.Ignore, keyAction(KeyEvent.KEYCODE_Z, KeyEvent.META_SYM_ON, letters))

    @Test fun `Alt is never a modifier - Alt+J is null`() =
        assertNull(keyAction(KeyEvent.KEYCODE_J, KeyEvent.META_ALT_ON, letters))

    @Test fun `non-letter key with Sym is null`() =
        assertNull(keyAction(KeyEvent.KEYCODE_1, KeyEvent.META_SYM_ON, letters))

    @Test fun `assignLetters picks first letter of each name`() =
        assertEquals(mapOf('a' to "alpha", 'b' to "beta"), assignLetters(listOf("alpha", "beta")))

    @Test fun `assignLetters resolves collisions via next letters - sql and ssh-test`() =
        assertEquals(mapOf('s' to "sql", 'h' to "ssh-test"), assignLetters(listOf("sql", "ssh-test")))

    @Test fun `assignLetters never assigns reserved letters j k m`() {
        val result = assignLetters(listOf("job", "kit", "main"))
        assertEquals(mapOf('o' to "job", 'i' to "kit", 'a' to "main"), result)
    }

    @Test fun `assignLetters skips leading digits and symbols`() =
        assertEquals(mapOf('a' to "123abc"), assignLetters(listOf("123abc")))

    @Test fun `assignLetters falls back to first free letter for names with no letters at all`() =
        assertEquals(mapOf('a' to "007"), assignLetters(listOf("007")))

    @Test fun `assignLetters leaves sessions beyond the 23 available letters unassigned`() {
        val names = (1..24).map { it.toString() }
        val result = assignLetters(names)
        assertEquals(23, result.size)
        assertEquals(setOf("24"), names.toSet() - result.values.toSet())
    }

    @Test fun `letterForKeyCode maps A-Z, else null`() {
        assertEquals('a', letterForKeyCode(KeyEvent.KEYCODE_A))
        assertEquals('z', letterForKeyCode(KeyEvent.KEYCODE_Z))
        assertNull(letterForKeyCode(KeyEvent.KEYCODE_1))
    }

    @Test fun `cycleSession wraps forward and backward`() {
        val order = listOf("a", "b", "c")
        assertEquals("c", cycleSession("b", order, forward = true))
        assertEquals("a", cycleSession("c", order, forward = true))
        assertEquals("a", cycleSession("b", order, forward = false))
        assertEquals("c", cycleSession("a", order, forward = false))
    }

    @Test fun `cycleSession with no current selection picks first`() =
        assertEquals("a", cycleSession(null, listOf("a", "b", "c"), forward = true))

    @Test fun `cycleSession with empty order is null`() =
        assertNull(cycleSession("a", emptyList(), forward = true))

    @Test fun `extraKeyBytes - Esc Tab and arrows`() {
        assertEquals(listOf<Byte>(0x1B), extraKeyBytes(ExtraKey.Esc).toList())
        assertEquals(listOf<Byte>(0x09), extraKeyBytes(ExtraKey.Tab).toList())
        assertEquals("\u001B[A".toByteArray().toList(), extraKeyBytes(ExtraKey.Up).toList())
        assertEquals("\u001B[B".toByteArray().toList(), extraKeyBytes(ExtraKey.Down).toList())
        assertEquals("\u001B[D".toByteArray().toList(), extraKeyBytes(ExtraKey.Left).toList())
        assertEquals("\u001B[C".toByteArray().toList(), extraKeyBytes(ExtraKey.Right).toList())
    }

    @Test fun `ctrlByte converts letters to control codes`() {
        assertEquals(0x03.toByte(), ctrlByte('c'))
        assertEquals(0x03.toByte(), ctrlByte('C'))
        assertEquals(0x01.toByte(), ctrlByte('a'))
        assertEquals(0x1A.toByte(), ctrlByte('z'))
    }

    @Test fun `ctrlByte is null for non-letters`() {
        assertNull(ctrlByte('1'))
        assertNull(ctrlByte(' '))
    }

    @Test fun rail_letters_follow_the_chosen_order_not_the_rail_order() {
        val chosen = listOf("beta", "alpha", "bravo")
        val byStatus = listOf("bravo", "alpha", "beta")
        val letters = railLetters(chosen, byStatus)
        assertEquals(mapOf('b' to "beta", 'a' to "alpha", 'r' to "bravo"), letters)
        assertEquals(letters, railLetters(chosen, byStatus.reversed()))
    }

    @Test fun a_gone_session_keeps_its_letter_reserved() =
        assertEquals(mapOf('r' to "bravo"), railLetters(listOf("beta", "bravo"), listOf("bravo")))
}
