package com.chuatzeyee.deck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionParserTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("screens/$name")) {
            "missing fixture screens/$name"
        }.bufferedReader(Charsets.UTF_8).readText()

    @Test fun permission_dialog() {
        val q = QuestionParser.parse("api", fixture("permission.txt"))!!
        assertEquals(
            listOf(
                "Yes",
                "Yes, allow all edits during this session (shift+tab)",
                "No, and tell Claude what to do differently (esc)",
            ),
            q.options,
        )
        assertEquals(0, q.selected)
        assertTrue(q.title.contains("Do you want to make this edit to teams.py?"))
    }

    @Test fun cursor_move_keeps_fingerprint() {
        val moved = fixture("permission.txt").replace("❯ 1. Yes", "  1. Yes").replace("  2. Yes, allow", "❯ 2. Yes, allow")
        val a = QuestionParser.parse("api", fixture("permission.txt"))!!
        val b = QuestionParser.parse("api", moved)!!
        assertEquals(1, b.selected)
        assertEquals(a.fingerprint, b.fingerprint)
    }

    @Test fun countdown_does_not_change_fingerprint() {
        val t1 = fixture("permission.txt").replace(
            " Esc to cancel",
            " Claude will automatically deny this request in 0:22\n Esc to cancel",
        )
        val t2 = t1.replace("0:22", "0:21")
        assertEquals(
            QuestionParser.parse("api", t1)!!.fingerprint,
            QuestionParser.parse("api", t2)!!.fingerprint,
        )
    }

    @Test fun other_session_other_fingerprint() = assertNotEquals(
        QuestionParser.parse("a", fixture("permission.txt"))!!.fingerprint,
        QuestionParser.parse("b", fixture("permission.txt"))!!.fingerprint,
    )

    @Test fun numbered_list_in_answer_is_not_a_question() =
        assertNull(QuestionParser.parse("api", fixture("numbered-answer.txt")))

    @Test fun picker_above_new_composer_is_ignored() =
        assertNull(QuestionParser.parse("api", fixture("permission.txt") + "\n> next prompt typed\n"))

    @Test fun checkbox_menu_is_ignored() = assertNull(
        QuestionParser.parse("api", fixture("permission.txt").replace("1. Yes", "1. [ ] Yes")),
    )

    @Test fun ansi_codes_are_stripped() = assertNotNull(
        QuestionParser.parse("api", fixture("permission.txt").replace("Yes", "\u001b[1mYes\u001b[0m")),
    )

    // Synthetic screens with the structure of real Claude Code captures (ANSI colours, box rules,
    // the composer prompt and mode footer) but invented text.
    @Test fun idle_screens_are_not_questions() {
        // An ordinary Claude reply whose text happens to contain a numbered list: not a menu,
        // no footer inviting a key press, so it must never be treated as a question.
        assertNull(QuestionParser.parse("api", fixture("numbered-answer.txt")))
        // An idle Claude screen with no pending question at all.
        assertNull(QuestionParser.parse("api", fixture("ordinary-reply.txt")))
    }

    private fun permissionWith(old: String, new: String): String {
        val screen = fixture("permission.txt")
        require(screen.contains(old)) { "fixture lacks: $old" }
        return screen.replace(old, new)
    }

    @Test fun zero_marked_rows_is_not_a_question() =
        assertNull(QuestionParser.parse("api", permissionWith("❯ 1. Yes", "  1. Yes")))

    @Test fun two_marked_rows_is_not_a_question() =
        assertNull(QuestionParser.parse("api", permissionWith("  2. Yes, allow", "❯ 2. Yes, allow")))

    @Test fun more_than_nine_options_is_not_a_question() {
        val extra = (4..10).joinToString("") { "│   $it. Option $it │\n" }
        assertNull(QuestionParser.parse("api", permissionWith("╰", extra + "╰")))
    }

    @Test fun non_contiguous_numbering_is_not_a_question() =
        assertNull(QuestionParser.parse("api", permissionWith("3. No", "4. No")))

    @Test fun title_without_a_question_is_not_a_question() = assertNull(
        QuestionParser.parse("api", permissionWith("Do you want to make this edit to teams.py?", "Edit applied to teams.py.")),
    )

    @Test fun progress_header_is_part_of_the_fingerprint() {
        val first = QuestionParser.parse("api", permissionWith("│ Edit file", "│ Question 1/2 │\n│ Edit file"))!!
        val second = QuestionParser.parse("api", permissionWith("│ Edit file", "│ Question 2/2 │\n│ Edit file"))!!
        assertEquals("Question 1/2", first.progress)
        assertNotEquals(first.fingerprint, second.fingerprint)
    }
}
