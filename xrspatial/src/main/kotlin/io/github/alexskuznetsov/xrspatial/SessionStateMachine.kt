package io.github.alexskuznetsov.xrspatial

/**
 * The lifecycle decisions of [XrSpatialSession], without Android, RayNeo or native calls, so every transition is
 * JVM-testable. Used on the main thread only.
 *
 * Every start attempt (a start, or a resume after a pause) gets a new [generation]; a bind result or a native-run
 * result of an older attempt is reported as stale and must be ignored. A new attempt is only possible from
 * STOPPED or PAUSED, which are reached only after the previous native run has returned, so two native runs never
 * overlap.
 */
internal class SessionStateMachine {
    @Volatile var state: TrackingState = TrackingState.STOPPED
        private set
    var generation = 0
        private set
    var nativeRunning = false
        private set
    var stopRequested = false
        private set
    private var focusLostInRun = false

    /** Starts an attempt from STOPPED, or from PAUSED when [resume]; returns its generation, or null. */
    fun begin(resume: Boolean = false): Int? {
        if (state != if (resume) TrackingState.PAUSED else TrackingState.STOPPED) return null
        generation++
        state = TrackingState.STARTING
        stopRequested = false
        focusLostInRun = false
        nativeRunning = false
        return generation
    }

    enum class Stop { NONE, NOW, REQUESTED }

    /** NONE: nothing runs. NOW: a pause ended, already STOPPED. REQUESTED: the running attempt ends asynchronously. */
    fun stop(): Stop = when (state) {
        TrackingState.STOPPED -> Stop.NONE
        TrackingState.PAUSED -> { state = TrackingState.STOPPED; Stop.NOW }
        else -> { stopRequested = true; Stop.REQUESTED }
    }

    enum class Bind { STALE, FAILED, CANCELLED, RUN }

    /** What to do with the bind result of attempt [generation]. */
    fun bound(generation: Int, ok: Boolean): Bind = when {
        generation != this.generation || state != TrackingState.STARTING -> Bind.STALE
        !ok -> Bind.FAILED
        stopRequested -> Bind.CANCELLED
        else -> Bind.RUN
    }

    /** The native run of attempt [generation] starts (state RUNNING). */
    fun nativeStarted(generation: Int) {
        if (generation != this.generation) return
        nativeRunning = true
        state = TrackingState.RUNNING
    }

    /** Whether a paused session should resume now. Records focus loss during a native run. */
    fun focusChanged(hasFocus: Boolean): Boolean {
        if (!hasFocus && nativeRunning) focusLostInRun = true
        return hasFocus && state == TrackingState.PAUSED
    }

    enum class End { STALE, PAUSE, FINISH }

    /** The native run of attempt [generation] returned with [run] (null: no parsable result). */
    fun nativeEnded(generation: Int, run: RunResult?): End {
        if (generation != this.generation || !nativeRunning) return End.STALE
        nativeRunning = false
        return if (run != null && run.pausedByFocusLoss(focusLostInRun, stopRequested)) End.PAUSE else End.FINISH
    }

    fun stopping() { state = TrackingState.STOPPING }
    fun stopped() { state = TrackingState.STOPPED; nativeRunning = false }
    fun paused() { state = TrackingState.PAUSED; nativeRunning = false }
}
