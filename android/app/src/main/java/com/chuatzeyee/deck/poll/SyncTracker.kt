package com.chuatzeyee.deck.poll

import com.chuatzeyee.deck.core.Observation
import com.chuatzeyee.deck.core.StatusChange
import com.chuatzeyee.deck.core.StatusTracker

/**
 * Thread-safe facade over [StatusTracker], which is itself documented as "not thread-safe:
 * callers must serialize calls". Every method synchronizes on this instance's own monitor, so
 * [HostLink] can let [HostLink.opened]/[HostLink.focus] be called from any thread (e.g. the UI
 * thread) while its own poll loop concurrently calls [observe]/[forget] from a coroutine -
 * exactly the serialization [StatusTracker] requires its caller to provide, centralized in one
 * place instead of left to every caller to get right.
 */
class SyncTracker(private val delegate: StatusTracker) {
    @Synchronized fun observe(observation: Observation): StatusChange = delegate.observe(observation)
    @Synchronized fun opened(session: String) = delegate.opened(session)
    @Synchronized fun focus(session: String?) = delegate.focus(session)
    @Synchronized fun forget(live: Set<String>) = delegate.forget(live)
}
