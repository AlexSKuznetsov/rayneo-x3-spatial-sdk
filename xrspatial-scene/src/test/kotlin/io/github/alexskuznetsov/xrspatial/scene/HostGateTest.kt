package io.github.alexskuznetsov.xrspatial.scene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostGateTest {
    private val owner = Any()
    private val other = Any()

    private fun ready() = HostGate(owner).also { it.onResumed(owner); it.onSurface(true) }

    @Test fun startsOnlyWhenResumedFocusedAndWithSurface() {
        val g = HostGate(owner)
        assertFalse(g.shouldStart(hasFocus = true))
        g.onResumed(owner)
        assertFalse(g.shouldStart(hasFocus = true))  // no surface yet
        g.onSurface(true)
        assertFalse(g.shouldStart(hasFocus = false))
        assertTrue(g.shouldStart(hasFocus = true))
        assertFalse("one session at a time", g.shouldStart(hasFocus = true))
    }

    @Test fun otherActivitiesAreIgnored() {
        val g = ready()
        assertTrue(g.shouldStart(true))
        assertFalse("another Activity pausing does not stop the session", g.onPaused(other))
        assertTrue(g.resumed)
        assertFalse(g.onResumed(other))
        assertFalse(g.isOwner(other))
    }

    @Test fun quickPauseAndResumeRestartsAfterTheOldSessionStopped() {
        val g = ready()
        assertTrue(g.shouldStart(true))
        assertTrue(g.onPaused(owner))           // host calls session.stop(); the stop is asynchronous
        assertTrue(g.onResumed(owner))
        assertFalse("old session still stopping", g.shouldStart(true))
        assertTrue(g.onStopped("STOPPED"))     // restart requested ...
        assertTrue(g.shouldStart(true))        // ... and possible
    }

    @Test fun noRestartWhilePausedOrAfterAnError() {
        val g = ready()
        assertTrue(g.shouldStart(true))
        g.onPaused(owner)
        assertTrue(g.onStopped("STOPPED_BEFORE_SESSION"))
        assertFalse("not resumed: nothing starts", g.shouldStart(true))
        g.onResumed(owner)
        assertTrue(g.shouldStart(true))
        assertFalse("an error does not loop", g.onStopped("ERROR"))
        g.onStartFailed()
        assertTrue(g.shouldStart(true))  // a later resume or focus gain may try again
    }
}
