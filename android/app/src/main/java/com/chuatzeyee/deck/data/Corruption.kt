package com.chuatzeyee.deck.data

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences

/**
 * A DataStore corruption handler that reports the failure via [onCorrupt] and replaces the
 * unreadable file with empty preferences, so a damaged store file never crashes Deck on every
 * launch - the user re-adds hosts instead. Every `preferencesDataStore` delegate passes one.
 */
fun resetOnCorruption(onCorrupt: (Throwable) -> Unit): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { e ->
        onCorrupt(e)
        emptyPreferences()
    }
