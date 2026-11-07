package com.chuatzeyee.deck.core

/** One tmux session as reported by [Tmux.LIST]. */
data class TmuxSession(
    val name: String,
    val activity: Long,
    val width: Int,
    val height: Int,
    val path: String,
    val command: String,
)

/** A key tmux `send-keys` understands by name, as opposed to literal text. */
enum class TmuxKey(val token: String) {
    Enter("Enter"),
    Up("Up"),
    Down("Down"),
    Escape("Escape"),
    CtrlC("C-c"),
    Tab("Tab"),
    BackTab("BTab"),
}

/**
 * Builds the exact shell command strings sent to tmux over the SSH exec channel, and parses
 * their output. Every session name and directory is passed through [quote]; session targets
 * always use tmux's exact-match syntax `=<name>:` via [target] so a session name that is a
 * prefix of another session's name is never ambiguous. Every invocation is forced into UTF-8
 * mode with `-u`: without it, tmux 3.2a sanitizes any byte it doesn't recognise as 7-bit ASCII
 * (including our `\u001f` field separator and non-ASCII session names) to `_` whenever the
 * remote client's locale isn't UTF-8 - which an SSH exec channel usually isn't.
 */
object Tmux {

    private const val TMUX = "tmux -u"
    private const val UNIT_SEPARATOR = "\u001f"
    private val LIST_FIELDS = listOf(
        "#{session_name}",
        "#{window_activity}",
        "#{window_width}",
        "#{window_height}",
        "#{pane_current_path}",
        "#{pane_current_command}",
    )

    /**
     * Session-name prefixes that tmux resolves specially even inside a `=<name>:` exact-match
     * target: `$N` is a session id, `@N` a window id, `%N` a pane id. tmux will happily let a
     * session be *named* `$1`, but then `-t '=$1:'` targets session id 1, not the session named
     * `$1` - so these names are unsafe to show or target and must never reach the rest of Deck.
     */
    private val UNSAFE_NAME_PREFIXES = charArrayOf('$', '@', '%')

    /** POSIX single-quote escaping: safe to splice into a shell command unquoted elsewhere. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** tmux's exact-match session target syntax. */
    fun target(session: String): String = "=$session:"

    /** Lists every session with a stable, parseable, unit-separator-delimited format. */
    val LIST: String = "$TMUX list-sessions -F " + quote(LIST_FIELDS.joinToString(UNIT_SEPARATOR))

    /**
     * Parses [LIST]'s stdout. Lines with the wrong field count or unparseable numbers are
     * skipped, as are sessions whose name starts with `$`, `@` or `%` (see
     * [UNSAFE_NAME_PREFIXES]) - Deck never shows or targets those.
     */
    fun parseList(stdout: String): List<TmuxSession> =
        stdout.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull(::parseLine)
            .toList()

    private fun parseLine(line: String): TmuxSession? {
        val fields = line.split(UNIT_SEPARATOR)
        if (fields.size != LIST_FIELDS.size) return null
        val name = fields[0]
        if (name.isEmpty() || name[0] in UNSAFE_NAME_PREFIXES) return null
        val activity = fields[1].toLongOrNull() ?: return null
        val width = fields[2].toIntOrNull() ?: return null
        val height = fields[3].toIntOrNull() ?: return null
        return TmuxSession(name, activity, width, height, fields[4], fields[5])
    }

    /** True when [exit]/[stderr] indicate tmux has no server running on the host, not a real error. */
    fun isNoServer(exit: Int, stderr: String): Boolean {
        if (exit == 0) return false
        val lower = stderr.lowercase()
        return lower.contains("no server running") || lower.contains("error connecting to")
    }

    /** Dumps the visible pane with ANSI colours (`-e`), joining wrapped lines (`-J`). */
    fun capture(session: String, lines: Int = 120): String {
        require(lines > 0) { "lines must be positive, was $lines" }
        return "$TMUX capture-pane -p -e -J -t ${quote(target(session))} -S -$lines"
    }

    /** Sends a sequence of named keys (e.g. arrow navigation, Enter) to the session. */
    fun sendKeys(session: String, keys: List<TmuxKey>): String =
        "$TMUX send-keys -t ${quote(target(session))} " + keys.joinToString(" ") { it.token }

    /**
     * Loads text piped on the command's stdin into tmux buffer `deck`, then pastes it into the
     * session as bracketed paste (`-p`) so the receiving program can tell it apart from typed
     * input, and deletes the buffer afterward (`-d`).
     */
    fun loadAndPaste(session: String): String =
        "$TMUX load-buffer -b deck - \\; paste-buffer -p -d -b deck -t ${quote(target(session))}"

    /**
     * Creates a detached session in `dir`, optionally launching `claude` in it immediately. A
     * blank `dir` omits `-c` entirely rather than quoting a placeholder like `"~"`: `-c` takes a
     * literal path for `chdir(2)`, not a shell argument, so it is never expanded - tmux then
     * starts the session in its own default directory (ordinarily `$HOME` on a fresh SSH login).
     */
    fun newSession(name: String, dir: String, startClaude: Boolean): String {
        val cd = if (dir.isBlank()) "" else " -c ${quote(dir)}"
        val base = "$TMUX new-session -d -s ${quote(name)}$cd"
        if (!startClaude) return base
        return "$base \\; send-keys -t ${quote(target(name))} claude Enter"
    }

    fun rename(old: String, new: String): String =
        "$TMUX rename-session -t ${quote(target(old))} ${quote(new)}"

    fun kill(session: String): String = "$TMUX kill-session -t ${quote(target(session))}"

    fun attach(session: String): String = "$TMUX attach-session -t ${quote(target(session))}"

    /** Enters copy mode (`-e` so tmux auto-exits it once the view scrolls back to the bottom) -
     * the first step of swipe-to-scroll; see [scroll]. */
    fun copyMode(session: String): String = "$TMUX copy-mode -e -t ${quote(target(session))}"

    /** Leaves copy-mode on [session]'s pane. tmux exits non-zero ("not in a mode") when the pane
     * already left copy-mode on its own - callers treat that exit as harmless. */
    fun cancelCopyMode(session: String): String = "$TMUX send-keys -t ${quote(target(session))} -X cancel"

    /** Scrolls [session]'s copy-mode view by [lines] rows (`-N`), up or down. Must run after
     * [copyMode]. */
    fun scroll(session: String, up: Boolean, lines: Int): String {
        require(lines > 0) { "lines must be positive, was $lines" }
        val direction = if (up) "scroll-up" else "scroll-down"
        return "$TMUX send-keys -t ${quote(target(session))} -X -N $lines $direction"
    }

    /**
     * 1-40 chars, no `.`/`:` (both are meaningful to tmux targets), no control chars, not blank,
     * and not starting with `$`, `@` or `%` (see [UNSAFE_NAME_PREFIXES]) - such a name would make
     * `target()` resolve to a session/window/pane id instead of this session.
     */
    fun isValidNewName(name: String): Boolean =
        name.isNotBlank() &&
            name.length in 1..40 &&
            name[0] !in UNSAFE_NAME_PREFIXES &&
            name.none { it == '.' || it == ':' || it.isISOControl() }
}
