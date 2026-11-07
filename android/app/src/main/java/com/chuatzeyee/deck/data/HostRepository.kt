package com.chuatzeyee.deck.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chuatzeyee.deck.core.Host
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val HOSTS_KEY = stringPreferencesKey("hosts")
private val LAST_HOST_KEY = stringPreferencesKey("last_host_id")
private val HOST_LIST_SERIALIZER = ListSerializer(Host.serializer())

// ignoreUnknownKeys: a field a later Deck version added to the stored JSON (or a field this
// version doesn't know about yet) must not make older/other versions unable to read the file.
private val JSON = Json { ignoreUnknownKeys = true }

/**
 * Persists the user's configured hosts as a single JSON array under one DataStore key.
 *
 * @param onCorrupt called (with the parse failure) whenever stored data can't be decoded as
 * hosts; the repository falls back to an empty list rather than ever throwing from [hosts] or a
 * mutator, and the next successful write overwrites the corrupt data. Kept as a plain callback,
 * not a `Log.w` call, so this class stays Android-free; callers wire it to logging.
 */
class HostRepository(
    private val store: DataStore<Preferences>,
    private val onCorrupt: (Throwable) -> Unit = {},
) {

    val hosts: Flow<List<Host>> = store.data.map { prefs -> decode(prefs[HOSTS_KEY]) }

    /** The id of the host most recently made active - auto-start prefers it (see
     * [com.chuatzeyee.deck.core.pickAutoStartHost]). */
    val lastHostId: Flow<String?> = store.data.map { prefs -> prefs[LAST_HOST_KEY] }

    suspend fun setLastHostId(id: String?) {
        store.edit { prefs -> if (id == null) prefs.remove(LAST_HOST_KEY) else prefs[LAST_HOST_KEY] = id }
    }

    /** Inserts [host], or replaces the existing host with the same id in place, preserving order. */
    suspend fun upsert(host: Host) {
        updateHosts { current ->
            val index = current.indexOfFirst { it.id == host.id }
            if (index >= 0) current.toMutableList().apply { this[index] = host } else current + host
        }
    }

    /** Removes host [id]; if it was the [lastHostId], that is forgotten too, in the same edit. */
    suspend fun remove(id: String) {
        store.edit { prefs ->
            prefs[HOSTS_KEY] = encode(decode(prefs[HOSTS_KEY]).filterNot { it.id == id })
            if (prefs[LAST_HOST_KEY] == id) prefs.remove(LAST_HOST_KEY)
        }
    }

    /** Forgets [id]'s pinned host key, so its next connection goes through first-use trust again
     * ("Re-trust" after an identity change). No-op if [id] doesn't match any stored host. */
    suspend fun unpin(id: String) {
        updateHosts { current -> current.map { if (it.id == id) it.copy(pinnedHostKey = null) else it } }
    }

    /** No-op if [id] doesn't match any stored host. */
    suspend fun setSessions(id: String, sessions: List<String>) {
        updateHosts { current -> current.map { if (it.id == id) it.copy(sessions = sessions) else it } }
    }

    /** No-op if [id] doesn't match any stored host. */
    suspend fun pin(id: String, fingerprint: String) {
        updateHosts { current -> current.map { if (it.id == id) it.copy(pinnedHostKey = fingerprint) else it } }
    }

    private suspend fun updateHosts(transform: (List<Host>) -> List<Host>) {
        store.edit { prefs -> prefs[HOSTS_KEY] = encode(transform(decode(prefs[HOSTS_KEY]))) }
    }

    private fun decode(raw: String?): List<Host> {
        if (raw == null) return emptyList()
        return try {
            JSON.decodeFromString(HOST_LIST_SERIALIZER, raw)
        } catch (e: SerializationException) {
            onCorrupt(e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            onCorrupt(e)
            emptyList()
        }
    }

    private fun encode(hosts: List<Host>): String = JSON.encodeToString(HOST_LIST_SERIALIZER, hosts)
}
