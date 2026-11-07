// Overrides the Task 11 brief's modifier/KeyAction design per the controller's rulings from the
// real-device spike (docs/device-notes.md "Results"): the Titan 2 has no arrow/Ctrl keys, and Alt
// is the symbol layer (never usable as a shortcut modifier) - Sym (META_SYM_ON) is the only
// modifier that arrives cleanly. Session jumps are by rail letter, not by digit index.
package com.chuatzeyee.deck.core

import android.view.KeyEvent

/** What a hardware key combination means for the Deck screen; `null` from [keyAction] means
 * "pass the key to the terminal" - this sealed type is only ever produced for a recognised
 * Sym-chord, never for ordinary typing. */
sealed interface KeyAction {
    /** Sym + a rail letter: jump straight to [session]. */
    data class Jump(val session: String) : KeyAction

    /** Sym+J: select the next session in rail order. */
    data object Next : KeyAction

    /** Sym+K: select the previous session in rail order. */
    data object Prev : KeyAction

    /** Sym+M: open the session menu - see [com.chuatzeyee.deck.ui.SessionMenu]. */
    data object OpenMenu : KeyAction

    /** A recognised Sym+letter chord with no session assigned to that letter - still consumed,
     * never passed through to the terminal (fix round 1, minor): typing Sym+<unassigned letter>
     * must never leak a stray letter into the pty. */
    data object Ignore : KeyAction
}

/**
 * Decodes one hardware key event into a [KeyAction], or `null` if it isn't a recognised Sym-chord
 * at all (including every plain, unmodified key - those must reach the terminal). Every recognised
 * Sym+letter chord - reserved or not, assigned to a session or not - returns a non-null
 * [KeyAction], so the caller can consume it (see [KeyAction.Ignore]). [meta] is the raw
 * `KeyEvent.metaState`; only [KeyEvent.META_SYM_ON] is checked - Alt is deliberately never treated
 * as a modifier here (it's the Titan 2's symbol layer). [letters] is this rail's current
 * letter-to-session assignment, from [assignLetters].
 */
fun keyAction(keyCode: Int, meta: Int, letters: Map<Char, String>): KeyAction? {
    if (meta and KeyEvent.META_SYM_ON == 0) return null
    val letter = letterForKeyCode(keyCode) ?: return null
    return when (letter) {
        'j' -> KeyAction.Next
        'k' -> KeyAction.Prev
        'm' -> KeyAction.OpenMenu
        else -> letters[letter]?.let { KeyAction.Jump(it) } ?: KeyAction.Ignore
    }
}

/** The lowercase letter a plain A-Z hardware key represents, or `null` for any other key - keycode
 * identity only, independent of meta state (Sym+A and plain A are both `'a'`). */
fun letterForKeyCode(keyCode: Int): Char? {
    if (keyCode !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) return null
    return 'a' + (keyCode - KeyEvent.KEYCODE_A)
}

/** Letters [keyAction] treats as session-jump shortcuts, never assignable by [assignLetters]. */
private val RESERVED_LETTERS = setOf('j', 'k', 'm')

/**
 * Assigns each name in [names] (rail order) a stable, deterministic jump letter: the first letter
 * of the name (lowercased, restricted to `a`-`z`) not yet taken and not in [RESERVED_LETTERS];
 * failing that, the name's next letters in order; failing that, the first free letter `a`-`z`.
 * A name with no letters available at all (every `a`-`z` letter already assigned to an earlier
 * name) gets none and is absent from the result - at most 23 of [names] (26 letters minus the 3
 * reserved for Next/Prev/OpenMenu) ever get one.
 */
fun assignLetters(names: List<String>): Map<Char, String> {
    val taken = mutableSetOf<Char>()
    val result = LinkedHashMap<Char, String>()
    for (name in names) {
        val letter = pickLetter(name, taken) ?: continue
        taken += letter
        result[letter] = name
    }
    return result
}

/**
 * The rail's jump letters: assigned over [chosen] - the user's own session order, which only they
 * change - rather than the status-sorted rail, so a session keeps its letter when it starts or
 * stops working. Only names in [onRail] (alive right now) are jump targets; a chosen session that
 * is gone keeps its letter reserved so the others don't shift when it comes back.
 */
fun railLetters(chosen: List<String>, onRail: List<String>): Map<Char, String> {
    val alive = onRail.toSet()
    return assignLetters(chosen).filterValues { it in alive }
}

private fun pickLetter(name: String, taken: Set<Char>): Char? {
    val candidates = name.lowercase().filter { it in 'a'..'z' }
    candidates.firstOrNull { it !in RESERVED_LETTERS && it !in taken }?.let { return it }
    return ('a'..'z').firstOrNull { it !in RESERVED_LETTERS && it !in taken }
}

/** Picks the session adjacent to [current] in [order] (rail order), wrapping around; `null`
 * selection picks the first session. `null` if [order] is empty. */
fun cycleSession(current: String?, order: List<String>, forward: Boolean): String? {
    if (order.isEmpty()) return null
    val index = order.indexOf(current)
    if (index < 0) return order.first()
    val step = if (forward) 1 else -1
    return order[(index + step).mod(order.size)]
}

/** R22's on-screen extra-keys row: everything but Ctrl, which is a sticky modifier (see
 * [ctrlByte]) rather than a byte of its own. */
enum class ExtraKey { Esc, Tab, Up, Down, Left, Right }

/** The literal bytes to write to the pty for [key]: Esc/Tab are single control bytes, the arrows
 * are xterm cursor-key sequences (`ESC [ A/B/D/C`). */
fun extraKeyBytes(key: ExtraKey): ByteArray = when (key) {
    ExtraKey.Esc -> byteArrayOf(0x1B)
    ExtraKey.Tab -> byteArrayOf(0x09)
    ExtraKey.Up -> xtermCursorKey('A')
    ExtraKey.Down -> xtermCursorKey('B')
    ExtraKey.Left -> xtermCursorKey('D')
    ExtraKey.Right -> xtermCursorKey('C')
}

private fun xtermCursorKey(final: Char): ByteArray = byteArrayOf(0x1B, '['.code.toByte(), final.code.toByte())

/**
 * The control byte for letter [c] (e.g. `Ctrl-C` -> `0x03`), or `null` if [c] isn't a letter - R22's
 * sticky Ctrl key arms once, converts the next typed letter via this function, then disarms.
 */
fun ctrlByte(c: Char): Byte? {
    val upper = c.uppercaseChar()
    if (upper !in 'A'..'Z') return null
    return (upper.code - 'A'.code + 1).toByte()
}
