package com.chuatzeyee.deck.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.chuatzeyee.deck.core.Host
import com.chuatzeyee.deck.data.HostRepository
import com.chuatzeyee.deck.ssh.Exec
import com.chuatzeyee.deck.ssh.ExecResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [SessionMenuController]'s own logic - kick-after-success and the selection follow-up on rename/
 * kill - independent of [DeckController], which only delegates to it (fix round 1: "if
 * DeckController is testable with fakes" - it isn't, [DeckController.restartLink] hardcodes
 * `SshConnection`/`TerminalConnection`, but the selection/kick logic itself lives entirely in
 * [SessionMenuController] and is testable here with the same [HostRepository] + fake [Exec]
 * pattern as [SessionActionsTest]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionMenuControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: HostRepository

    private val host = Host(id = "1", name = "vm", address = "1.2.3.4", user = "u", keyId = "k", sessions = listOf("a", "api", "b"))

    private class FakeExec : Exec {
        val commands = mutableListOf<String>()
        override suspend fun run(command: String, stdin: ByteArray?): ExecResult {
            commands.add(command)
            return ExecResult(0, "", "")
        }
    }

    private var selectedName: String? = null
    private var kicked = false
    private var updatedHost: Host? = null

    /** [scope] is the test's own [kotlinx.coroutines.test.TestScope] (passed as `this` from inside
     * `runTest`), never [dataStoreScope] (plain [Dispatchers.Unconfined]) - the double-tap guard
     * test below depends on `confirmKill()`'s launched coroutine not running any further than its
     * first line until [advanceUntilIdle] is called, which only [kotlinx.coroutines.test.TestScope]'s
     * dispatcher guarantees. */
    private fun controller(actions: SessionActions, scope: CoroutineScope): SessionMenuController =
        SessionMenuController(
            scope = scope,
            repo = repo,
            currentHostId = { host.id },
            currentSessionActions = { actions },
            kickHostLink = { kicked = true },
            onHostUpdated = { updatedHost = it },
            selected = { selectedName },
            select = { selectedName = it },
            clearSelection = { selectedName = null },
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

    @Test fun confirmRename_reselects_the_new_name_and_kicks_when_the_renamed_session_was_selected() = runTest {
        repo.upsert(host)
        val fake = FakeExec()
        val c = controller(SessionActions(fake, repo, host.id), this)
        selectedName = "api"

        c.requestRename("api")
        c.confirmRename("api2")
        advanceUntilIdle()

        assertEquals("api2", selectedName)
        assertEquals(true, kicked)
        assertEquals(listOf("a", "api2", "b"), updatedHost?.sessions)
        assertEquals(SessionMenuState.Closed, c.menuState.value)
    }

    @Test fun confirmRename_leaves_selection_alone_when_a_different_session_was_renamed() = runTest {
        repo.upsert(host)
        val fake = FakeExec()
        val c = controller(SessionActions(fake, repo, host.id), this)
        selectedName = "a"

        c.requestRename("api")
        c.confirmRename("api2")
        advanceUntilIdle()

        assertEquals("a", selectedName)
    }

    @Test fun confirmKill_clears_selection_when_the_killed_session_was_selected() = runTest {
        repo.upsert(host)
        val fake = FakeExec()
        val c = controller(SessionActions(fake, repo, host.id), this)
        selectedName = "api"

        c.requestKill("api")
        c.confirmKill()
        advanceUntilIdle()

        assertNull(selectedName)
        assertEquals(true, kicked)
    }

    @Test fun a_second_confirm_while_the_first_is_still_running_is_ignored() = runTest {
        repo.upsert(host)
        val fake = FakeExec()
        val c = controller(SessionActions(fake, repo, host.id), this)

        c.requestKill("api")
        c.confirmKill()
        c.confirmKill() // double tap before the first action's coroutine has run at all
        advanceUntilIdle()

        assertEquals(1, fake.commands.size)
    }

    @Test fun failure_keeps_the_dialog_open_with_the_error_and_never_kicks() = runTest {
        repo.upsert(host)
        val fake = FakeExec()
        val c = controller(SessionActions(fake, repo, host.id), this)

        c.requestRename("api")
        c.confirmRename("bad.name") // rejected by Tmux.isValidNewName before anything runs
        advanceUntilIdle()

        val state = c.menuState.value as SessionMenuState.Rename
        assertEquals("api", state.old)
        assertNotNull(state.error)
        assertEquals(false, kicked)
        assertTrue(fake.commands.isEmpty())
    }
}
