package io.github.alexskuznetsov.xrspatial

enum class Hand { LEFT, RIGHT }

/**
 * One hand in one frame from `XR_EXT_hand_tracking` (experimental), located in the same LOCAL space and at the
 * same raw [xrTime] as the frame's head pose. Only the palm and the index fingertip are passed on.
 *
 * Values are raw: positions are whatever the runtime wrote, also when the flags say they are not valid. Use
 * [palmValid] / [indexTipValid] before using a position. Whether the RayNeo X3 Pro runtime tracks hands at all
 * is not established (see docs/x3-pro-runtime-notes.md).
 */
data class HandSample(
    val sessionEpoch: Int,
    val frameIndex: Long,
    val xrTime: Long,
    val hand: Hand,
    /** Raw XrResult of xrLocateHandJointsEXT (0 = XR_SUCCESS). */
    val locateResult: Int,
    /** XrHandJointLocationsEXT::isActive: the runtime currently tracks this hand. */
    val active: Boolean,
    /** Raw XrSpaceLocationFlags of the palm joint. */
    val palmFlags: Long,
    val palm: Vector3,
    /** Raw XrSpaceLocationFlags of the index fingertip joint. */
    val indexTipFlags: Long,
    val indexTip: Vector3,
) {
    val palmValid: Boolean get() = usable(palmFlags, palm)
    val indexTipValid: Boolean get() = usable(indexTipFlags, indexTip)

    private fun usable(flags: Long, p: Vector3) =
        locateResult == 0 && active && flags and PoseSample.POSITION_VALID != 0L && p.x.isFinite() && p.y.isFinite() && p.z.isFinite()
}

/** Receives both hands of every frame while hand tracking runs; called on the render thread. */
fun interface HandListener {
    fun onHands(left: HandSample, right: HandSample)
}
