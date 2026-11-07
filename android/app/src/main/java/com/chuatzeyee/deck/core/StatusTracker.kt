package com.chuatzeyee.deck.core

/** A session's status, in the rail's display order (most urgent first). */
enum class Status { Waiting, Finished, Working, Idle, Off }

/** One poll's raw inputs for a session: its tmux state, when it was polled, and any question found on screen. */
data class Observation(val session: TmuxSession, val now: Long, val question: Question?)

/** The status [StatusTracker.observe] derived for a session, whether to notify, and the question behind it, if any. */
data class StatusChange(val status: Status, val notify: Boolean, val question: Question?)

/**
 * Derives each session's [Status] from successive [Observation]s. Per-session state (last status,
 * last notified question fingerprint, and the redraw filter tracked in [Redraw]) lives in one
 * private immutable map replaced wholesale on every call - never mutated in place. Not
 * thread-safe: callers must serialize calls to [observe], [opened], [focus] and [forget] (e.g.
 * from a single poll loop). [Observation.now] and [TmuxSession.activity] must come from the same
 * clock - the poller uses the remote host's clock for both.
 *
 * Status rules (spec "Waiting/finished state and notifications", amended for sparse polling -
 * background polling, backoff and doze mean two polls of the same session can be tens of seconds
 * apart, so a burst of work can start and finish entirely between polls), applied in order:
 * 1. [TmuxSession.command] isn't `claude` (or `node`, which `claude` may show as) -> [Status.Off];
 *    returned immediately without reading or updating any tracked state for the session.
 * 2. Effective activity ignores a resize's redraw burst - see [Redraw].
 * 3. A question on screen -> [Status.Waiting]; notifies when its fingerprint is new. Leaving
 *    [Status.Waiting] for [Status.Working] or [Status.Finished] (rules 4-5) forgets the notified
 *    fingerprint, so an identical question reappearing after more work notifies again.
 * 4. Effective activity within [workingWindow] seconds of now -> [Status.Working].
 * 5. The previous status was [Status.Working], OR effective activity advanced since the
 *    *previous* observation of this session (i.e. work happened and finished entirely inside one
 *    poll gap, wider than [workingWindow]) -> [Status.Finished], notifies. A session's first
 *    observation only seeds its tracked state and never produces [Status.Finished] here, since
 *    there is no previous observation to compare against.
 * 6. A previous [Status.Waiting]/[Status.Finished] persists until rule 4 or 5 fires, or [opened]
 *    sets [Status.Idle].
 * 7. Otherwise [Status.Idle].
 *
 * The focused session (see [focus]) never notifies, but its status is still tracked normally.
 *
 * Known gap (accepted for v1): any effective-activity advance is treated as proof of finished
 * work, so output that isn't the agent finishing a turn - a human typing from the desktop, or a
 * pane simply redrawing after reattachment past [redrawGrace] - can occasionally produce a
 * spurious [Status.Finished] notification.
 */
class StatusTracker(private val workingWindow: Long = 6, private val redrawGrace: Long = 3) {

    /** Commands a `claude` pane may run under; anything else means the session is [Status.Off]. */
    private val claudeCommands = setOf("claude", "node")

    /**
     * Ported from deckaway's `integrations/activity.py` (`Activity.shown`/`Activity.forget`):
     * https://github.com/matacoder/deckaway - MIT License, Copyright (c) 2026 Denis Matakov.
     *
     * tmux counts a resize's redraw as activity, so every terminal attach (a phone unlocking,
     * another tab regaining focus) looked like a burst of work that had just finished. On a size
     * change, [until] is pushed [redrawGrace] seconds out and [shown] keeps reporting the
     * previously shown activity; only a later activity timestamp that is both newer than [shown]
     * and past [until] is adopted.
     */
    private data class Redraw(val size: Pair<Int, Int>, val shown: Long, val until: Long)

    /** Everything remembered for one session between calls to [observe]. */
    private data class PerSession(
        val status: Status,
        val redraw: Redraw?,
        val notifiedFingerprint: String?,
    )

    private var state: Map<String, PerSession> = emptyMap()
    private var focused: String? = null

    fun observe(o: Observation): StatusChange {
        val name = o.session.name
        if (o.session.command !in claudeCommands) {
            return StatusChange(Status.Off, false, null)
        }

        val prev = state[name]
        val prevRedraw = prev?.redraw
        val redraw = redrawFiltered(prevRedraw, o.session.width to o.session.height, o.session.activity, o.now)
        val advanced = prevRedraw != null && redraw.shown > prevRedraw.shown
        val prevStatus = prev?.status ?: Status.Idle
        val prevNotified = prev?.notifiedFingerprint

        val status: Status
        var notify = false
        var notifiedFingerprint = prevNotified

        when {
            o.question != null -> {
                status = Status.Waiting
                val fingerprint = o.question.fingerprint
                if (fingerprint != prevNotified) {
                    notify = true
                    notifiedFingerprint = fingerprint
                }
            }
            o.now - redraw.shown <= workingWindow -> status = Status.Working
            prevStatus == Status.Working || advanced -> {
                status = Status.Finished
                notify = true
            }
            prevStatus == Status.Waiting || prevStatus == Status.Finished -> status = prevStatus
            else -> status = Status.Idle
        }

        if (prevStatus == Status.Waiting && status != Status.Waiting) notifiedFingerprint = null
        if (name == focused) notify = false

        state = state + (name to PerSession(status, redraw, notifiedFingerprint))
        return StatusChange(status, notify, o.question)
    }

    /** Marks [session] opened by the user: clears a pending Waiting/Finished back to [Status.Idle]. */
    fun opened(session: String) {
        val existing = state[session]
        val cleared = existing?.copy(status = Status.Idle, notifiedFingerprint = null)
            ?: PerSession(Status.Idle, null, null)
        state = state + (session to cleared)
    }

    /** Sets the session the user is currently viewing, or `null` for none; it is never notified. */
    fun focus(session: String?) {
        focused = session
    }

    /** Drops tracked state for any session not in [live] (one that was closed or renamed). */
    fun forget(live: Set<String>) {
        state = state.filterKeys { it in live }
    }

    private fun redrawFiltered(prev: Redraw?, size: Pair<Int, Int>, activity: Long, now: Long): Redraw {
        if (prev == null) return Redraw(size, activity, 0)
        if (size != prev.size) return prev.copy(size = size, until = now + redrawGrace)
        return if (activity > prev.shown && activity > prev.until) prev.copy(shown = activity) else prev
    }
}
