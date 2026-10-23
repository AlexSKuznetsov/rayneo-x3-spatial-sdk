package io.github.alexskuznetsov.xrspatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunResultTest {
    private val ok = RunResult("COMPLETED", "", "COMPLETED", "", "COMPLETED", "", "EXITING_AFTER_EXIT_REQUEST")

    @Test fun requestedStopIsNotAnError() {
        assertNull(ok.error())
        assertNull(ok.copy(endReason = "STOPPING_AFTER_EXIT_REQUEST").error())
    }

    @Test fun instanceFailureMeansRuntimeUnavailable() {
        val e = ok.copy(instanceStatus = "ERROR", instanceFailure = "xrCreateInstance:-2").error()
        assertTrue(e is SpatialError.RuntimeUnavailable)
        assertEquals("xrCreateInstance:-2", e!!.operation)
        assertEquals(-2, e.resultCode)
    }

    @Test fun graphicsFailureKeepsOperationAndCode() {
        val e = ok.copy(graphicsStatus = "ERROR", graphicsFailure = "xrCreateSession:-38").error()
        assertTrue(e is SpatialError.SessionUnsupported)
        assertEquals(-38, e!!.resultCode)
        assertTrue(ok.copy(graphicsStatus = null, graphicsFailure = null).error() is SpatialError.SessionUnsupported)
    }

    @Test fun frameFailureAndRuntimeEndAreSessionLost() {
        val frame = ok.copy(loopStatus = "ERROR", loopFailure = "xrEndFrame", endReason = "FRAME_FAILURE").error()
        assertTrue(frame is SpatialError.SessionLost)
        assertEquals("xrEndFrame", frame!!.operation)
        assertNull(frame.resultCode)
        val lost = ok.copy(endReason = "SESSION_LOST_OR_EXITING").error()
        assertTrue(lost is SpatialError.SessionLost)
        assertEquals("SESSION_LOST_OR_EXITING", lost!!.operation)
    }

    @Test fun runtimeStopAfterFocusLossIsAPause() {
        val stopped = ok.copy(endReason = "RUNTIME_STOPPING")
        assertTrue(stopped.pausedByFocusLoss(focusLost = true, stopRequested = false))
        assertFalse(stopped.pausedByFocusLoss(focusLost = false, stopRequested = false))  // still SessionLost
        assertFalse(stopped.pausedByFocusLoss(focusLost = true, stopRequested = true))    // our own stop wins
        assertFalse(ok.pausedByFocusLoss(focusLost = true, stopRequested = false))        // exit we requested
        assertFalse(stopped.copy(loopStatus = "ERROR", loopFailure = "xrEndFrame").pausedByFocusLoss(true, false))
    }
}
