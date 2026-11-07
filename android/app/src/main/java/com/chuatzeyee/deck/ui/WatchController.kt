package com.chuatzeyee.deck.ui

import android.content.Intent
import com.chuatzeyee.deck.watch.DeckRuntime
import com.chuatzeyee.deck.watch.EXTRA_HOST_ID
import com.chuatzeyee.deck.watch.EXTRA_SESSION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A tap on an alert notification (ruling R24/brief Task 13): the Deck screen to open and the
 * session to select on it - see [WatchController.openFromNotification]. */
data class PendingOpen(val hostId: String, val session: String)

/** [WatchController.watchAskState]'s payload - the two DataStore-backed values `MainActivity`'s
 * R15 ask-once gate needs, bundled so they only ever reach it together once both have actually
 * loaded (fix round 2, minor). */
data class WatchAskState(val enabled: Boolean, val asked: Boolean)

/**
 * The "Watch in background" toggle and alert-notification-tap navigation for [AppViewModel] -
 * split out of it so that file stays under the file-size limit (fix round 1, important #6).
 */
class WatchController(private val scope: CoroutineScope, private val runtime: DeckRuntime) {

    /** The persisted "Watch in background" toggle (ruling R15) - the host-setup screen's switch. */
    val watchEnabled: StateFlow<Boolean> =
        runtime.watchPrefs.enabled.stateIn(scope, SharingStarted.WhileSubscribed(5_000), true)

    fun setWatchEnabled(enabled: Boolean) {
        scope.launch { runtime.watchPrefs.setEnabled(enabled) }
    }

    /** `null` until both DataStore-backed values behind it have actually loaded - gates
     * `MainActivity`'s R15 "ask once" logic (fix round 2, minor). [watchEnabled] above is allowed
     * a placeholder initial value since a UI switch merely rendering "on" for a moment while
     * loading is harmless, but this one is never allowed to use a placeholder: a stale `asked =
     * false` read before the real (possibly `true`) stored value has loaded would re-show a
     * permission prompt the user already dismissed once. */
    val watchAskState: StateFlow<WatchAskState?> =
        combine(runtime.watchPrefs.enabled, runtime.watchPrefs.permissionsAsked) { enabled, asked ->
            WatchAskState(enabled, asked)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    fun markPermissionsAsked() {
        scope.launch { runtime.watchPrefs.setPermissionsAsked(true) }
    }

    private val _pendingOpen = MutableStateFlow<PendingOpen?>(null)
    /** Set by [openFromNotification]; `MainActivity` navigates to it then calls [clearPendingOpen]. */
    val pendingOpen: StateFlow<PendingOpen?> = _pendingOpen.asStateFlow()

    /** Reads an alert's `hostId`/`session` extras (`MainActivity.onCreate`/`onNewIntent`) off
     * [intent] and queues the matching navigation via [pendingOpen]. A no-op for any other intent
     * (a plain launcher tap has neither extra). */
    fun openFromNotification(intent: Intent) {
        val hostId = intent.getStringExtra(EXTRA_HOST_ID) ?: return
        val session = intent.getStringExtra(EXTRA_SESSION) ?: return
        _pendingOpen.value = PendingOpen(hostId, session)
    }

    fun clearPendingOpen() {
        _pendingOpen.value = null
    }
}
