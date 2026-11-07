package com.chuatzeyee.deck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chuatzeyee.deck.core.Tmux
import com.chuatzeyee.deck.data.HostRepository
import com.chuatzeyee.deck.ssh.Exec
import java.io.IOException
import kotlinx.coroutines.flow.first

/**
 * Which session-management dialog (if any) is showing on the Deck screen - produced by
 * [DeckController]'s `openMenu`/`openMenuFor`/`request*`/`confirm*` methods, rendered by
 * [SessionMenu]. Each dialog's own `error`, when set, is the previous attempt's failure message
 * (tmux's own trimmed stderr, or a short "not connected" - see [SessionActions]) - shown inline,
 * without closing the dialog, so the user can correct and retry.
 */
sealed interface SessionMenuState {
    data object Closed : SessionMenuState

    /** The general menu: "New session" plus every known session's Rename/Kill actions. [focus]
     * (set by a rail row's long-press) is passed straight through to [SessionMenu] - purely which
     * row it opened from, nothing else behaves differently. */
    data class Open(val focus: String? = null) : SessionMenuState

    data class NewSession(val defaultDir: String, val error: String? = null) : SessionMenuState

    data class Rename(val old: String, val error: String? = null) : SessionMenuState

    data class KillConfirm(val name: String, val error: String? = null) : SessionMenuState
}

/**
 * [DeckScreen]'s session-menu plumbing, bundled into one param so that composable's own signature
 * doesn't balloon (fix round 1, important #6's pattern) - every callback forwards straight to an
 * [AppViewModel]/[DeckController] method of the same name.
 */
data class SessionMenuActions(
    val onOpen: () -> Unit,
    val onOpenFor: (String) -> Unit,
    val onDismiss: () -> Unit,
    val onNewSessionRequest: () -> Unit,
    val onRenameRequest: (String) -> Unit,
    val onKillRequest: (String) -> Unit,
    val onCreate: (name: String, dir: String, startClaude: Boolean) -> Unit,
    val onRename: (new: String) -> Unit,
    val onKill: () -> Unit,
)

/**
 * Creates, renames, and kills tmux sessions on one host over [exec] - the host's existing polling
 * channel (see [DeckController]), never a fresh connection - keeping [HostRepository]'s chosen
 * sessions for [hostId] in sync: create appends the new name, rename replaces the old name in
 * place, kill removes it. A non-zero tmux exit is returned as a [Result.failure] carrying tmux's
 * own trimmed stderr (e.g. `"duplicate session: api"`) - that's tmux's output, not Deck's, so it's
 * fine to show to the user as-is. An invalid name is rejected before anything reaches tmux.
 */
class SessionActions(
    private val exec: Exec,
    private val repo: HostRepository,
    private val hostId: String,
) {
    suspend fun create(name: String, dir: String, startClaude: Boolean): Result<Unit> {
        if (!Tmux.isValidNewName(name)) return invalidName(name)
        return runTmux(Tmux.newSession(name, dir, startClaude)) { chosen -> chosen + name }
    }

    suspend fun rename(old: String, new: String): Result<Unit> {
        if (!Tmux.isValidNewName(new)) return invalidName(new)
        // .distinct() (fix round 1, minor): renaming "a" to an existing chosen name "b" would
        // otherwise leave "b" listed twice in `chosen` - tmux itself allows the rename regardless.
        return runTmux(Tmux.rename(old, new)) { chosen -> chosen.map { if (it == old) new else it }.distinct() }
    }

    suspend fun kill(name: String): Result<Unit> =
        runTmux(Tmux.kill(name)) { chosen -> chosen.filterNot { it == name } }

    private fun invalidName(name: String): Result<Unit> =
        Result.failure(IllegalArgumentException("\"$name\" isn't a valid session name."))

    private suspend fun runTmux(command: String, updateChosen: (List<String>) -> List<String>): Result<Unit> {
        val result = exec.run(command, null)
        if (result.exit != 0) {
            // tmux's own stderr is normally descriptive ("duplicate session: api"); fall back to
            // the exit code on the rare empty-stderr failure instead of an empty message (fix
            // round 1, minor).
            val message = result.stderr.trim().ifEmpty { "tmux exited ${result.exit}" }
            return Result.failure(IOException(message))
        }
        val chosen = repo.hosts.first().firstOrNull { it.id == hostId }?.sessions ?: emptyList()
        repo.setSessions(hostId, updateChosen(chosen))
        return Result.success(Unit)
    }
}

