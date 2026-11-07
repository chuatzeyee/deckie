package com.chuatzeyee.deck.watch

import com.chuatzeyee.deck.core.Status
import com.chuatzeyee.deck.poll.SessionView

/**
 * One notification [WatchService] should post for a [SessionView] that just became notify-worthy
 * (an entry of [com.chuatzeyee.deck.poll.PollResult.alerts]). [id] is stable for a given
 * [hostId]/[session] pair (see [notificationId]) so a fresh alert for the same session replaces
 * the previous one instead of stacking a second notification.
 */
data class AlertSpec(
    val id: Int,
    val title: String,
    val text: String,
    val hostId: String,
    val session: String,
)

/** Everything a group of alerts are posted under - see `WatchService`. */
const val ALERTS_CHANNEL_ID = "deck-alerts"
const val ALERTS_GROUP_KEY = "deck"

/** Intent extras on the `MainActivity` the alert's `PendingIntent` opens - selects [AlertSpec.session]
 * on [AlertSpec.hostId] (`MainActivity.onCreate`/`onNewIntent`). */
const val EXTRA_HOST_ID = "hostId"
const val EXTRA_SESSION = "session"

/**
 * Builds the [AlertSpec] for [v], a session [com.chuatzeyee.deck.core.StatusTracker] just marked
 * notify-worthy. Only [Status.Waiting] and [Status.Finished] are ever handed here in practice -
 * [com.chuatzeyee.deck.poll.PollResult.alerts] only ever contains those - but the other statuses
 * get an inert fallback rather than throwing, so a caller can't crash the watch service on an
 * unexpected status.
 */
fun alertFor(v: SessionView): AlertSpec = when (v.status) {
    Status.Waiting -> AlertSpec(
        id = notificationId(v.hostId, v.name),
        title = "${v.name} needs you",
        text = v.question?.title?.substringBefore('\n') ?: "",
        hostId = v.hostId,
        session = v.name,
    )
    Status.Finished -> AlertSpec(
        id = notificationId(v.hostId, v.name),
        title = "${v.name} finished",
        text = "Claude is waiting for your next prompt",
        hostId = v.hostId,
        session = v.name,
    )
    Status.Working, Status.Idle, Status.Off -> AlertSpec(
        id = notificationId(v.hostId, v.name),
        title = v.name,
        text = "",
        hostId = v.hostId,
        session = v.name,
    )
}

/** Stable per host+session, so a fresh alert replaces rather than stacks (brief, Task 13). */
fun notificationId(hostId: String, session: String): Int = "$hostId\u0000$session".hashCode()

/** [WatchService]'s ongoing "watching" notification text - singular for exactly one session
 * (fix round 1, minor). */
fun watchingText(sessionCount: Int): String =
    if (sessionCount == 1) "Watching 1 session" else "Watching $sessionCount sessions"
