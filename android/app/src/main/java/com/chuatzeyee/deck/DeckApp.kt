package com.chuatzeyee.deck

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import com.chuatzeyee.deck.watch.DeckRuntime
import com.chuatzeyee.deck.watch.WatchService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Deck's [Application]: owns the app-scoped [DeckRuntime] (ruling R24) and reports whether
 * `MainActivity` is resumed to it, so [DeckRuntime] can pick the right poll interval and know when
 * no activity needs the live connection any more. Also starts/stops [WatchService] to match the
 * "Watch in background" toggle - a single place doing that, so the service comes back on its own
 * after a process restart with the toggle already on, not only right after the user flips it.
 *
 * No boot receiver in v1 (fix round 1): [WatchService] only ever (re)starts from an app process
 * start - a cold launch, or the OS reviving this `Application` to satisfy `WatchService`'s own
 * `START_STICKY` - never from `BOOT_COMPLETED`. If the toggle is on but the phone reboots without
 * Deck being opened, watching only resumes the next time the user opens the app.
 */
class DeckApp : Application() {

    lateinit var runtime: DeckRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = DeckRuntime(this)
        registerActivityLifecycleCallbacks(VisibilityTracker(runtime))
        registerNetworkKick()
        runtime.scope.launch {
            combine(runtime.watchPrefs.enabled, runtime.repo.hosts, runtime.visible) { enabled, hosts, visible ->
                (enabled && hosts.isNotEmpty()) to visible
            }.distinctUntilChanged().collect { (shouldRun, visible) -> toggleWatchService(shouldRun, visible) }
        }
    }

    /**
     * Starts/stops [WatchService] to match the toggle. Android 12+ (API 31) forbids starting a
     * foreground service from the background unless the app is visible or already holds the
     * battery-optimization exemption: calling `startForegroundService` otherwise throws
     * `ForegroundServiceStartNotAllowedException` (a subtype of [IllegalStateException]) straight
     * out of this collector coroutine, which has no handler and would crash the process - possibly
     * in a loop, since the toggle staying on means this runs again on the next process start
     * (critical fix, fix round 1). So: skip the start outright when neither holds - [runtime]'s
     * combine above re-fires this with `visible = true` the moment `MainActivity` next resumes,
     * which starts it then - and keep the `try`/`catch` anyway as a second line of defense (e.g.
     * the exemption check here racing the user revoking it a moment later).
     */
    private fun toggleWatchService(shouldRun: Boolean, visible: Boolean) {
        val intent = Intent(this, WatchService::class.java)
        if (!shouldRun) {
            stopService(intent)
            return
        }
        if (!visible && !hasBatteryExemption()) return
        try {
            startForegroundService(intent)
        } catch (e: IllegalStateException) {
            Log.w("Deck", "Could not start WatchService", e)
        }
    }

    /** Wi-Fi -> mobile, or the network coming back after the phone slept: the live link retries
     * at once instead of waiting out its backoff (B3). Also fires once for the current network
     * right after registering, which is a harmless extra kick. */
    private fun registerNetworkKick() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        try {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = runtime.kick()
            })
        } catch (e: RuntimeException) {
            Log.w("Deck", "Could not watch network changes", e)
        }
    }

    private fun hasBatteryExemption(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(packageName) == true
    }
}

/** Counts resumed/paused `MainActivity` instances (there's only ever one) and forwards whether
 * any is currently resumed to [runtime] - the "no `lifecycle-process` dependency" fallback the
 * ruling calls for. */
private class VisibilityTracker(private val runtime: DeckRuntime) : Application.ActivityLifecycleCallbacks {
    private var resumedCount = 0

    override fun onActivityResumed(activity: Activity) {
        resumedCount++
        runtime.setVisible(resumedCount > 0)
    }

    override fun onActivityPaused(activity: Activity) {
        resumedCount = (resumedCount - 1).coerceAtLeast(0)
        runtime.setVisible(resumedCount > 0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
