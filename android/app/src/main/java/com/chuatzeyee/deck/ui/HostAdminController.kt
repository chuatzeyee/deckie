package com.chuatzeyee.deck.ui

import android.util.Log
import com.chuatzeyee.deck.core.Host
import com.chuatzeyee.deck.data.HostRepository
import com.chuatzeyee.deck.poll.LinkState
import com.chuatzeyee.deck.ssh.KeyVault
import com.chuatzeyee.deck.watch.DeckRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A saved host's server presenting a different host key than the pinned one. */
data class IdentityChange(val expected: String, val got: String, val keyType: String)

/** The host-list confirmation dialog currently showing, if any. */
sealed interface HostDialog {
    data class ConfirmDelete(val host: Host) : HostDialog
    data class ConfirmRetrust(val host: Host, val change: IdentityChange) : HostDialog
}

/**
 * Deleting a saved host and re-trusting one whose host key changed (B5) - split out of
 * [AppViewModel] to keep that file under the size limit. Both act only after the user confirms a
 * [HostDialog]. [identityChanges] collects every host seen presenting a different key, from either
 * a one-shot connection ([recordIdentityChange]) or the live link's [LinkState.IdentityChanged].
 */
class HostAdminController(
    private val scope: CoroutineScope,
    private val repo: HostRepository,
    private val keyVault: KeyVault,
    private val runtime: DeckRuntime,
    /** Called once host [String] is gone, so the owner can drop its own per-host state. */
    private val onDeleted: (String) -> Unit,
    /** Reconnects [Host] (already unpinned) through the normal first-use trust flow. */
    private val reconnect: (Host) -> Unit,
) {
    private val _dialog = MutableStateFlow<HostDialog?>(null)
    val dialog: StateFlow<HostDialog?> = _dialog.asStateFlow()

    private val _identityChanges = MutableStateFlow<Map<String, IdentityChange>>(emptyMap())
    val identityChanges: StateFlow<Map<String, IdentityChange>> = _identityChanges.asStateFlow()

    init {
        scope.launch {
            runtime.link.collect { state ->
                val id = runtime.activeHostId
                if (state is LinkState.IdentityChanged && id != null) {
                    recordIdentityChange(id, IdentityChange(state.expected, state.got, state.keyType))
                }
            }
        }
    }

    fun recordIdentityChange(hostId: String, change: IdentityChange) {
        _identityChanges.update { it + (hostId to change) }
    }

    fun requestDelete(host: Host) {
        _dialog.value = HostDialog.ConfirmDelete(host)
    }

    /** No-op unless [host] has a recorded [IdentityChange] - the dialog must show the new key. */
    fun requestRetrust(host: Host) {
        val change = identityChanges.value[host.id] ?: return
        _dialog.value = HostDialog.ConfirmRetrust(host, change)
    }

    fun dismiss() {
        _dialog.value = null
    }

    /** Removes the host, its link and - unless another saved host shares it - its key file. */
    fun confirmDelete() {
        val host = (dialog.value as? HostDialog.ConfirmDelete)?.host ?: return
        _dialog.value = null
        scope.launch {
            runtime.forgetHost(host.id)
            repo.remove(host.id)
            _identityChanges.update { it - host.id }
            onDeleted(host.id)
            if (repo.hosts.first().none { it.keyId == host.keyId }) deleteKey(host.keyId)
        }
    }

    /** Forgets the pinned key, then reconnects so the first-use dialog asks to trust the new one. */
    fun confirmRetrust() {
        val host = (dialog.value as? HostDialog.ConfirmRetrust)?.host ?: return
        _dialog.value = null
        scope.launch {
            repo.unpin(host.id)
            _identityChanges.update { it - host.id }
            reconnect(host.copy(pinnedHostKey = null))
        }
    }

    private suspend fun deleteKey(keyId: String) {
        try {
            withContext(Dispatchers.IO) { keyVault.delete(keyId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Deck", "Could not delete key '$keyId'", e)
        }
    }
}

/** The delete confirmation's text, exactly as the user sees it. */
fun deleteHostMessage(name: String): String = "Delete $name? Its key will be removed from this phone."

/** The re-trust confirmation's text: names the new key in full before anything is unpinned. */
fun retrustMessage(name: String, change: IdentityChange): String =
    "Re-trust $name?\n\nIt now presents ${change.keyType} key ${change.got} (Deck had ${change.expected}).\n\n" +
        "Only continue if you know why it changed, e.g. the server was reinstalled. " +
        "Deck will reconnect and ask you to confirm the new key once more."
