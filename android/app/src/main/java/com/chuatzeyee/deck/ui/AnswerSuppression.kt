package com.chuatzeyee.deck.ui

import com.chuatzeyee.deck.poll.LinkState

/**
 * After a one-tap answer is [com.chuatzeyee.deck.core.AnswerResult.Sent], the question it answered
 * can stay on screen for a poll or two while Claude redraws; showing its buttons again invites a
 * stale second tap. These pure functions keep a `session -> fingerprint` map of answered questions
 * hidden until a poll has observed that question gone (or replaced by a different one).
 */

/** Drops every entry an [LinkState.Up] poll shows is no longer on screen. Any other state is not a
 * fresh observation, so nothing is dropped. */
fun pruneSuppressed(suppressed: Map<String, String>, state: LinkState): Map<String, String> {
    if (suppressed.isEmpty() || state !is LinkState.Up) return suppressed
    val showing = state.result.views.associate { it.name to it.question?.fingerprint }
    return suppressed.filter { (session, fingerprint) -> showing[session] == fingerprint }
}

/** [state] with each suppressed question removed from its own session's view. */
fun hideSuppressed(state: LinkState, suppressed: Map<String, String>): LinkState {
    if (suppressed.isEmpty() || state !is LinkState.Up) return state
    val views = state.result.views.map { view ->
        val question = view.question
        if (question != null && suppressed[view.name] == question.fingerprint) view.copy(question = null) else view
    }
    return state.copy(result = state.result.copy(views = views))
}
