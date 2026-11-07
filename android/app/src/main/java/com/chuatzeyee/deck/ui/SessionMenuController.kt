package com.chuatzeyee.deck.ui

import android.util.Log
import com.chuatzeyee.deck.core.Host
import com.chuatzeyee.deck.data.HostRepository
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the session-menu dialog state (see [SessionMenuState]) and the create/rename/kill actions
 * behind it - split out of [DeckController] so that class stays under the file-size limit (fix
 * round 1, important; the same reason [DeckController] itself was split out of [AppViewModel]).
 * [DeckController] delegates every session-menu method here unchanged.
 */
class SessionMenuController(
    private val scope: CoroutineScope,
    private val repo: HostRepository,
    private val currentHostId: () -> String?,
    private val currentSessionActions: () -> SessionActions?,
    private val kickHostLink: () -> Unit,
    private val onHostUpdated: (Host) -> Unit,
    private val selected: () -> String?,
    private val select: (String) -> Unit,
    private val clearSelection: () -> Unit,
) {
    private val _menuState = MutableStateFlow<SessionMenuState>(SessionMenuState.Closed)
    val menuState: StateFlow<SessionMenuState> = _menuState.asStateFlow()

    /** True while a confirm* action is running - [SessionMenu] disables its confirm button on this
     * so a double tap can't run the action twice (fix round 1, minor; [sessionActionJob] is the
     * actual guard, this is just its UI reflection). */
    private val _actionInFlight = MutableStateFlow(false)
    val actionInFlight: StateFlow<Boolean> = _actionInFlight.asStateFlow()

    /** Guards the confirm actions: only one may run at a time (fix round 1, minor). */
    private var sessionActionJob: Job? = null

    fun openMenu() {
        _menuState.value = SessionMenuState.Open()
    }

    /** Long-press on a rail row: opens the same menu, with [session] noted as where it was opened
     * from. */
    fun openMenuFor(session: String) {
        _menuState.value = SessionMenuState.Open(focus = session)
    }

    fun dismissMenu() {
        _menuState.value = SessionMenuState.Closed
    }

    fun requestNewSession(defaultDir: String) {
        _menuState.value = SessionMenuState.NewSession(defaultDir = defaultDir)
    }

    fun requestRename(old: String) {
        _menuState.value = SessionMenuState.Rename(old)
    }

    fun requestKill(name: String) {
        _menuState.value = SessionMenuState.KillConfirm(name)
    }

    fun confirmNewSession(name: String, dir: String, startClaude: Boolean) {
        val state = _menuState.value as? SessionMenuState.NewSession ?: return
        runGuarded(state, { it.create(name, dir, startClaude) }) {}
    }

    /** Re-selects [new] (re-attaching the terminal) if the renamed session was the selected one. */
    fun confirmRename(new: String) {
        val state = _menuState.value as? SessionMenuState.Rename ?: return
        runGuarded(state, { it.rename(state.old, new) }) {
            if (selected() == state.old) select(new)
        }
    }

    /** Clears the selection if the killed session was the selected one; [DeckScreen]'s own
     * "auto-select the first session" effect then picks whatever remains, if anything. */
    fun confirmKill() {
        val state = _menuState.value as? SessionMenuState.KillConfirm ?: return
        runGuarded(state, { it.kill(state.name) }) {
            if (selected() == state.name) clearSelection()
        }
    }

    private fun runGuarded(
        state: SessionMenuState,
        action: suspend (SessionActions) -> Result<Unit>,
        onSuccess: () -> Unit,
    ) {
        if (sessionActionJob?.isActive == true) return
        _actionInFlight.value = true
        sessionActionJob = scope.launch {
            try {
                perform(state, action, onSuccess)
            } finally {
                _actionInFlight.value = false
            }
        }
    }

    /**
     * Runs one session-management action, never crashing on a dropped connection: success kicks
     * the poll loop (now effective immediately, since [com.chuatzeyee.deck.poll.HostLink]'s
     * `chosen` supplier re-reads [repo] on every tick - fix round 1, important) and refreshes
     * [onHostUpdated] with the repository's fresh host before running [onSuccess] and closing the
     * menu; failure keeps the dialog open with the error message attached, so the user can correct
     * and retry.
     *
     * Error mapping (fix round 1, minor): no live [currentSessionActions], or the polling `Exec`
     * itself reporting it has no live connection (`IllegalStateException("Not connected")` - see
     * [DeckController.restartLink]'s `pollingExec`), is a genuine connection problem and says so;
     * anything else (a tmux failure already comes back as [Result.failure] from [action], not a
     * thrown exception, and is shown as-is - so this only catches the unexpected: a repository I/O
     * error, etc.) gets the more honest "Couldn't save the change." instead of blaming the
     * connection for it.
     */
    private suspend fun perform(
        state: SessionMenuState,
        action: suspend (SessionActions) -> Result<Unit>,
        onSuccess: () -> Unit,
    ) {
        val actions = currentSessionActions()
        val result = if (actions == null) {
            Result.failure(IOException("Not connected."))
        } else {
            try {
                action(actions)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                Log.w("Deck", "Session action failed: not connected", e)
                Result.failure(IOException("Not connected."))
            } catch (e: Exception) {
                Log.w("Deck", "Session action failed", e)
                Result.failure(IOException("Couldn't save the change."))
            }
        }
        result.fold(
            onSuccess = {
                kickHostLink()
                refreshActiveHost()
                onSuccess()
                _menuState.value = SessionMenuState.Closed
            },
            onFailure = { e -> _menuState.value = withError(state, e.message ?: "Failed.") },
        )
    }

    /** Re-reads the active host from [repo] and hands it to [onHostUpdated], so a caller's own
     * snapshot (e.g. [AppViewModel.activeHost]) picks up the sessions list a just-succeeded create/
     * rename/kill changed (fix round 1, important). */
    private suspend fun refreshActiveHost() {
        val id = currentHostId() ?: return
        val updated = repo.hosts.first().firstOrNull { it.id == id } ?: return
        onHostUpdated(updated)
    }

    private fun withError(state: SessionMenuState, message: String): SessionMenuState = when (state) {
        is SessionMenuState.NewSession -> state.copy(error = message)
        is SessionMenuState.Rename -> state.copy(error = message)
        is SessionMenuState.KillConfirm -> state.copy(error = message)
        SessionMenuState.Closed, is SessionMenuState.Open -> state
    }
}