/** Dispatches to the dialog for [state]; a no-op for [SessionMenuState.Closed]. [actionInFlight]
 * (fix round 1, minor) disables the open dialog's confirm button while a previous tap's action is
 * still running, so a double tap can't run it twice. */
@Composable
fun SessionMenu(
    state: SessionMenuState,
    sessions: List<String>,
    actionInFlight: Boolean,
    onNewSession: () -> Unit,
    onRenameRequest: (String) -> Unit,
    onKillRequest: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: (name: String, dir: String, startClaude: Boolean) -> Unit,
    onRename: (new: String) -> Unit,
    onKill: () -> Unit,
) {
    when (state) {
        SessionMenuState.Closed -> Unit
        is SessionMenuState.Open -> SessionList(sessions, state.focus, onNewSession, onRenameRequest, onKillRequest, onDismiss)
        is SessionMenuState.NewSession -> NewSessionDialog(state, actionInFlight, onCreate, onDismiss)
        is SessionMenuState.Rename -> RenameDialog(state, actionInFlight, onRename, onDismiss)
        is SessionMenuState.KillConfirm -> KillConfirmDialog(state, actionInFlight, onKill, onDismiss)
    }
}

/** [focus] (the rail row a long-press opened this from, if any - see [SessionMenuState.Open])
 * highlights that row the same way [Rail] highlights the selected one (fix round 1, minor: this
 * was the only consumer of `focus`, previously threaded through but never rendered). */
@Composable
private fun SessionList(
    sessions: List<String>,
    focus: String?,
    onNewSession: () -> Unit,
    onRenameRequest: (String) -> Unit,
    onKillRequest: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
        title = { Text("Sessions") },
        text = {
            Column {
                Button(onClick = onNewSession) { Text("New session…") }
                sessions.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (name == focus) DeckColors.SelectedRow else Color.Transparent)
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, modifier = Modifier.weight(1f))
                        Button(onClick = { onRenameRequest(name) }) { Text("Rename") }
                        Button(onClick = { onKillRequest(name) }) { Text("Kill") }
                    }
                }
            }
        },
    )
}

@Composable
private fun NewSessionDialog(
    state: SessionMenuState.NewSession,
    actionInFlight: Boolean,
    onCreate: (name: String, dir: String, startClaude: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var dir by remember { mutableStateOf(state.defaultDir) }
    var startClaude by remember { mutableStateOf(true) }
    val nameError = name.isNotEmpty() && !Tmux.isValidNewName(name)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    isError = nameError,
                    supportingText = { if (nameError) Text("Invalid session name") },
                )
                OutlinedTextField(
                    value = dir,
                    onValueChange = { dir = it },
                    label = { Text("Working directory") },
                    placeholder = { Text("tmux default") },
                    // fix round 1, minor: "~" is a shell expansion, never performed here - see
                    // Tmux.newSession's own doc for why a leading "~" would reach tmux literally.
                    supportingText = { Text("absolute path (~ is not expanded)") },
                )
                Row(
                    modifier = Modifier.toggleable(
                        value = startClaude,
                        onValueChange = { startClaude = it },
                        role = Role.Checkbox,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = startClaude, onCheckedChange = null)
                    Text("Start Claude")
                }
                state.error?.let { Text(it, color = DeckColors.Attention) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, dir, startClaude) },
                enabled = Tmux.isValidNewName(name) && !actionInFlight,
            ) {
                Text("Create")
            }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(
    state: SessionMenuState.Rename,
    actionInFlight: Boolean,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val nameError = name.isNotEmpty() && !Tmux.isValidNewName(name)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename \"${state.old}\"") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("New name") },
                    isError = nameError,
                    supportingText = { if (nameError) Text("Invalid session name") },
                )
                state.error?.let { Text(it, color = DeckColors.Attention) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onRename(name) },
                enabled = Tmux.isValidNewName(name) && !actionInFlight,
            ) { Text("Rename") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun KillConfirmDialog(
    state: SessionMenuState.KillConfirm,
    actionInFlight: Boolean,
    onKill: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column {
                Text("Kill session \"${state.name}\"? Anything running in it (including Claude) stops.")
                state.error?.let { Text(it, color = DeckColors.Attention) }
            }
        },
        confirmButton = {
            Button(
                onClick = onKill,
                enabled = !actionInFlight,
                colors = ButtonDefaults.buttonColors(containerColor = DeckColors.Attention, contentColor = DeckColors.Background),
            ) { Text("Kill") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}
