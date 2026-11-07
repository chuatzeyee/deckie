package com.chuatzeyee.deck.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chuatzeyee.deck.core.Host
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HostRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Same key name [HostRepository] stores hosts under; used here to inject raw stored data. */
    private val rawHostsKey = stringPreferencesKey("hosts")

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: HostRepository

    private val h = Host(
        id = "1",
        name = "vm",
        address = "203.0.113.10",
        port = 22,
        user = "deck",
        keyId = "key-1",
    )

    @Before
    fun setUp() {
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + Job())
        store = PreferenceDataStoreFactory.create(scope = dataStoreScope) { tmp.newFile("t.preferences_pb") }
        repo = HostRepository(store)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test fun starts_empty() = runTest { assertTrue(repo.hosts.first().isEmpty()) }

    @Test fun upsert_then_read() = runTest {
        repo.upsert(h)
        assertEquals(listOf(h), repo.hosts.first())
    }

    @Test fun upsert_replaces_existing_id_keeping_order() = runTest {
        val h2 = h.copy(id = "2", name = "vm2")
        repo.upsert(h)
        repo.upsert(h2)
        val renamed = h.copy(name = "renamed")
        repo.upsert(renamed)
        assertEquals(listOf(renamed, h2), repo.hosts.first())
    }

    @Test fun set_sessions_keeps_order() = runTest {
        repo.upsert(h)
        repo.setSessions(h.id, listOf("api", "web"))
        assertEquals(listOf("api", "web"), repo.hosts.first().single().sessions)
    }

    @Test fun set_sessions_on_unknown_id_is_a_noop() = runTest {
        repo.upsert(h)
        repo.setSessions("missing", listOf("api"))
        assertEquals(listOf(h), repo.hosts.first())
    }

    @Test fun pin_sets_host_key() = runTest {
        repo.upsert(h)
        repo.pin(h.id, "SHA256:abc123")
        assertEquals("SHA256:abc123", repo.hosts.first().single().pinnedHostKey)
    }

    @Test fun pin_on_unknown_id_is_a_noop() = runTest {
        repo.upsert(h)
        repo.pin("missing", "SHA256:abc123")
        assertEquals(listOf(h), repo.hosts.first())
    }

    @Test fun remove() = runTest {
        repo.upsert(h)
        repo.remove(h.id)
        assertTrue(repo.hosts.first().isEmpty())
    }

    @Test fun corrupt_stored_data_recovers_to_empty_list_and_reports_it() = runTest {
        store.edit { it[rawHostsKey] = "not json" }
        val corruptions = mutableListOf<Throwable>()
        val recoveringRepo = HostRepository(store, onCorrupt = { corruptions.add(it) })

        assertTrue(recoveringRepo.hosts.first().isEmpty())
        assertEquals(1, corruptions.size)

        recoveringRepo.upsert(h)
        assertEquals(listOf(h), recoveringRepo.hosts.first())
    }

    @Test fun stored_json_with_unknown_field_still_decodes() = runTest {
        val rawJson = "[{\"id\":\"1\",\"name\":\"vm\",\"address\":\"203.0.113.10\"," +
            "\"port\":22,\"user\":\"deck\",\"keyId\":\"key-1\",\"sessions\":[]," +
            "\"futureField\":\"ignored\"}]"
        store.edit { it[rawHostsKey] = rawJson }

        assertEquals(listOf(h), repo.hosts.first())
    }

    @Test fun unpin_clears_the_host_key_only() = runTest {
        repo.upsert(h.copy(pinnedHostKey = "SHA256:abc", sessions = listOf("api")))
        repo.unpin(h.id)
        assertEquals(listOf(h.copy(sessions = listOf("api"))), repo.hosts.first())
    }

    @Test fun last_host_id_round_trips_and_clears() = runTest {
        assertEquals(null, repo.lastHostId.first())
        repo.setLastHostId("1")
        assertEquals("1", repo.lastHostId.first())
        repo.setLastHostId(null)
        assertEquals(null, repo.lastHostId.first())
    }

    @Test fun removing_the_last_host_forgets_it_as_last() = runTest {
        repo.upsert(h)
        repo.setLastHostId(h.id)
        repo.remove(h.id)
        assertEquals(null, repo.lastHostId.first())
    }
}
