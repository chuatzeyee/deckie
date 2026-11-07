package com.chuatzeyee.deck.ui

import com.chuatzeyee.deck.poll.LinkState
import com.chuatzeyee.deck.ssh.TerminalState

/**
 * Whether a failed terminal should re-attach on its own because the host's polling link just came
 * back ([previous] was not [LinkState.Up], [current] is): the network that broke the terminal is
 * evidently back, so the user shouldn't have to tap "retry". Only transitions count, so a terminal
 * that keeps failing while the link stays up is left for the user to retry.
 */
fun shouldRetryTerminalOnLink(terminal: TerminalState, previous: LinkState, current: LinkState): Boolean =
    terminal is TerminalState.Failed && previous !is LinkState.Up && current is LinkState.Up

/** Whether a failed terminal should re-attach on its own when the app comes back to the front. */
fun shouldRetryTerminalOnResume(terminal: TerminalState, visible: Boolean): Boolean =
    visible && terminal is TerminalState.Failed
