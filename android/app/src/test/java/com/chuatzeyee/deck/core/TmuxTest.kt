package com.chuatzeyee.deck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxTest {

    @Test fun quote_escapes_single_quotes() = assertEquals("'a'\\''b'", Tmux.quote("a'b"))

    @Test fun quote_keeps_spaces_inside() = assertEquals("'my work'", Tmux.quote("my work"))

    @Test fun capture_targets_exact_session() =
        assertEquals("tmux -u capture-pane -p -e -J -t '=api:' -S -120", Tmux.capture("api"))

    @Test fun capture_rejects_non_positive_lines() {
        try {
            Tmux.capture("api", lines = 0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun list_command_is_pinned() =
        assertEquals(
            "tmux -u list-sessions -F " +
                "'#{session_name}\u001f#{window_activity}\u001f#{window_width}" +
                "\u001f#{window_height}\u001f#{pane_current_path}\u001f#{pane_current_command}'",
            Tmux.LIST,
        )

    @Test fun list_command_forces_utf8_mode() = assertTrue(Tmux.LIST.startsWith("tmux -u "))

    @Test fun parse_list_reads_fields() {
        val out = listOf("api", "1794038884", "133", "44", "/home/u/api", "claude").joinToString("\u001f") + "\n" +
            listOf("my work", "1794038000", "80", "24", "/tmp", "bash").joinToString("\u001f") + "\n"
        assertEquals(
            listOf(
                TmuxSession("api", 1794038884, 133, 44, "/home/u/api", "claude"),
                TmuxSession("my work", 1794038000, 80, 24, "/tmp", "bash"),
            ),
            Tmux.parseList(out),
        )
    }

    @Test fun parse_list_skips_malformed_lines() = assertEquals(emptyList<TmuxSession>(), Tmux.parseList("garbage\n\n"))

    @Test fun parse_list_drops_sessions_with_unsafe_name_prefixes() {
        val out = listOf(
            listOf("\$1", "1794038884", "80", "24", "/tmp", "bash").joinToString("\u001f"),
            listOf("@2", "1794038884", "80", "24", "/tmp", "bash").joinToString("\u001f"),
            listOf("%3", "1794038884", "80", "24", "/tmp", "bash").joinToString("\u001f"),
            listOf("api", "1794038884", "80", "24", "/tmp", "bash").joinToString("\u001f"),
        ).joinToString("\n")
        assertEquals(
            listOf(TmuxSession("api", 1794038884, 80, 24, "/tmp", "bash")),
            Tmux.parseList(out),
        )
    }

    @Test fun no_server_is_recognised() {
        assertTrue(Tmux.isNoServer(1, "no server running on /tmp/tmux-1000/default"))
        assertTrue(Tmux.isNoServer(1, "error connecting to /tmp/tmux-1000/default (No such file or directory)"))
        assertFalse(Tmux.isNoServer(0, ""))
        assertFalse(Tmux.isNoServer(1, "can't find session: api"))
    }

    @Test fun send_keys_uses_tokens() =
        assertEquals(
            "tmux -u send-keys -t '=a'\\''b:' Down Down Enter",
            Tmux.sendKeys("a'b", listOf(TmuxKey.Down, TmuxKey.Down, TmuxKey.Enter)),
        )

    @Test fun load_and_paste_targets_session_and_deck_buffer() =
        assertEquals(
            "tmux -u load-buffer -b deck - \\; paste-buffer -p -d -b deck -t '=api:'",
            Tmux.loadAndPaste("api"),
        )

    @Test fun new_session_with_claude() = assertEquals(
        "tmux -u new-session -d -s 'x y' -c '/home/u' \\; send-keys -t '=x y:' claude Enter",
        Tmux.newSession("x y", "/home/u", startClaude = true),
    )

    @Test fun new_session_without_claude() = assertEquals(
        "tmux -u new-session -d -s 'x y' -c '/home/u'",
        Tmux.newSession("x y", "/home/u", startClaude = false),
    )

    @Test fun new_session_with_blank_dir_omits_c_flag() = assertEquals(
        "tmux -u new-session -d -s 'x y'",
        Tmux.newSession("x y", "", startClaude = false),
    )

    @Test fun rename_targets_old_session_with_quoted_new_name() =
        assertEquals("tmux -u rename-session -t '=api:' 'my work'", Tmux.rename("api", "my work"))

    @Test fun kill_targets_exact_session() = assertEquals("tmux -u kill-session -t '=api:'", Tmux.kill("api"))

    @Test fun kill_does_not_target_a_similarly_named_session() {
        // '=api:' is tmux's exact-match syntax: it must never also match a session named "api2".
        assertEquals("tmux -u kill-session -t '=api:'", Tmux.kill("api"))
        assertEquals("tmux -u kill-session -t '=api2:'", Tmux.kill("api2"))
    }

    @Test fun attach_targets_exact_session() = assertEquals("tmux -u attach-session -t '=api:'", Tmux.attach("api"))

    @Test fun copy_mode_auto_exits_at_bottom() =
        assertEquals("tmux -u copy-mode -e -t '=api:'", Tmux.copyMode("api"))

    @Test fun cancel_copy_mode_targets_the_exact_session() =
        assertEquals("tmux -u send-keys -t '=a'\\''b:' -X cancel", Tmux.cancelCopyMode("a'b"))

    @Test fun scroll_up_sends_X_scroll_up_with_count() =
        assertEquals("tmux -u send-keys -t '=api:' -X -N 3 scroll-up", Tmux.scroll("api", up = true, lines = 3))

    @Test fun scroll_down_sends_X_scroll_down_with_count() =
        assertEquals("tmux -u send-keys -t '=api:' -X -N 5 scroll-down", Tmux.scroll("api", up = false, lines = 5))

    @Test fun scroll_rejects_non_positive_lines() {
        try {
            Tmux.scroll("api", up = true, lines = 0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun names() {
        assertTrue(Tmux.isValidNewName("api-2"))
        assertFalse(Tmux.isValidNewName("a.b"))
        assertFalse(Tmux.isValidNewName("a:b"))
        assertFalse(Tmux.isValidNewName(" "))
    }

    @Test fun names_enforce_length_bounds() {
        assertTrue(Tmux.isValidNewName("a".repeat(40)))
        assertFalse(Tmux.isValidNewName("a".repeat(41)))
        assertFalse(Tmux.isValidNewName(""))
    }

    @Test fun names_reject_ids_masquerading_as_names() {
        assertFalse(Tmux.isValidNewName("\$1"))
        assertFalse(Tmux.isValidNewName("@2"))
        assertFalse(Tmux.isValidNewName("%3"))
    }
}
