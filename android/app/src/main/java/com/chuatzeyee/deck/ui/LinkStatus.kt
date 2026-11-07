package com.chuatzeyee.deck.ui

import com.chuatzeyee.deck.poll.LinkState

/**
 * The one-line status text for a [LinkState] on [hostName]. Never includes [LinkState.Down.reason]
 * verbatim (Review Focus 3): the raw reason is for a details view/log only, not the headline UI
 * text. The up-but-no-tmux-server case reads exactly `"No tmux sessions on <hostName>"` (SPEC,
 * Review Focus 1) - [hostName] is [com.chuatzeyee.deck.core.Host.name].
 */
fun linkStatusLine(state: LinkState, hostName: String): String = when (state) {
    is LinkState.Connecting -> "Connecting…"
    is LinkState.Up -> if (state.result.noServer) "No tmux sessions on $hostName" else "Connected"
    is LinkState.Down -> "disconnected · retrying in ${state.retryInSec}s"
    is LinkState.IdentityChanged -> "Server identity changed! Not connecting."
}

/** True when [state] is an [LinkState.Up] result older than [staleAfterMs] at [nowMs] - the rail
 * dims it and says "updating…" rather than presenting it as current. A clock that went backwards
 * (`nowMs` before the result) never counts as stale. */
fun isStale(state: LinkState, nowMs: Long, staleAfterMs: Long): Boolean =
    state is LinkState.Up && nowMs - state.atMs > staleAfterMs

/** [Rail]'s `message` parameter for a [LinkState] on [hostName]: non-null replaces the session list
 * outright - a dropped link, identity change, an up-but-empty host (no tmux server running at all),
 * or an up host where none of the chosen sessions are running must never keep showing a stale
 * session list (see [LinkState.Down]'s own doc). */
fun railMessage(state: LinkState, hostName: String): String? = when (state) {
    is LinkState.Connecting -> "Connecting…"
    is LinkState.Up -> when {
        state.result.noServer -> linkStatusLine(state, hostName)
        state.result.views.isEmpty() -> "None of your chosen sessions are running"
        else -> null
    }
    is LinkState.Down, is LinkState.IdentityChanged -> linkStatusLine(state, hostName)
}
