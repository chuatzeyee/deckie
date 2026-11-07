package com.chuatzeyee.deck.ui

/**
 * Which screen [com.chuatzeyee.deck.MainActivity] shows. No navigation library: this task only
 * has two real screens, so a plain sealed state (switched on on by the activity's Compose root)
 * is simpler than wiring up a nav graph for one transition.
 */
sealed interface Screen {
    /** Add/manage hosts; also the list of already-configured hosts. Always the start screen. */
    data object HostSetup : Screen

    /** Picking which live tmux sessions on [hostId] to show, after a successful connection. */
    data class SessionPicker(val hostId: String) : Screen

    /** The rail + live terminal home screen for [hostId]'s chosen sessions. */
    data class Deck(val hostId: String) : Screen
}
