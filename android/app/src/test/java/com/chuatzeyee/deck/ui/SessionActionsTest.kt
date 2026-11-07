package com.chuatzeyee.deck.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.chuatzeyee.deck.core.Host
import com.chuatzeyee.deck.core.Tmux
import com.chuatzeyee.deck.data.HostRepository
import com.chuatzeyee.deck.ssh.Exec
import com.chuatzeyee.deck.ssh.ExecResult
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

/** [FakeExec] + a real temp-file-backed [HostRepository] (no new fakes/interfaces - ruling R2),
 * exactly [com.chuatzeyee.deck.data.HostRepositoryTest]'s and [com.chuatzeyee.deck.core.SenderTest]'s
 * own setups. */
class SessionActionsTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: HostRepository

    private val host = Host(
        id = "1",
        name = "vm",
        address = "1.2.3.4",
        user = "u",
        keyId = "k",
        sessions = listOf("a", "api", "b"),
    )

    private class FakeExec : Exec {
        val commands = mutableListOf<String>()
        var exit = 0
        var stderr = ""

        override suspend fun run(command: String, stdin: ByteArray?): ExecResult {
            commands.add(command)
            return ExecResult(exit, "", stderr)
        }
    }

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

    @Test fun create_runs_new_session_command_and_appends_to_chosen() = runTest {
        repo.upsert(host)
        val fake = FakeExec()
        val actions = SessionActions(fake, repo, host.id)

        val result = actions.create("api2", "/home/u/api2", startClaude = true)

        assertTrue(result.isSuccess)
        assertEquals(listOf(Tmux.newSession("api2", "/home/u/api2", true)), fake.commands)
        assertEquals(listOf("a", "api", "b", "api2"), repo.hosts.first().single().sessions)
    }

    @Test fun create_rejects_invalid_name_without_running_anything() = runTest {
        repo.upsert(host)
        val fake = FakeExec()
        val actions = SessionActions(fake, repo, host.id)

        val result = actions.create("a.b", "/home/u", startClaude = false)

        assertTrue(result.isFailure)
        assertTrue(fake.commands.isEmpty())
        assertEquals(listOf("a", "api", "b"), repo.hosts.first().single().sessions)
    }

    @Test fun create_surfaces_tmux_stderr_on_failure() = runTest {
        repo.upsert(host)
        val fake = FakeExec().apply { exit = 1; stderr = "duplicate session: api" }
        val actions = SessionActions(fake, repo, host.id)

        val result = actions.create("api", "/home/u", startClaude = false)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("duplicate session"))
        assertEquals(listOf("a", "api", "b"), repo.hosts.first().single().sessions)
    }

    @Test fun rename_keeps_position() = runTest {
        repo.upsert(host)
        val fake = FakeExec()
        val actions = SessionActions(fake, repo, host.id)

        val result = actions.rename("api", "api2")

        assertTrue(result.isSuccess)
        assertEquals(listOf(Tmux.rename("api", "api2")), fake.commands)
        assertEquals(listOf("a", "api2", "b"), repo.hosts.first().single().sessions)
    }

    @Test fun rename_rejects_invalid_new_name_without_running_anything() = runTest {
        repo.upsert(host)
        val fake = FakeExec()
        val actions = SessionActions(fake, repo, host.id)

        val result = actions.rename("api", "a.b")

        assertTrue(result.isFailure)
        assertTrue(fake.commands.isEmpty())
        assertEquals(listOf("a", "api", "b"), repo.hosts.first().single().sessions)
    }

    @Test fun rename_to_a_duplicate_name_fails_with_tmux_stderr() = runTest {
        repo.upsert(host)
        val fake = FakeExec().apply { exit = 1; stderr = "duplicate session: b" }
        val actions = SessionActions(fake, repo, host.id)

        val result = actions.rename("api", "b")

        assertTrue(result.isFailure)
        assertEquals("duplicate session: b", result.exceptionOrNull()?.message)
        assertEquals(listOf("a", "api", "b"), repo.hosts.first().single().sessions)
    }

    @Test fun stderr_with_surrounding_whitespace_is_trimmed() = runTest {
        repo.upsert(host)
        val fake = FakeExec().apply { exit = 1; stderr = "  duplicate session: api  \n" }
        val actions = SessionActions(fake, repo, host.id)

        val result = actions.create("api", "/home/u", startClaude = false)

        assertTrue(result.isFailure)
        assertEquals("duplicate session: api", result.exceptionOrNull()?.message)
    }

    @Test fun kill_removes_from_chosen() = runTest {
        repo.upsert(host)
        val fake = FakeExec()
        val actions = SessionActions(fake, repo, host.id)

        val result = actions.kill("api")

        assertTrue(result.isSuccess)
        assertEquals(listOf(Tmux.kill("api")), fake.commands)
        assertEquals(listOf("a", "b"), repo.hosts.first().single().sessions)
    }

    @Test fun kill_surfaces_tmux_stderr_on_failure() = runTest {
        repo.upsert(host)
        val fake = FakeExec().apply { exit = 1; stderr = "can't find session: api" }
        val actions = SessionActions(fake, repo, host.id)

        val result = actions.kill("api")

        assertTrue(result.isFailure)
        assertEquals("can't find session: api", result.exceptionOrNull()?.message)
        assertEquals(listOf("a", "api", "b"), repo.hosts.first().single().sessions)
    }
}
