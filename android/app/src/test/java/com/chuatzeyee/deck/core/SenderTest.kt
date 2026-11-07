package com.chuatzeyee.deck.core

import com.chuatzeyee.deck.ssh.Exec
import com.chuatzeyee.deck.ssh.ExecResult
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SenderTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("screens/$name")) {
            "missing fixture screens/$name"
        }.bufferedReader(Charsets.UTF_8).readText()

    private val permission = fixture("permission.txt")

    /**
     * Records every command issued (and its stdin), and answers `capture-pane` calls from a fixed
     * list of screens in order - once the list runs out, it keeps returning the last entry, so a
     * test doesn't need to supply one screen per poll for a stable screen.
     */
    private class FakeExec(private val screens: List<String> = emptyList()) : Exec {
        data class Recorded(val command: String, val stdin: ByteArray?)

        val commands = mutableListOf<Recorded>()
        private var captureIndex = 0

        /** Non-zero exit (with [stderrOverride]) to return for every command, or `null` for success. */
        var exitOverride: Int? = null
        var stderrOverride: String = ""

        override suspend fun run(command: String, stdin: ByteArray?): ExecResult {
            commands.add(Recorded(command, stdin))
            yield() // a real SSH round trip suspends here; exposes any missing per-session lock.
            exitOverride?.let { return ExecResult(it, "", stderrOverride) }
            if ("capture-pane" in command) {
                val screen = screens.getOrElse(captureIndex) { screens.lastOrNull() ?: "" }
                captureIndex++
                return ExecResult(0, screen, "")
            }
            return ExecResult(0, "", "")
        }

        fun keyCommands(): List<String> = commands.map { it.command }.filter { "send-keys" in it }

        fun stdinOf(commandSubstring: String): String? =
            commands.lastOrNull { commandSubstring in it.command }?.stdin?.toString(Charsets.UTF_8)
    }

    // --- answer(): moving the cursor -------------------------------------------------------

    @Test fun answers_by_moving_then_enter() = runTest {
        val fake = FakeExec(screens = listOf(permission, permission, selected(permission, 1)))
        val q = QuestionParser.parse("api", permission)!!

        assertEquals(AnswerResult.Sent, Sender(fake).answer(q, 1))
        assertEquals(
            listOf("tmux -u send-keys -t '=api:' Down", "tmux -u send-keys -t '=api:' Enter"),
            fake.keyCommands(),
        )
    }

    @Test fun cancels_copy_mode_before_capturing_when_asked() = runTest {
        val fake = FakeExec(screens = listOf(permission))
        val q = QuestionParser.parse("api", permission)!!

        assertEquals(AnswerResult.Sent, Sender(fake).answer(q, 0, cancelCopyMode = true))
        assertEquals("tmux -u send-keys -t '=api:' -X cancel", fake.commands.first().command)
        assertEquals("tmux -u send-keys -t '=api:' Enter", fake.commands.last().command)
    }

    @Test fun a_failed_cancel_does_not_stop_the_answer() = runTest {
        // tmux exits 1 ("not in a mode") when copy-mode has already ended on its own.
        val fake = object : Exec {
            val commands = mutableListOf<String>()
            override suspend fun run(command: String, stdin: ByteArray?): ExecResult {
                commands += command
                return when {
                    "-X cancel" in command -> ExecResult(1, "", "not in a mode")
                    "capture-pane" in command -> ExecResult(0, permission, "")
                    else -> ExecResult(0, "", "")
                }
            }
        }
        val q = QuestionParser.parse("api", permission)!!
        assertEquals(AnswerResult.Sent, Sender(fake).answer(q, 0, cancelCopyMode = true))
        assertEquals("tmux -u send-keys -t '=api:' Enter", fake.commands.last())
    }

    @Test fun does_not_cancel_copy_mode_by_default() = runTest {
        val fake = FakeExec(screens = listOf(permission))
        Sender(fake).answer(QuestionParser.parse("api", permission)!!, 0)
        assertTrue(fake.commands.none { "-X cancel" in it.command })
    }

    @Test fun answers_without_moving_sends_only_enter() = runTest {
        val fake = FakeExec(screens = listOf(permission))
        val q = QuestionParser.parse("api", permission)!!

        assertEquals(AnswerResult.Sent, Sender(fake).answer(q, 0))
        assertEquals(listOf("tmux -u send-keys -t '=api:' Enter"), fake.keyCommands())
    }

    @Test fun moving_up_sends_up_keys() = runTest {
        val fake = FakeExec(screens = listOf(selected(permission, 2), selected(permission, 2), selected(permission, 0)))
        val q = QuestionParser.parse("api", selected(permission, 2))!!

        assertEquals(AnswerResult.Sent, Sender(fake).answer(q, 0))
        assertEquals(
            listOf("tmux -u send-keys -t '=api:' Up Up", "tmux -u send-keys -t '=api:' Enter"),
            fake.keyCommands(),
        )
    }

    // --- answer(): refusals ------------------------------------------------------------------

    @Test fun refuses_when_question_changed() = runTest {
        val otherQuestion = permission.replace("teams.py", "billing.py")
        val fake = FakeExec(screens = listOf(otherQuestion))
        val q = QuestionParser.parse("api", permission)!!

        assertEquals(AnswerResult.Refused("This question has changed or closed."), Sender(fake).answer(q, 0))
        assertTrue(fake.keyCommands().isEmpty())
    }

    @Test fun refuses_when_question_gone() = runTest {
        val fake = FakeExec(screens = listOf("plain prompt"))
        val q = QuestionParser.parse("api", permission)!!

        assertEquals(AnswerResult.Refused("This question has changed or closed."), Sender(fake).answer(q, 0))
        assertTrue(fake.keyCommands().isEmpty())
    }

    @Test fun refuses_when_capture_fails() = runTest {
        val fake = FakeExec().apply {
            exitOverride = 1
            stderrOverride = "can't find session: api"
        }
        val q = QuestionParser.parse("api", permission)!!

        assertEquals(AnswerResult.Refused("This question has changed or closed."), Sender(fake).answer(q, 0))
        assertTrue(fake.keyCommands().isEmpty())
    }

    @Test fun out_of_range() = runTest {
        val fake = FakeExec(screens = listOf(permission))
        val q = QuestionParser.parse("api", permission)!!

        assertEquals(AnswerResult.Refused("No such option."), Sender(fake).answer(q, 7))
        assertTrue(fake.keyCommands().isEmpty())
    }

    @Test fun refuses_when_selection_never_settles() = runTest {
        // Every re-capture still shows option 1 selected (same fingerprint - selected is excluded
        // from it), so the move to option 2 never appears to land within the 10 retries.
        val fake = FakeExec(screens = List(11) { permission })
        val q = QuestionParser.parse("api", permission)!!

        assertEquals(
            AnswerResult.Refused("Couldn't select that option — answer in the terminal."),
            Sender(fake).answer(q, 1),
        )
        // The move was attempted; only the final Enter was withheld.
        assertEquals(listOf("tmux -u send-keys -t '=api:' Down"), fake.keyCommands())
    }

    // --- sendText ------------------------------------------------------------------------------

    @Test fun text_strips_paste_markers_and_keeps_newlines() = runTest {
        val fake = FakeExec(screens = listOf("x", "x"))

        Sender(fake).sendText("api", "a\u001b[200~b\nc\u001b[201~")

        assertEquals("ab\nc", fake.stdinOf("load-buffer"))
        assertEquals("tmux -u send-keys -t '=api:' Enter", fake.keyCommands().last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun text_waits_for_two_consecutive_identical_captures_before_enter() = runTest {
        val fake = FakeExec(screens = listOf("a", "b", "b"))

        Sender(fake).sendText("api", "hi")

        val captures = fake.commands.map { it.command }.filter { "capture-pane" in it }
        assertEquals(3, captures.size)
        assertEquals(500L, currentTime) // two 250 ms polls before the screen is judged stable
    }

    @Test fun text_gives_up_waiting_after_four_polls_and_still_sends_enter() = runTest {
        // The screen never repeats itself - wait_for_quiet_screen times out, Enter is still sent.
        val fake = FakeExec(screens = listOf("a", "b", "c", "d", "e"))

        Sender(fake).sendText("api", "hi")

        assertEquals("tmux -u send-keys -t '=api:' Enter", fake.keyCommands().last())
    }

    @Test fun send_text_throws_io_exception_on_tmux_failure() = runTest {
        val fake = FakeExec().apply {
            exitOverride = 1
            stderrOverride = "no server running"
        }
        val error = try {
            Sender(fake).sendText("api", "hi")
            null
        } catch (e: IOException) {
            e
        }
        assertEquals("no server running", error?.message)
    }

    // --- sendKeys --------------------------------------------------------------------------------

    @Test fun send_keys_records_the_exact_command() = runTest {
        val fake = FakeExec()
        Sender(fake).sendKeys("api", listOf(TmuxKey.Escape))
        assertEquals(listOf("tmux -u send-keys -t '=api:' Escape"), fake.keyCommands())
    }

    @Test fun send_keys_throws_io_exception_on_tmux_failure() = runTest {
        val fake = FakeExec().apply {
            exitOverride = 1
            stderrOverride = "can't find session: api"
        }
        try {
            Sender(fake).sendKeys("api", listOf(TmuxKey.Enter))
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("can't find session: api", e.message)
        }
    }

    // --- enterCopyMode / scroll (R22 swipe-to-scroll) ---------------------------------------------

    @Test fun enter_copy_mode_records_the_exact_command() = runTest {
        val fake = FakeExec()
        Sender(fake).enterCopyMode("api")
        assertEquals(listOf("tmux -u copy-mode -e -t '=api:'"), fake.commands.map { it.command })
    }

    @Test fun scroll_records_the_exact_command() = runTest {
        val fake = FakeExec()
        Sender(fake).scroll("api", up = true, lines = 3)
        assertEquals(listOf("tmux -u send-keys -t '=api:' -X -N 3 scroll-up"), fake.commands.map { it.command })
    }

    @Test fun scroll_down_records_scroll_down() = runTest {
        val fake = FakeExec()
        Sender(fake).scroll("api", up = false, lines = 1)
        assertEquals(listOf("tmux -u send-keys -t '=api:' -X -N 1 scroll-down"), fake.commands.map { it.command })
    }

    @Test fun scroll_throws_io_exception_on_tmux_failure() = runTest {
        val fake = FakeExec().apply {
            exitOverride = 1
            stderrOverride = "can't find session: api"
        }
        try {
            Sender(fake).scroll("api", up = true, lines = 1)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("can't find session: api", e.message)
        }
    }

    // --- concurrency -----------------------------------------------------------------------------

    @Test fun answer_and_text_to_same_session_do_not_interleave() = runTest {
        val fake = FakeExec(screens = listOf(permission))
        val q = QuestionParser.parse("api", permission)!!
        val sender = Sender(fake)

        val answerCommands = listOf(
            Tmux.capture("api"),
            Tmux.sendKeys("api", listOf(TmuxKey.Enter)),
        )
        val textCommands = listOf(
            Tmux.loadAndPaste("api"),
            Tmux.capture("api"),
            Tmux.capture("api"),
            Tmux.sendKeys("api", listOf(TmuxKey.Enter)),
        )

        val jobs = listOf(
            launch { sender.answer(q, 0) }, // index 0 == already selected: no cursor move, Enter only
            launch { sender.sendText("api", "hello") },
        )
        jobs.forEach { it.join() }

        val recorded = fake.commands.map { it.command }
        val oneWholeOperationThenTheOther = recorded == answerCommands + textCommands ||
            recorded == textCommands + answerCommands
        assertTrue(
            "expected one whole operation to finish before the other started, got: $recorded",
            oneWholeOperationThenTheOther,
        )
    }

    /** [permission.txt] with the `❯` cursor moved onto option [index] (0-based), as in QuestionParserTest. */
    private fun selected(screen: String, index: Int): String {
        val options = listOf(
            "1. Yes",
            "2. Yes, allow all edits during this session (shift+tab)",
            "3. No, and tell Claude what to do differently (esc)",
        )
        var result = screen
        options.forEach { text -> result = result.replace("❯ $text", "  $text") }
        return result.replace("  ${options[index]}", "❯ ${options[index]}")
    }
}
