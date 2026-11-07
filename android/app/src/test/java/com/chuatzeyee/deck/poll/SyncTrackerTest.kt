package com.chuatzeyee.deck.poll

import com.chuatzeyee.deck.core.StatusTracker
import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTrackerTest {

    /**
     * [SyncTracker] exists so [HostLink.opened]/[HostLink.focus] can be called from any thread
     * while the poll loop concurrently calls [StatusTracker.observe]/[StatusTracker.forget] - that
     * only holds if every public method synchronizes on the same monitor (here, the instance
     * itself via `@Synchronized`). Deterministic by construction - no timing/thread races needed.
     */
    @Test fun every_public_method_is_synchronized_on_the_instance_monitor() {
        val methods = SyncTracker::class.java.declaredMethods.filter { !it.isSynthetic && !it.isBridge }

        assertTrue("expected observe/opened/focus/forget to exist", methods.isNotEmpty())
        methods.forEach { m ->
            assertTrue(
                "${m.name} must be @Synchronized so HostLink.opened/focus are safe from any thread",
                Modifier.isSynchronized(m.modifiers),
            )
        }
    }

    @Test fun forwards_every_call_to_the_delegate() {
        val tracker = StatusTracker()
        val sync = SyncTracker(tracker)

        sync.opened("api") // must not throw - exercises the real delegate through the facade
        sync.focus("api")
        sync.forget(setOf("api"))
    }
}
