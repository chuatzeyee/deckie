package com.chuatzeyee.deck.ui

import android.util.Log
import com.chuatzeyee.deck.ssh.KeyVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The host-setup form's [Draft] and its key generate/import actions - split out of
 * [AppViewModel] so that file stays under the size limit. */
class DraftController(private val scope: CoroutineScope, private val keyVault: KeyVault) {

    private val _draft = MutableStateFlow(Draft())
    val draft: StateFlow<Draft> = _draft.asStateFlow()

    /** Starts a brand new host-setup form: fresh id, fresh keyId, no key chosen yet. */
    fun reset() {
        _draft.value = Draft()
    }

    /** Generates a fresh Ed25519 key for the current draft and publishes its public-key line via
     * [draft]. Guarded by the draft's own keyId, so a response for a draft the user has since
     * moved on from (a new host's form) is dropped instead of corrupting the new draft's state. */
    fun generateKey() {
        val keyId = draft.value.keyId
        scope.launch(Dispatchers.IO) {
            val result = try {
                KeyActionState.Generated(keyVault.generate(keyId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Deck", "Key generation failed for '$keyId'", e)
                KeyActionState.Failed("Could not generate a key. Try again.")
            }
            _draft.update { if (it.keyId == keyId) it.copy(keyAction = result) else it }
        }
    }

    /** Imports an existing Ed25519 private key for the current draft; see [generateKey]'s doc for
     * the stale-draft guard. [passphrase] is zeroed as soon as it's no longer needed. */
    fun importKey(privateKeyPem: String, passphrase: CharArray?) {
        val keyId = draft.value.keyId
        scope.launch(Dispatchers.IO) {
            val result = try {
                keyVault.import(keyId, privateKeyPem, passphrase)
                KeyActionState.Imported
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Deck", "Key import failed for '$keyId'", e)
                KeyActionState.Failed("Could not import that key. Check the format and passphrase.")
            } finally {
                passphrase?.fill('\u0000')
            }
            _draft.update { if (it.keyId == keyId) it.copy(keyAction = result) else it }
        }
    }
}
