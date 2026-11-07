package com.chuatzeyee.deck.poll

import com.chuatzeyee.deck.core.Observation
import com.chuatzeyee.deck.core.Question
import com.chuatzeyee.deck.core.QuestionParser
import com.chuatzeyee.deck.core.Status
import com.chuatzeyee.deck.core.StatusChange
import com.chuatzeyee.deck.core.Tmux
import com.chuatzeyee.deck.core.TmuxSession
import com.chuatzeyee.deck.ssh.Exec
import java.io.IOException

/** One tmux session's current rail state for [hostId], as of the poll that produced it. */
data class SessionView(
    val hostId: String,
    val name: String,
    val status: Status,
    val question: Question?,
    val path: String,
)

/**
 * One poll tick's outcome: [views] are the chosen sessions still alive on the host, sorted for
 * the rail; [notAdded] are live sessions the user hasn't chosen to show; [alerts] are the views
 * among [views] that should notify the user right now; [noServer] is true when tmux has no
 * server running at all on the host - an expected empty state, not a failure.
 */
data class PollResult(
    val views: List<SessionView>,
    val notAdded: List<String>,
    val alerts: List<SessionView>,
    val noServer: Boolean,
)

/**
 * Polls one host's tmux sessions once per [tick].
 *
 * Every tick runs a single combined command, `date +%s && `[Tmux.LIST]: its first stdout line is
 * the host's own clock (epoch seconds), the rest is [Tmux.LIST]'s own output. The remote clock
 * feeds every [Observation] this tick produces, because [Observation.now] and
 * [TmuxSession.activity] must come from the same clock (see [StatusTracker]) - a poller using the
 * phone's own clock would drift from tmux's server-side activity timestamps, especially across a
 * reconnect after the phone slept.
 *
 * Only sessions named in `chosen` are captured (one [Tmux.capture] exec each) and observed via
 * [tracker]; a chosen session that no longer exists on the host is dropped silently (no capture),
 * and a capture failure for one session never fails the whole tick - it is just treated as no
 * question showing for that session. `views` is sorted by [Status] order (the rail's display
 * order) and then by each session's position in `chosen`.
 *
 * Any other failure - a non-numeric timestamp line (defensive: the combined command's output is
 * otherwise unparseable), or a nonzero tmux exit that isn't [Tmux.isNoServer] - throws
 * [IOException] rather than returning a result, since [PollResult] has no failure state of its
 * own; [HostLink] is what turns that exception into `Down`.
 */
class Poller(
    private val hostId: String,
    private val exec: Exec,
    private val tracker: SyncTracker,
) {
    suspend fun tick(chosen: List<String>): PollResult {
        val result = exec.run("date +%s && " + Tmux.LIST, null)
        val newline = result.stdout.indexOf('\n')
        val nowLine = if (newline >= 0) result.stdout.substring(0, newline) else result.stdout
        val now = nowLine.trim().toLongOrNull()
            ?: throw IOException("poll tick: not a timestamp line: ${nowLine.take(80)}")

        if (Tmux.isNoServer(result.exit, result.stderr)) {
            tracker.forget(emptySet())
            return PollResult(emptyList(), emptyList(), emptyList(), noServer = true)
        }
        if (result.exit != 0) {
            throw IOException("poll tick: list-sessions failed (exit ${result.exit}): ${result.stderr}")
        }

        val listOutput = if (newline >= 0) result.stdout.substring(newline + 1) else ""
        val sessions = Tmux.parseList(listOutput)
        val byName = sessions.associateBy { it.name }
        val chosenNames = chosen.distinct()
        val chosenIndex = chosenNames.withIndex().associate { (i, name) -> name to i }
        val notAdded = sessions.map { it.name }.filter { it !in chosenIndex }

        val observed = chosenNames.mapNotNull { name ->
            val session = byName[name] ?: return@mapNotNull null
            val question = captureQuestion(name)
            Observed(name, session, tracker.observe(Observation(session, now, question)))
        }.sortedWith(compareBy({ it.change.status.ordinal }, { chosenIndex.getValue(it.name) }))

        val viewed = observed.map { it.toView(hostId) to it.change.notify }
        val views = viewed.map { it.first }
        val alerts = viewed.filter { it.second }.map { it.first }

        tracker.forget(byName.keys)
        return PollResult(views, notAdded, alerts, noServer = false)
    }

    private suspend fun captureQuestion(name: String): Question? {
        val capture = exec.run(Tmux.capture(name), null)
        if (capture.exit != 0) return null
        return QuestionParser.parse(name, capture.stdout)
    }

    /** One chosen, still-live session's observation, pre-sort. */
    private data class Observed(val name: String, val session: TmuxSession, val change: StatusChange) {
        fun toView(hostId: String): SessionView = SessionView(hostId, name, change.status, change.question, session.path)
    }
}
