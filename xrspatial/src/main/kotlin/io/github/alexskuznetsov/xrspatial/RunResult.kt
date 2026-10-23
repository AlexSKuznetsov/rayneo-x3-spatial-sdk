package io.github.alexskuznetsov.xrspatial

/**
 * The parts of the native result JSON ({"instance","graphics","loop"}) that decide how a session ended.
 * Pure, so the mapping is testable on the JVM without Android's org.json.
 */
internal data class RunResult(
    val instanceStatus: String?, val instanceFailure: String?,
    val graphicsStatus: String?, val graphicsFailure: String?,
    val loopStatus: String?, val loopFailure: String?, val endReason: String?,
) {
    /** Null when the session ended because it was asked to; otherwise the first failing stage. */
    fun error(): SpatialError? {
        if (instanceStatus != "COMPLETED") return SpatialError.RuntimeUnavailable(instanceFailure.orStage("instance"), code(instanceFailure))
        if (graphicsStatus != "COMPLETED") return SpatialError.SessionUnsupported(graphicsFailure.orStage("graphics"), code(graphicsFailure))
        if (loopStatus != "COMPLETED") return SpatialError.SessionLost(loopFailure.orStage(endReason.orStage("loop")), code(loopFailure))
        if (endReason in RUNTIME_ENDED) return SpatialError.SessionLost(endReason)
        return null
    }

    /**
     * The runtime ended a healthy session while the app had lost window focus and nobody asked it to stop:
     * a pause, not an error (observed: the runtime answers focus loss with STOPPING).
     */
    fun pausedByFocusLoss(focusLost: Boolean, stopRequested: Boolean): Boolean =
        focusLost && !stopRequested && instanceStatus == "COMPLETED" && graphicsStatus == "COMPLETED" &&
            loopStatus == "COMPLETED" && endReason in RUNTIME_ENDED

    companion object {
        /** End reasons where the runtime ended the session without our exit request (frame_loop.h loop()). */
        val RUNTIME_ENDED = setOf("RUNTIME_STOPPING", "SESSION_LOST_OR_EXITING")

        /** Trailing ":<int>" of a failure text such as "xrCreateSession:-2" is the raw result code. */
        fun code(failure: String?): Int? = failure?.substringAfterLast(':', "")?.toIntOrNull()
        private fun String?.orStage(stage: String) = if (isNullOrEmpty()) stage else this
    }
}
