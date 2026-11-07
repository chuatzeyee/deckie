// Ported from deckaway's integrations/questions.py (`parse_question`, Claude path only):
// https://github.com/matacoder/deckaway — MIT License, Copyright (c) 2026 Denis Matakov.
// Codex's branches (async-form, "Queued follow-up inputs", the agent/instance/request_id/deck
// fields) and the transcript_* functions are not ported: Deck only watches the pane's visible
// screen over tmux, never the agent's own JSONL transcript.
package com.chuatzeyee.deck.core

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import java.security.MessageDigest

/** A question Claude is currently showing on screen (a permission dialog, a numbered menu, …). */
data class Question(
    val session: String,
    val title: String,
    val options: List<String>,
    val selected: Int,
    val progress: String,
) {
    /**
     * SHA-256 hex over everything that identifies *which* question this is: [session], [title],
     * [options] and [progress] - never [selected]. Moving the cursor with an arrow key changes
     * [selected] but must not look like a new question, or Deck would re-notify on every
     * keystroke; a new pane, a new conversation, or different wording always changes one of the
     * other fields and so always changes the fingerprint. The fields are serialized as a JSON
     * array (a fixed arity, ordered, type-tagged encoding) so no combination of values can
     * collide into the same bytes.
     */
    val fingerprint: String
        get() {
            val encoded = buildJsonArray {
                add(session)
                add(title)
                addJsonArray { options.forEach { add(it) } }
                add(progress)
            }.toString()
            val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
}

/**
 * Recognises a question Claude Code is asking on the visible terminal screen: an edit/bash
 * permission dialog, or a numbered-menu question (including a multi-question `AskUserQuestion`
 * form's "Question N/M" steps). Returns `null` for ordinary output, including a numbered list
 * that appears in Claude's own reply rather than in an active menu.
 */
object QuestionParser {

    private val ANSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val OPTION = Regex("^\\s*([›❯>●]?)\\s*(\\d{1,2})[.)]\\s+(.+)$")
    private val FOOTER = Regex(
        "(?:enter|return|⏎)\\s+(?:to\\s+)?(?:submit(?: (?:answer|all))?|select|confirm|continue|proceed)\\b" +
            // Claude Code's permission dialog since 2.1 names no Enter key: "Esc to cancel · Tab to amend".
            "|^esc to cancel\\b",
        RegexOption.IGNORE_CASE,
    )
    private val SOLID = Regex("^[─━═╭╰┌└][─━═╌┄╭╰┌└┐┘╮╯\\s]*$")
    private val RULE = Regex("^[─━═╌┄╭╰┌└][─━═╌┄╭╰┌└┐┘╮╯\\s]*$")
    // The last tab of Claude Code's form of several questions names no keys under its options.
    private val ASKS = Regex("\\?|would you|do you|allow|proceed|choose|выберите|разрешить", RegexOption.IGNORE_CASE)
    // Claude Code counts down before it denies by itself ("... automatically deny this request in 0:22, ..."): a
    // line that changes every second would make every second a new question (a notification each, answer refused).
    private val COUNTDOWN = Regex("\\bautomatically\\b.*\\bin \\d+:\\d\\d\\b", RegexOption.IGNORE_CASE)
    private val READY = Regex("^Ready to submit your answers\\?$", RegexOption.IGNORE_CASE)
    private val HEADER = Regex("^Question \\d+/\\d+")
    private val HISTORICAL_PICKER = Regex("^[›❯>]\\s+\\S")
    private val CHECKBOX = Regex("^\\[[ xX✓]\\]")

    /** A numbered menu found between its own start and the dialog's [end][MenuMatch.start]. */
    private data class MenuMatch(
        val rows: List<Pair<Int, MatchResult>>,
        val headers: List<Int>,
        val progress: String,
        val marked: Int,
        val start: Int,
    )

    fun parse(session: String, screen: String): Question? {
        val lines = cleanLines(screen)
        val end = findEnd(lines) ?: return null
        if (hasStalePickerOrNotesFocus(lines, end)) return null

        val menu = findMenu(lines, maxOf(0, end - 60), end) ?: return null
        val first = menu.rows[0].first
        val start = if (menu.headers.isEmpty()) findTitleStart(lines, menu.start, first) else menu.start

        val title = buildTitle(lines, start, first)
        if (title.isEmpty()) return null
        if (menu.headers.isEmpty() && !ASKS.containsMatchIn(title)) return null

        val options = menu.rows.map { it.second.groupValues[3].trim() }
        if (isCheckboxMenu(options) || mentionsSpaceToToggle(lines, start, end)) return null
        return Question(session, title.take(1600), options, menu.marked, menu.progress)
    }

    /** Strips ANSI escapes, keeps the last 120 lines, and trims each line like deckaway does. */
    private fun cleanLines(screen: String): List<String> =
        ANSI.replace(screen, "").split("\n").takeLast(120)
            .map { it.trim().trim { c -> c == '│' || c == '┃' }.trim() }

    /** The line a footer invites a key press on, or where the "Ready to submit…" menu ends. */
    private fun findEnd(lines: List<String>): Int? {
        val footers = lines.indices.filter { FOOTER.containsMatchIn(lines[it]) }
        return footers.lastOrNull() ?: submitEnd(lines)
    }

    /** Where the options under "Ready to submit your answers?" end, or `null` without that question. */
    private fun submitEnd(lines: List<String>): Int? {
        val ready = lines.indices.filter { READY.matchEntire(lines[it]) != null }
        if (ready.isEmpty()) return null
        val first = (ready.last() + 1 until lines.size).firstOrNull { lines[it].isNotEmpty() } ?: lines.size
        var end = first
        while (end < lines.size && OPTION.matchEntire(lines[end]) != null) end++
        return if (end > first) end else null
    }

    /**
     * True when a historical picker sits above a new composer (must never receive an answer), or
     * the cursor is in the notes field (which needs a different adapter, never selection keys).
     */
    private fun hasStalePickerOrNotesFocus(lines: List<String>, end: Int): Boolean {
        val stalePicker = lines.drop(end + 1)
            .any { HISTORICAL_PICKER.containsMatchIn(it) && OPTION.matchEntire(it) == null }
        val notesWindow = lines.subList(maxOf(0, end - 2), minOf(lines.size, end + 2))
        return stalePicker || notesWindow.any { it.lowercase().contains("to clear notes") }
    }

    /** The final contiguous `1.`-based numbered menu in `[start, end)`, with exactly one cursor row. */
    private fun findMenu(lines: List<String>, outerStart: Int, end: Int): MenuMatch? {
        var start = outerStart
        val headers = (start until end).filter { HEADER.containsMatchIn(lines[it]) }
        var progress = ""
        if (headers.isNotEmpty()) {
            start = headers.last() + 1
            progress = HEADER.find(lines[headers.last()])?.value.orEmpty()
        }
        val rows = (start until end).mapNotNull { i -> OPTION.matchEntire(lines[i])?.let { i to it } }
        if (rows.isEmpty() || rows.none { it.second.groupValues[1].isNotEmpty() }) return null
        // Keep only the final contiguous numbered menu (not a numbered list in the question).
        val restart = rows.indexOfLast { it.second.groupValues[2] == "1" }
        if (restart < 0) return null
        val menuRows = rows.subList(restart, rows.size)
        if (menuRows.map { it.second.groupValues[2].toInt() } != (1..menuRows.size).toList() || menuRows.size > 9) {
            return null
        }
        val marked = menuRows.indices.filter { menuRows[it].second.groupValues[1].isNotEmpty() }
        if (marked.size != 1) return null
        return MenuMatch(menuRows, headers, progress, marked[0], start)
    }

    /**
     * A solid rule bounds the dialog and keeps the conversation out of notifications; dashed
     * rules inside it frame the command a permission dialog asks about, which the answer must
     * not be given without.
     */
    private fun findTitleStart(lines: List<String>, start: Int, first: Int): Int {
        val solid = (start until first).filter { SOLID.matchEntire(lines[it]) != null }
        val dashed = (start until first).filter { RULE.matchEntire(lines[it]) != null }
        val near = maxOf(start, first - 6)
        val asked = (start until near).filter { ASKS.containsMatchIn(lines[it]) }
        val far = if (asked.isNotEmpty() && lines.subList(near, first).none { ASKS.containsMatchIn(it) }) {
            asked.last()
        } else {
            near
        }
        return when {
            solid.isNotEmpty() -> solid.last() + 1
            dashed.isNotEmpty() -> dashed.last() + 1
            else -> far
        }
    }

    private fun buildTitle(lines: List<String>, start: Int, first: Int): String =
        lines.subList(start, first)
            .filter {
                it.isNotEmpty() && RULE.matchEntire(it) == null && !it.startsWith("Tip: ") && !COUNTDOWN.containsMatchIn(it)
            }
            .joinToString("\n")
            .trim()

    private fun isCheckboxMenu(options: List<String>): Boolean = options.any { CHECKBOX.containsMatchIn(it) }

    private fun mentionsSpaceToToggle(lines: List<String>, start: Int, end: Int): Boolean =
        lines.subList(start, minOf(lines.size, end + 2)).any { it.lowercase().contains("space to toggle") }
}
