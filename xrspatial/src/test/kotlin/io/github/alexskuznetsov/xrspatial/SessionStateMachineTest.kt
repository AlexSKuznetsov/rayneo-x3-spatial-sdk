package io.github.alexskuznetsov.xrspatial

import io.github.alexskuznetsov.xrspatial.SessionStateMachine.Bind
import io.github.alexskuznetsov.xrspatial.SessionStateMachine.End
import io.github.alexskuznetsov.xrspatial.SessionStateMachine.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {
    private val requested = RunResult("COMPLETED", "", "COMPLETED", "", "COMPLETED", "", "EXITING_AFTER_EXIT_REQUEST")
    private val runtimeStopped = requested.copy(endReason = "RUNTIME_STOPPING")

    private fun running(m: SessionStateMachine = SessionStateMachine()): Pair<SessionStateMachine, Int> {
        val g = m.begin()!!
        assertEquals(Bind.RUN, m.bound(g, ok = true))
        m.nativeStarted(g)
        return m to g
    }

    @Test fun normalStartAndStop() {
        val (m, g) = running()
        assertEquals(TrackingState.RUNNING, m.state)
        assertNull("no second attempt while running", m.begin())
        assertEquals(Stop.REQUESTED, m.stop())
        assertEquals(End.FINISH, m.nativeEnded(g, requested))
        m.stopping(); m.stopped()
        assertEquals(TrackingState.STOPPED, m.state)
        assertEquals(Stop.NONE, m.stop())
    }

    @Test fun stopDuringBindCancelsTheStart() {
        val m = SessionStateMachine()
        val g = m.begin()!!
        assertEquals(Stop.REQUESTED, m.stop())
        assertEquals(Bind.CANCELLED, m.bound(g, ok = true))
        assertFalse(m.nativeRunning)
        assertNull("no new start until the cancelled one has finished", m.begin())
        m.stopping(); m.stopped()
        assertNotNull(m.begin())
    }

    @Test fun staleBindAndRunResultsAreIgnored() {
        val m = SessionStateMachine()
        val first = m.begin()!!
        assertEquals(Bind.FAILED, m.bound(first, ok = false))
        m.stopping(); m.stopped()
        val second = m.begin()!!
        assertEquals(Bind.STALE, m.bound(first, ok = true))
        assertEquals(Bind.RUN, m.bound(second, ok = true))
        m.nativeStarted(first)  // stale: ignored
        assertFalse(m.nativeRunning)
        m.nativeStarted(second)
        assertEquals(End.STALE, m.nativeEnded(first, requested))
        assertTrue(m.nativeRunning)
    }

    @Test fun focusLossPausesAndFocusGainResumesWithANewAttempt() {
        val (m, g) = running()
        assertFalse(m.focusChanged(hasFocus = false))
        assertEquals(End.PAUSE, m.nativeEnded(g, runtimeStopped))
        m.stopping(); m.paused()
        assertNull("a paused session is resumed, not started", m.begin())
        assertTrue(m.focusChanged(hasFocus = true))
        val resumed = m.begin(resume = true)!!
        assertTrue(resumed > g)
        assertEquals(TrackingState.STARTING, m.state)
    }

    @Test fun fastFocusLossAndGainWhileRunningNeverOverlapsRuns() {
        val (m, g) = running()
        assertFalse(m.focusChanged(false))
        assertFalse("still RUNNING: no resume, no second run", m.focusChanged(true))
        assertNull(m.begin(resume = true))
        assertNull(m.begin())
        assertEquals(End.PAUSE, m.nativeEnded(g, runtimeStopped))
        assertEquals(End.STALE, m.nativeEnded(g, runtimeStopped))  // reported once
    }

    @Test fun requestedStopIsNotAPauseEvenAfterFocusLoss() {
        val (m, g) = running()
        m.focusChanged(false)
        m.stop()
        assertEquals(End.FINISH, m.nativeEnded(g, runtimeStopped))
    }

    @Test fun stopWhilePausedStopsAtOnce() {
        val (m, g) = running()
        m.focusChanged(false)
        m.nativeEnded(g, runtimeStopped)
        m.stopping(); m.paused()
        assertEquals(Stop.NOW, m.stop())
        assertEquals(TrackingState.STOPPED, m.state)
        assertFalse(m.focusChanged(true))
        assertNotNull(m.begin())
    }
}
