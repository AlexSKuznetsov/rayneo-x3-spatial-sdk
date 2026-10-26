package io.github.alexskuznetsov.xrspatial.scene

import io.github.alexskuznetsov.xrspatial.Pose3
import io.github.alexskuznetsov.xrspatial.Vector3

/**
 * Where a [SpatialNode] is. All placements live in the LOCAL space of the current tracking session: when a
 * new session starts (first start, or after a pause on focus loss) LOCAL is re-created around the head, so
 * world-locked content is placed again. Nothing persists across sessions; the RayNeo X3 Pro runtime offers
 * no spatial anchors (see docs/x3-pro-runtime-notes.md).
 */
sealed interface Placement {
    /**
     * World-locked, placed once in front of the wearer: [distance] metres along the display's view axis (on
     * the X3 Pro about 11 degrees below the head's forward direction) from where the head is when the node is
     * first drawn in a session, turned to face the wearer without roll. [offset] moves it in the node's own
     * frame afterwards (x right, y up, z towards the wearer). It then stays put while the wearer moves.
     * Placed again in every new session and after [SpatialScene.recenter].
     */
    data class InFrontOfUser(val distance: Float = 1.5f, val offset: Vector3 = Vector3.ZERO) : Placement

    /** World-locked at a fixed pose in the session's LOCAL space (the node's front face is its +Z side). */
    data class InLocal(val pose: Pose3) : Placement

    /**
     * Head-locked: always [distance] metres along the display's view axis, shifted by [offset] (x right, y up)
     * in the display frame, facing the wearer. Use sparingly: head-locked content does not test tracking.
     */
    data class HeadLocked(val distance: Float = 1.5f, val offset: Vector3 = Vector3.ZERO) : Placement
}
