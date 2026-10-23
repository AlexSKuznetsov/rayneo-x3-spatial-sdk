package io.github.alexskuznetsov.xrspatial

/**
 * Head 6DoF tracking session. Implementations run their own frame loop and deliver every located sample,
 * classified by a per-session [WarmupGate]. Nothing is synthesised: when no pose is available, nothing is
 * delivered and [latestUsable] stays null.
 */
interface SpatialTracking {
    /**
     * Starts one session; returns immediately. Failures before the loop runs are returned here, failures
     * afterwards go to [PoseListener.onError]. Calling start while a session runs fails with
     * [SpatialError.AlreadyRunning].
     */
    fun start(listener: PoseListener): Result<Unit>

    /** Requests the session to end; [PoseListener.onStopped] follows once resources are released. */
    fun stop()

    /** Latest sample classified [SampleQuality.USABLE] in the current session, or null. */
    fun latestUsable(): PoseSample?

    /** Implementation-defined facts for logs (runtime/SDK versions, state). Never a success claim. */
    fun diagnostics(): Map<String, String>
}

/** Callbacks arrive on the implementation's tracking thread; keep them short. */
interface PoseListener {
    fun onSample(sample: PoseSample, quality: SampleQuality)
    fun onStateChanged(state: TrackingState) {}
    fun onError(error: SpatialError) {}
    fun onStopped(reason: String) {}
}

enum class TrackingState { STARTING, RUNNING, STOPPING, STOPPED }

/** Failures keep the failing operation's name and raw result code (e.g. an XrResult), never a guess. */
sealed class SpatialError(message: String, val operation: String? = null, val resultCode: Int? = null) :
    Exception(message) {
    class RuntimeUnavailable(operation: String? = null, resultCode: Int? = null) :
        SpatialError("OpenXR runtime is unavailable", operation, resultCode)
    class LoaderInitFailed(operation: String? = null, resultCode: Int? = null) :
        SpatialError("Android loader initialization failed", operation, resultCode)
    class SessionUnsupported(operation: String? = null, resultCode: Int? = null) :
        SpatialError("OpenXR session could not be created or run", operation, resultCode)
    class PositionTrackingUnavailable(operation: String? = null, resultCode: Int? = null) :
        SpatialError("Tracked position is unavailable", operation, resultCode)
    class PermissionDenied(operation: String? = null) : SpatialError("Required access was denied", operation)
    class VendorInitializationFailed(operation: String? = null, resultCode: Int? = null) :
        SpatialError("Vendor SDK initialization failed", operation, resultCode)
    class SessionLost(operation: String? = null, resultCode: Int? = null) :
        SpatialError("Tracking session ended unexpectedly", operation, resultCode)
    class AlreadyRunning : SpatialError("A tracking session is already running")
}
