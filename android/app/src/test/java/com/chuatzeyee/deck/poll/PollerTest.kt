package com.chuatzeyee.deck.poll

import com.chuatzeyee.deck.core.Status
import com.chuatzeyee.deck.core.StatusTracker
import com.chuatzeyee.deck.core.Tmux
import com.chuatzeyee.deck.ssh.Exec
import com.chuatzeyee.deck.ssh.ExecResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollerTest {

    private val unitSep = "\u001f"
    private val now = 1_700_000_000L

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("screens/$name")) {
            "missing fixture screens/$name"
        }.bufferedReader(Charsets.UTF_8).readText()

    private val permission = fixture("permission.txt")

    /** One [Tmux.LIST] row in its exact unit-separator field order. */
    private fun sessionLine(name: String, activity: Long = now - 100, path: String = "/tmp", cmd: String = "claude"): String =
        listOf(name, activity.toString(), "80", "24", path, cmd).joinToString(unitSep)

    /**
     * Answers the combined `date +%s && ` + [Tmux.LIST] command from [listBody]/[listExit]/
     * [listStderr], and `capture-pane` calls from [screens] (per session name, default empty) /
     * [captureExit] (default success). Records every command issued.
     */
    private class FakeExec : Exec {
        val commands = mutableListOf<String>()
        var now: Long = 1_700_000_000L
        var listExit: Int = 0
        var listStderr: String = ""
        var listBody: String = ""
        var rawListFirstLine: String? = null // overrides the timestamp line verbatim, for defensive-parse tests
        val screens = mutableMapOf<String, String>()
        val captureExit = mutableMapOf<String, Int>()

        override suspend fun run(command: String, stdin: ByteArray?): ExecResult {
            commands.add(command)
            if (command == "date +%s && " + Tmux.LIST) {
                val firstLine = rawListFirstLine ?: now.toString()
                return ExecResult(listExit, "$firstLine\n$listBody", listStderr)
            }
            val captured = CAPTURE_TARGET.find(command)?.groupValues?.get(1)
            if (captured != null) {
                return ExecResult(captureExit[captured] ?: 0, screens[captured] ?: "", "")
            }
            return ExecResult(0, "", "")
        }

        fun captureCommands(): List<String> = commands.filter { "capture-pane" in it }

        private companion object {
            val CAPTURE_TARGET = Regex("capture-pane .*-t '=([^:]+):'")
        }
    }

    private fun poller(exec: FakeExec, tracker: StatusTracker = StatusTracker()) =
        Poller("host1", exec, SyncTracker(tracker))

    // --- the brief's required scenarios -----------------------------------------------------

    @Test fun only_chosen_sessions_are_captured_and_sorted() = runTest {
        val fake = FakeExec().apply {
            listBody = listOf(sessionLine("api"), sessionLine("web"), sessionLine("docs")).joinToString("\n")
            screens["api"] = permission
        }

        val result = poller(fake).tick(listOf("docs", "api"))

        assertEquals(listOf("api", "docs"), result.views.map { it.name }) // Waiting before Idle
        assertEquals(Status.Waiting, result.views[0].status)
        assertEquals(Status.Idle, result.views[1].status)
        assertEquals(listOf("web"), result.notAdded)
        assertEquals(setOf(Tmux.capture("api"), Tmux.capture("docs")), fake.captureCommands().toSet())
        assertEquals(2, fake.captureCommands().size) // never captured web
        assertFalse(result.noServer)
    }

    @Test fun no_server_gives_empty_rail() = runTest {
        val fake = FakeExec().apply {
            listExit = 1
            listStderr = "no server running on /tmp/tmux-0/default"
        }

        val result = poller(fake).tick(listOf("api"))

        assertEquals(emptyList<SessionView>(), result.views)
        assertEquals(emptyList<String>(), result.notAdded)
        assertEquals(emptyList<SessionView>(), result.alerts)
        assertTrue(result.noServer)
    }

    @Test fun vanished_chosen_session_is_dropped_silently() = runTest {
        val fake = FakeExec().apply { listBody = sessionLine("api") }

        val result = poller(fake).tick(listOf("api", "gone"))

        assertEquals(listOf("api"), result.views.map { it.name })
        assertEquals(listOf(Tmux.capture("api")), fake.captureCommands())
    }

    @Test fun renamed_session_shows_as_not_added() = runTest {
        val fake = FakeExec().apply { listBody = sessionLine("api2") }

        val result = poller(fake).tick(listOf("api"))

        assertEquals(emptyList<SessionView>(), result.views)
        assertEquals(listOf("api2"), result.notAdded)
        assertTrue(fake.captureCommands().isEmpty())
    }

    @Test fun alerts_carry_new_waiting() = runTest {
        val tracker = StatusTracker()
        val fake = FakeExec().apply {
            listBody = sessionLine("api")
            screens["api"] = permission
        }
        val p = poller(fake, tracker)

        val first = p.tick(listOf("api"))
        assertEquals(listOf("api"), first.alerts.map { it.name })

        val second = p.tick(listOf("api"))
        assertEquals(emptyList<SessionView>(), second.alerts)
    }

    // --- additional edge cases from the controller rulings ------------------------------------

    @Test fun capture_failure_for_one_session_does_not_fail_the_tick() = runTest {
        val fake = FakeExec().apply {
            listBody = listOf(sessionLine("api"), sessionLine("docs")).joinToString("\n")
            captureExit["api"] = 1 // e.g. session gone right between list and capture
        }

        val result = poller(fake).tick(listOf("api", "docs"))

        assertEquals(setOf("api", "docs"), result.views.map { it.name }.toSet())
        assertEquals(null, result.views.first { it.name == "api" }.question)
        assertEquals(Status.Idle, result.views.first { it.name == "api" }.status)
    }

    @Test fun non_numeric_timestamp_throws() = runTest {
        val fake = FakeExec().apply { rawListFirstLine = "not-a-number" }

        var threw = false
        try {
            poller(fake).tick(listOf("api"))
        } catch (e: java.io.IOException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test fun real_list_failure_other_than_no_server_throws() = runTest {
        val fake = FakeExec().apply {
            listExit = 1
            listStderr = "unexpected tmux error"
        }

        var threw = false
        try {
            poller(fake).tick(listOf("api"))
        } catch (e: java.io.IOException) {
            threw = true
        }
        assertTrue(threw)
    }
}
