package com.chuatzeyee.deck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chuatzeyee.deck.core.Host
import com.chuatzeyee.deck.poll.PollResult

/**
 * After connecting, lists this host's live tmux sessions as checkboxes (already-chosen ones
 * checked, from [Host.sessions] + [PollResult.notAdded] - a chosen session absent from this tick
 * stays checked, marked "(not running)", fix round 1 important #5) with up/down reordering; Save
 * calls [onSave] with the checked names in their current order - see Task 10 brief Step 3.
 */
@Composable
fun SessionPickerScreen(
    host: Host,
    result: PollResult?,
    onSave: (hostId: String, names: List<String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var entries by remember(result) { mutableStateOf(result?.let { pickerEntries(host.sessions, it) } ?: emptyList()) }

    Column(modifier = modifier.padding(16.dp)) {
        Text("Sessions on ${host.name}")

        when {
            result == null -> Text("Connecting…")
            result.noServer -> Text("No tmux sessions on ${host.name}")
            entries.isEmpty() -> Text("No tmux sessions on ${host.name}")
            else -> LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(entries.size) { index ->
                    SessionRow(
                        entry = entries[index],
                        canMoveUp = index > 0,
                        canMoveDown = index < entries.size - 1,
                        onToggle = { entries = toggleEntry(entries, index) },
                        onMoveUp = { entries = moveEntry(entries, index, -1) },
                        onMoveDown = { entries = moveEntry(entries, index, 1) },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text("Back") }
            Button(onClick = { onSave(host.id, chosenNames(entries)) }) { Text("Save") }
        }
    }
}

@Composable
private fun SessionRow(
    entry: PickerEntry,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Row(
            modifier = Modifier.toggleable(value = entry.checked, onValueChange = { onToggle() }, role = Role.Checkbox),
        ) {
            Checkbox(checked = entry.checked, onCheckedChange = null)
            Text(if (entry.running) entry.name else "${entry.name} (not running)")
        }
        Row {
            Button(
                onClick = onMoveUp,
                enabled = canMoveUp,
                modifier = Modifier.semantics { contentDescription = "Move ${entry.name} up" },
            ) { Text("Up") }
            Button(
                onClick = onMoveDown,
                enabled = canMoveDown,
                modifier = Modifier.semantics { contentDescription = "Move ${entry.name} down" },
            ) { Text("Down") }
        }
    }
}
