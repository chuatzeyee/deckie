package com.chuatzeyee.deck.watch

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.chuatzeyee.deck.data.resetOnCorruption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.watchDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "deck_watch",
    corruptionHandler = resetOnCorruption { Log.w("Deck", "Watch settings unreadable - reset to defaults", it) },
)
private val WATCH_ENABLED_KEY = booleanPreferencesKey("watch_enabled")
private val PERMISSIONS_ASKED_KEY = booleanPreferencesKey("permissions_asked")

/**
 * Persists the "Watch in background" toggle (ruling R15/brief Task 13). Default ON: there is
 * nothing to watch in background before the user has saved a host, so treating an absent stored
 * value as "on" is equivalent in practice to only defaulting it on once a host exists, without
 * [HostRepository] having to know anything about this feature.
 *
 * Also persists whether Deck has already asked once for `POST_NOTIFICATIONS`/the battery
 * exemption on the strength of the toggle defaulting on (fix round 1, R15 default path): since
 * the user never explicitly flipped anything, `MainActivity` asks exactly once per install - see
 * [permissionsAsked]/[setPermissionsAsked] - rather than on every app open. Flipping the toggle
 * itself (off then on) still always asks again, independent of this flag.
 */
class WatchPrefs(context: Context) {
    private val store = context.watchDataStore

    val enabled: Flow<Boolean> = store.data.map { prefs -> prefs[WATCH_ENABLED_KEY] ?: true }

    suspend fun setEnabled(value: Boolean) {
        store.edit { prefs -> prefs[WATCH_ENABLED_KEY] = value }
    }

    val permissionsAsked: Flow<Boolean> = store.data.map { prefs -> prefs[PERMISSIONS_ASKED_KEY] ?: false }

    suspend fun setPermissionsAsked(value: Boolean) {
        store.edit { prefs -> prefs[PERMISSIONS_ASKED_KEY] = value }
    }
}
