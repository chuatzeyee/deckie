// Ported from deckaway's panel/panel.py (`answer_question`'s Claude path, and
// `wait_for_quiet_screen`): https://github.com/matacoder/deckaway — MIT License, Copyright (c)
// 2026 Denis Matakov. Codex's async-question branch (`answer_by_message`, the `request_id`/
// `pending`/`deck` matching that routes to a gateway session) is not ported: Deck only drives
// Claude Code's own on-screen menu over tmux.
package com.chuatzeyee.deck.core

import com.chuatzeyee.deck.ssh.Exec
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The outcome of one [Sender.answer] attempt. */
sealed interface AnswerResult {
    /** The chosen option was selected and entered. */
    data object Sent : AnswerResult

    /** Nothing was sent to the session; [reason] is shown to the user as-is. */
    data class Refused(val reason: String) : AnswerResult
}

/**
 * Sends keystrokes and pasted text into a tmux session, and answers a [Question] by moving the
 * on-screen cursor to the chosen option and pressing Enter.
 *
 * Every operation this class performs against one session - answering, typing, or sending raw
 * keys - runs under that session's own [Mutex], one at a time, never two concurrently: a one-tap
 * answer and a typed message racing into the same pane would interleave their keystrokes into
 * whichever one Claude happened to be rendering, which is exactly the "answered the wrong
 * question" failure this class exists to prevent. [answer] re-captures the screen *after* taking
 * the lock, because the fingerprint the caller saw when they tapped may already be stale by the
 * time the lock is free (another answer could have gone through first); it then refuses rather
 * than guesses, and never retries after a refusal - the caller must show the user the terminal.
 */
class Sender(private val exec: Exec) {

    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(session: String): Mutex = locks.computeIfAbsent(session) { Mutex() }

    /**
     * Re-captures [q]'s session and parses it; refuses unless the exact question [q] saw is still
     * showing (same fingerprint — [Question.fingerprint] ignores [Question.selected], so a cursor
     * move alone never counts as "changed"). Refuses if [index] isn't one of the re-captured
     * question's options. Otherwise sends `Up`/`Down` to move the cursor to [index], polling for
     * up to 10×50 ms for the move to land, then sends `Enter`.
     *
     * [cancelCopyMode]: the caller may have put the pane in copy-mode (swipe-to-scroll), where
     * `Up`/`Down`/`Enter` would move a copy-mode cursor instead of answering - so first leave it.
     * Its exit status is ignored: tmux fails "not in a mode" when the pane already left copy-mode.
     */
    suspend fun answer(q: Question, index: Int, cancelCopyMode: Boolean = false): AnswerResult = lockFor(q.session).withLock {
        if (cancelCopyMode) exec.run(Tmux.cancelCopyMode(q.session), null)
        val current = captureQuestion(q.session)
        if (current == null || current.fingerprint != q.fingerprint) {
            return@withLock AnswerResult.Refused(QUESTION_CHANGED)
        }
        if (index !in current.options.indices) return@withLock AnswerResult.Refused("No such option.")

        val delta = index - current.selected
        if (delta != 0) {
            val keys = List(abs(delta)) { if (delta > 0) TmuxKey.Down else TmuxKey.Up }
            if (exec.run(Tmux.sendKeys(q.session, keys), null).exit != 0) {
                return@withLock AnswerResult.Refused(QUESTION_CHANGED)
            }
            // Poll up to 10 times, 50 ms apart, for the cursor move to land.
            var moved = false
            for (attempt in 1..10) {
                delay(50)
                val retry = captureQuestion(q.session)
                if (retry == null || retry.fingerprint != q.fingerprint) {
                    return@withLock AnswerResult.Refused(QUESTION_CHANGED)
                }
                if (retry.selected == index) {
                    moved = true
                    break
                }
            }
            if (!moved) {
                return@withLock AnswerResult.Refused("Couldn't select that option — answer in the terminal.")
            }
        }
        if (exec.run(Tmux.sendKeys(q.session, listOf(TmuxKey.Enter)), null).exit != 0) {
            return@withLock AnswerResult.Refused(QUESTION_CHANGED)
        }
        AnswerResult.Sent
    }

