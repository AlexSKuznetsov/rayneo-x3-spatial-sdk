package io.github.alexskuznetsov.xrspatial.scene

/**
 * [SpatialHost]'s start/stop decisions, without Android, so they are JVM-testable. Main thread only.
 *
 * The session needs the [owner] Activity resumed and focused, with its surface. Lifecycle events of other
 * Activities are ignored. After a normal stop the host starts again by itself if those conditions still hold
 * (a quick pause and resume can complete while the previous session is still stopping); after an error it
 * does not, so a failing start cannot loop.
 */
internal class HostGate(private val owner: Any) {
    var resumed = false
        private set
    var surfaceReady = false
        private set
    /** A session is starting, running, paused or stopping: do not start another. */
    var running = false
        private set

    /** True when [activity] is the owner (then try to start). */
    fun onResumed(activity: Any): Boolean {
        if (activity !== owner) return false
        resumed = true
        return true
    }

    /** True when [activity] is the owner (then stop the session). */
    fun onPaused(activity: Any): Boolean {
        if (activity !== owner) return false
        resumed = false
        return true
    }

    fun isOwner(activity: Any) = activity === owner

    fun onSurface(ready: Boolean) { surfaceReady = ready }

    /** Whether to start a session now; if so it counts as running from here on. */
    fun shouldStart(hasFocus: Boolean): Boolean {
        if (running || !resumed || !surfaceReady || !hasFocus) return false
        running = true
        return true
    }

    fun onStartFailed() { running = false }

    /** The session stopped with [reason]; true when the host should try to start again at once. */
    fun onStopped(reason: String): Boolean {
        running = false
        return reason in RESTARTABLE
    }

    private companion object {
        /** Ends asked for by the app or the host (XrSpatialSession's onStopped reasons), not errors. */
        val RESTARTABLE = setOf("STOPPED", "STOPPED_BEFORE_SESSION")
    }
}
