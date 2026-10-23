package io.github.alexskuznetsov.xrspatial

/**
 * One raw head pose as returned by `xrLocateSpace(VIEW, LOCAL, predictedDisplayTime)`.
 *
 * Coordinates: OpenXR LOCAL, right-handed metres, +X right, +Y up, −Z forward of the head direction at
 * session start. No conversion to Unity/left-handed axes. This is the head (VIEW) pose, not a camera pose;
 * camera-to-head extrinsics are unknown.
 *
 * Observed on RayNeo X3 Pro (runtime 1.1.7.9, see docs/x3-pro-runtime-notes.md):
 *  - LOCAL is re-created at every session start (yaw ≈ 0, gravity-aligned, a fixed ≈2.6 cm offset), so
 *    poses from different [sessionEpoch]s are not comparable.
 *  - [xrTime] is the runtime's predictedDisplayTime and stayed constant (16666667) on every frame; it is
 *    kept raw and is NOT a usable clock on this runtime.
 *  - [locationFlags] read 15 on every frame, including start-up garbage and with the cameras covered, so
 *    the flags alone do not prove a usable pose; see [WarmupGate].
 * Values are kept exactly as returned (float32, may be NaN/Inf, quaternion not normalised).
 */
data class PoseSample(
    /** Increments per tracking session; LOCAL changes with it. */
    val sessionEpoch: Int,
    /** Frame index within the session (0-based). */
    val frameIndex: Long,
    /** Raw XrTime passed to xrLocateSpace. Not converted, not verified as a clock. */
    val xrTime: Long,
    /**
     * Host CLOCK_MONOTONIC (ns) when xrLocateSpace returned: a receipt time, NOT the time the pose was
     * measured. Use it for ordering and rates only.
     */
    val hostReceiptNs: Long,
    /** Raw XrSpaceLocationFlags bits. */
    val locationFlags: Long,
    val px: Float, val py: Float, val pz: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float,
) {
    val orientationValid: Boolean get() = locationFlags and ORIENTATION_VALID != 0L
    val positionValid: Boolean get() = locationFlags and POSITION_VALID != 0L
    val orientationTracked: Boolean get() = locationFlags and ORIENTATION_TRACKED != 0L
    val positionTracked: Boolean get() = locationFlags and POSITION_TRACKED != 0L

    /** All four flag bits set. Says nothing about whether the numbers are sane (see [WarmupGate]). */
    val allFourFlags: Boolean get() = locationFlags and ALL_FOUR == ALL_FOUR

    val finite: Boolean
        get() = px.isFinite() && py.isFinite() && pz.isFinite() &&
            qx.isFinite() && qy.isFinite() && qz.isFinite() && qw.isFinite()

    /** Euclidean norm of the raw quaternion (double precision). */
    val quaternionNorm: Double
        get() = Math.sqrt(qx.toDouble() * qx + qy.toDouble() * qy + qz.toDouble() * qz + qw.toDouble() * qw)

    /** True when position and orientation bits equal [other]'s (raw float bits, not tolerance). */
    fun sameRawPose(other: PoseSample): Boolean =
        listOf(px, py, pz, qx, qy, qz, qw).map { it.toRawBits() } ==
            listOf(other.px, other.py, other.pz, other.qx, other.qy, other.qz, other.qw).map { it.toRawBits() }

    companion object {
        const val ORIENTATION_VALID = 1L
        const val POSITION_VALID = 2L
        const val ORIENTATION_TRACKED = 4L
        const val POSITION_TRACKED = 8L
        const val ALL_FOUR = 15L
    }
}
