package io.github.alexskuznetsov.xrspatial.scene

import io.github.alexskuznetsov.xrspatial.Pose3
import io.github.alexskuznetsov.xrspatial.Quaternion
import io.github.alexskuznetsov.xrspatial.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.PI

class PointerCastTest {
    private val near = PointerCast.Target("near", Pose3(Vector3(0f, 0f, -1f)), 0.2f, 0.1f)
    private val far = PointerCast.Target("far", Pose3(Vector3(0f, 0f, -2f)), 1f, 1f)
    private val dot = PointerStyle.Dot(distanceWithoutHit = 1.5f)

    @Test fun gazeHitsTheNearestNode() {
        val r = PointerCast.cast(PointerSource.Gaze(), dot, Pose3.IDENTITY, listOf(far, near))!!
        assertSame(near, r.target)
        assertEquals(1f, r.hit!!.distance, 1e-5f)
        assertEquals(-1f, r.end.z, 1e-5f)
    }

    @Test fun withoutHitTheEndIsAtTheStyleDistance() {
        val r = PointerCast.cast(PointerSource.Gaze(), dot, Pose3.IDENTITY, emptyList())!!
        assertNull(r.target)
        assertEquals(-1.5f, r.end.z, 1e-5f)
        val ray = PointerCast.cast(PointerSource.Gaze(), PointerStyle.Ray(maxLength = 3f), Pose3.IDENTITY, emptyList())!!
        assertEquals(-3f, ray.end.z, 1e-5f)
    }

    @Test fun gazeFollowsTheDisplayAxisAndBeamStartsAtTheOffset() {
        // Display pitched 10.8 degrees down at 1.6 m height: the gaze passes under the near node.
        val display = Pose3(Vector3(0f, 1.6f, 0f), Quaternion.axisAngle(Vector3.RIGHT, (-10.8 * PI / 180).toFloat()))
        val atEyeHeight = PointerCast.Target("card", Pose3(Vector3(0f, 1.6f, -1f)), 0.2f, 0.1f)
        val below = PointerCast.Target("low", Pose3(Vector3(0f, 1.6f - 0.191f, -1f)), 0.2f, 0.1f)
        val r = PointerCast.cast(PointerSource.Gaze(Vector3(0.1f, -0.15f, 0f)), dot, display, listOf(atEyeHeight, below))!!
        assertSame(below, r.target)
        assertEquals(0.1f, r.beamStart.x, 1e-5f)
        assertEquals(1.6f - 0.15f * Math.cos(10.8 * PI / 180).toFloat(), r.beamStart.y, 1e-4f)
    }

    @Test fun customSourceUsesItsOwnPoseAndCanHide() {
        // A source to the right, pointing left at the near node's plane... and missing it; then pointing at it.
        val fromRight = Pose3(Vector3(1f, 0f, -1f), Quaternion.axisAngle(Vector3.UP, (PI / 2).toFloat()))
        val miss = PointerCast.cast(PointerSource.Custom { fromRight }, dot, Pose3.IDENTITY, listOf(near))!!
        assertNull(miss.target)  // parallel to the node's plane
        val ahead = Pose3(Vector3(0.05f, 0f, 0f))
        val hit = PointerCast.cast(PointerSource.Custom { ahead }, dot, Pose3.IDENTITY, listOf(near))!!
        assertSame(near, hit.target)
        assertEquals(0.75f, hit.hit!!.u, 1e-5f)
        assertEquals(0.05f, hit.beamStart.x, 0f)
        assertNull(PointerCast.cast(PointerSource.Custom { null }, dot, Pose3.IDENTITY, listOf(near)))
    }
}
