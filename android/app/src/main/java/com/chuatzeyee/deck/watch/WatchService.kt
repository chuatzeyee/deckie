package com.chuatzeyee.deck.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.util.Log
import com.chuatzeyee.deck.DeckApp
import com.chuatzeyee.deck.MainActivity
import com.chuatzeyee.deck.R
import com.chuatzeyee.deck.poll.LinkState
import com.chuatzeyee.deck.poll.SessionView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

private const val WATCH_CHANNEL_ID = "deck-watch"
private const val WATCH_NOTIFICATION_ID = 2001
private const val ACTION_STOP = "com.chuatzeyee.deck.watch.STOP"

/**
 * Foreground service (type `specialUse`, see the manifest) that keeps [DeckRuntime]'s shared
 * `HostLink` alive while "Watch in background" is on and no activity is visible, and turns
 * [com.chuatzeyee.deck.poll.PollResult.alerts] into notifications - ruling R24/brief Task 13.
 * [DeckApp] starts/stops this service to match the toggle; this class never flips the toggle
 * itself except via the ongoing notification's Stop action.
 *
 * [onStartCommand] wraps `startForeground` in `try`/`catch`: [DeckApp] already only starts this
 * service while visible or battery-exempt, but a `START_STICKY` restart (or the OS reviving a
 * previously-foreground service after the process died) can still race that guard, and
 * `startForeground` throwing here must never crash the process (fix round 1, critical). That same
 * guard is why `START_STICKY` stays the normal return value; the two early-return paths use
 * `START_NOT_STICKY` since there is nothing useful for the OS to restart in either case.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var linkObserverJob: Job? = null
    private var alertsObserverJob: Job? = null
    private val runtime get() = (application as DeckApp).runtime

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // fix round 1, important #4: runs on runtime.scope, which outlives this service's own
            // scope - stopSelf() below tears this service (and its scope) down right away.
            runtime.scope.launch { runtime.watchPrefs.setEnabled(false) }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(WATCH_NOTIFICATION_ID, watchNotification(0))
        } catch (e: IllegalStateException) {
            Log.w("Deck", "startForeground refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        // fix round 1, important #2: eligibleHost() suspends on the repository's real first
        // emission, so a cold start is never stuck showing "Watching 0 sessions" forever.
        scope.launch { runtime.eligibleHost()?.let { runtime.ensureLink(it) } }
        if (linkObserverJob == null) linkObserverJob = scope.launch { observeSessionCount() }
        if (alertsObserverJob == null) alertsObserverJob = scope.launch { observeAlerts() }
        return START_STICKY
    }

    override fun onDestroy() {
        linkObserverJob?.cancel()
        alertsObserverJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Keeps the ongoing notification's session count current - re-posted only when the count
     * changes, since every tick publishes a fresh [LinkState.Up] (its own timestamp). */
    private suspend fun observeSessionCount() {
        runtime.link
            .mapNotNull { state -> (state as? LinkState.Up)?.result?.views?.size }
            .distinctUntilChanged()
            .collect { count -> notificationManager().notify(WATCH_NOTIFICATION_ID, watchNotification(count)) }
    }

    /** Collects [DeckRuntime.alerts] - not [DeckRuntime.link] - so a fast run of ticks never gets
     * conflated away and a resubscribe never re-posts a stale batch (fix round 1, important #5). */
    private suspend fun observeAlerts() {
        runtime.alerts.collect { alerts -> alerts.forEach(::postAlert) }
    }

    private fun postAlert(view: SessionView) {
        val spec = alertFor(view)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_HOST_ID, spec.hostId)
            putExtra(EXTRA_SESSION, spec.session)
        }
        val pending = PendingIntent.getActivity(
            this,
            spec.id,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, ALERTS_CHANNEL_ID)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setGroup(ALERTS_GROUP_KEY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        notificationManager().notify(spec.id, notification)
    }

    private fun watchNotification(sessionCount: Int): Notification {
        val stopIntent = Intent(this, WatchService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notification),
            "Stop",
            stopPending,
        ).build()
        return Notification.Builder(this, WATCH_CHANNEL_ID)
            .setContentTitle(watchingText(sessionCount))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    private fun createChannels() {
        val manager = notificationManager()
        manager.createNotificationChannel(
            NotificationChannel(WATCH_CHANNEL_ID, "Deck watch service", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERTS_CHANNEL_ID, "Deck alerts", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)
}
