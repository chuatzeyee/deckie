package com.chuatzeyee.deck.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CorruptionTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun a_corrupt_store_file_is_reset_to_empty_and_reported() = runTest {
        val file = tmp.newFile("x.preferences_pb").apply { writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x04)) }
        val reported = mutableListOf<Throwable>()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = resetOnCorruption { reported += it },
                scope = scope,
            ) { file }
            assertTrue(store.data.first().asMap().isEmpty())
            assertEquals(1, reported.size)
            // The reset store is usable again.
            val key = stringPreferencesKey("k")
            store.edit { it[key] = "v" }
            assertEquals("v", store.data.first()[key])
        } finally {
            scope.cancel()
        }
    }
}
