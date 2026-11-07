package com.chuatzeyee.deck.ui

import com.chuatzeyee.deck.poll.PollResult

/** One row in the session picker. [running] is false for a chosen session that didn't come back
 * in this tick's live list (fix round 1, important #5) - still checked, shown as "(not running)". */
data class PickerEntry(val name: String, val checked: Boolean, val running: Boolean)

/**
 * Builds the picker's rows: every session in [configuredSessions] (the host's already-chosen
 * sessions, in order) stays checked even if it's temporarily absent from [result] - Save must
 * never silently drop a chosen session just because it wasn't alive on this one poll tick. Any
 * live session [result] reports that isn't already configured is added, unchecked.
 */
fun pickerEntries(configuredSessions: List<String>, result: PollResult): List<PickerEntry> {
    val liveNames = result.views.map { it.name }.toSet()
    val chosen = configuredSessions.distinct()
    val chosenRows = chosen.map { name -> PickerEntry(name, checked = true, running = name in liveNames) }
    val newRows = result.notAdded.filter { it !in chosen }.map { PickerEntry(it, checked = false, running = true) }
    return chosenRows + newRows
}

fun toggleEntry(entries: List<PickerEntry>, index: Int): List<PickerEntry> =
    entries.mapIndexed { i, entry -> if (i == index) entry.copy(checked = !entry.checked) else entry }

fun moveEntry(entries: List<PickerEntry>, index: Int, delta: Int): List<PickerEntry> {
    val target = index + delta
    if (index !in entries.indices || target !in entries.indices) return entries
    return entries.toMutableList().apply {
        val moved = this[index]
        this[index] = this[target]
        this[target] = moved
    }
}

fun chosenNames(entries: List<PickerEntry>): List<String> = entries.filter { it.checked }.map { it.name }
