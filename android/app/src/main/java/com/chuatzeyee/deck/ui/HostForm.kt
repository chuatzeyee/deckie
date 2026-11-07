package com.chuatzeyee.deck.ui

import com.chuatzeyee.deck.core.Host
import java.util.UUID

/**
 * Pure decision logic for the host-setup form, kept out of [AppViewModel]/[HostSetupScreen] so it
 * has ordinary JVM unit tests - Compose UI itself can't be tested in this environment (see the
 * Task 10 brief).
 */

/** The port field shows "22" only as a hint (not a stored default); a blank field means "use 22". */
fun effectivePort(port: String): String = port.ifBlank { "22" }

/**
 * Builds the [Host] to persist from the setup form's fields. Callers must have already checked
 * `validateHost(name, address, effectivePort(port), user)` returns no errors for the same fields.
 */
fun hostFromForm(
    id: String,
    keyId: String,
    name: String,
    address: String,
    port: String,
    user: String,
    pinnedHostKey: String?,
    sessions: List<String>,
): Host = Host(
    id = id,
    name = name.trim(),
    address = address.trim(),
    port = effectivePort(port).toInt(),
    user = user.trim(),
    keyId = keyId,
    pinnedHostKey = pinnedHostKey,
    sessions = sessions,
)

/** A fresh per-host key id: "host-" plus a dash-free UUID, matching [com.chuatzeyee.deck.ssh.KeyVault]'s `[A-Za-z0-9_-]{1,64}` rule. */
fun randomHostKeyId(): String = "host-" + UUID.randomUUID().toString().replace("-", "")

/** Text for the first-connection trust dialog; see the Task 10 brief for the exact wording. */
fun firstUseMessage(address: String, keyType: String, fingerprint: String): String =
    "First connection to $address. Server fingerprint ($keyType): $fingerprint. Trust it?"

/** Text for a changed/mismatched host key; shown instead of connecting, never auto-repinned. */
fun hostKeyMismatchMessage(expected: String, got: String): String =
    "Server identity changed! Expected $expected, got $got. Not connecting."

/** One `generateKey`/`importKey` attempt's outcome, for the "new key" step of host setup. */
sealed interface KeyActionState {
    data object Idle : KeyActionState
    data class Generated(val publicKeyLine: String) : KeyActionState
    data object Imported : KeyActionState
    data class Failed(val message: String) : KeyActionState
}

/**
 * The host-setup form's own id/keyId/key-provisioning state, owned by [AppViewModel] rather than a
 * Composable's `remember` - so it survives screen changes, and so a backgrounded
 * `generateKey`/`importKey` can never resolve into a *different*, later draft's [keyAction] (fix
 * round 1, critical: this is what made `keyVault.provider(host.keyId)` reachably crash with a
 * stale/mismatched keyId for a second host). [AppViewModel.resetDraft] starts a brand new one.
 */
data class Draft(
    val id: String = UUID.randomUUID().toString(),
    val keyId: String = randomHostKeyId(),
    val keyAction: KeyActionState = KeyActionState.Idle,
)