    /** Pastes [text] (with any embedded paste markers stripped) into [session], then presses Enter once the screen settles. */
    suspend fun sendText(session: String, text: String): Unit = lockFor(session).withLock {
        val stripped = stripPasteMarkers(text)
        runOrThrow(Tmux.loadAndPaste(session), stripped.toByteArray(Charsets.UTF_8))
        waitForQuietScreen(session)
        runOrThrow(Tmux.sendKeys(session, listOf(TmuxKey.Enter)), null)
    }

    /** Sends named keys (arrows, Enter, Escape, …) to [session] verbatim. */
    suspend fun sendKeys(session: String, keys: List<TmuxKey>): Unit = lockFor(session).withLock {
        runOrThrow(Tmux.sendKeys(session, keys), null)
    }

    /** Swipe-to-scroll, step 1: enters copy-mode. Call once per drag gesture, before any [scroll]. */
    suspend fun enterCopyMode(session: String): Unit = lockFor(session).withLock {
        runOrThrow(Tmux.copyMode(session), null)
    }

    /** Swipe-to-scroll, step 2: scrolls [session]'s copy-mode view by [lines] rows; [up] or down.
     * Must be preceded by [enterCopyMode] - tmux auto-exits copy-mode once the view reaches the
     * bottom (see [Tmux.copyMode]'s `-e`). */
    suspend fun scroll(session: String, up: Boolean, lines: Int): Unit = lockFor(session).withLock {
        runOrThrow(Tmux.scroll(session, up, lines), null)
    }

    private suspend fun captureQuestion(session: String): Question? {
        val result = exec.run(Tmux.capture(session), null)
        if (result.exit != 0) return null
        return QuestionParser.parse(session, result.stdout)
    }

    /**
     * Ported from `wait_for_quiet_screen`: polls the screen every 250 ms and returns as soon as
     * two consecutive captures come back identical, for up to four polls (~1 s). Claude turns a
     * paste into on-screen text asynchronously, so an Enter sent while the screen is still
     * redrawing can land mid-paste and get dropped instead of submitting it; this does not throw
     * if the screen never settles — it just stops waiting and the caller still sends Enter.
     */
    private suspend fun waitForQuietScreen(session: String) {
        var previous: String? = null
        repeat(QUIET_SCREEN_POLLS) {
            val screen = exec.run(Tmux.capture(session), null).stdout
            if (screen == previous) return
            previous = screen
            delay(QUIET_SCREEN_POLL_DELAY_MS)
        }
    }

    private suspend fun runOrThrow(command: String, stdin: ByteArray?) {
        val result = exec.run(command, stdin)
        if (result.exit != 0) throw IOException(result.stderr)
    }

    private companion object {
        private const val QUESTION_CHANGED = "This question has changed or closed."
        private const val QUIET_SCREEN_POLLS = 4
        private const val QUIET_SCREEN_POLL_DELAY_MS = 250L
        private const val PASTE_BEGIN = "\u001b[200~"
        private const val PASTE_END = "\u001b[201~"

        /**
         * Strips every paste marker, repeating until none remain: removing one marker must not
         * join its neighbours into a new one (e.g. `"\x1b[20" + "\x1b[201~" + "1~"` would become
         * `"\x1b[2011~"` after one pass, which still contains no marker — but the reverse split
         * could, so the loop runs until a fixed point).
         */
        private fun stripPasteMarkers(text: String): String {
            var stripped = text
            while (PASTE_BEGIN in stripped || PASTE_END in stripped) {
                stripped = stripped.replace(PASTE_BEGIN, "").replace(PASTE_END, "")
            }
            return stripped
        }
    }
}
