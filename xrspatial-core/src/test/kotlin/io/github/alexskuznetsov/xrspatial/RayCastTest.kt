package io.github.alexskuznetsov.xrspatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI

class RayCastTest {
    // A 0.4 x 0.2 m rectangle 1.5 m ahead, facing the origin (its +Z towards +Z).
    private val rect = Pose3(Vector3(0f, 0f, -1.5f))
    private val forward = Ray3(Vector3.ZERO, Vector3.FORWARD)

    @Test fun centreHit() {
        val hit = forward.hitRect(rect, 0.4f, 0.2f)!!
        assertEquals(1.5f, hit.distance, 1e-6f)
        assertEquals(0.5f, hit.u, 1e-6f)
        assertEquals(0.5f, hit.v, 1e-6f)
        assertEquals(-1.5f, hit.point.z, 1e-6f)
    }

    @Test fun uvLikeImagePixels() {
        // Up-left of centre: u < 0.5 (left), v < 0.5 (top).
        val hit = Ray3(Vector3.ZERO, Vector3(-0.1f, 0.05f, -1.5f)).hitRect(rect, 0.4f, 0.2f)!!
        assertEquals(0.25f, hit.u, 1e-5f)
        assertEquals(0.25f, hit.v, 1e-5f)
    }

    @Test fun edgesBehindAndBackSide() {
        assertNull(Ray3(Vector3.ZERO, Vector3(0.25f, 0f, -1.5f)).hitRect(rect, 0.4f, 0.2f))  // beyond the right edge
        assertNull(Ray3(Vector3.ZERO, Vector3.FORWARD * -1f).hitRect(rect, 0.4f, 0.2f))    // pointing away
        assertNull(Ray3(Vector3(0f, 0f, -3f), Vector3(0f, 0f, 1f)).hitRect(rect, 0.4f, 0.2f)) // from behind
        assertNull(Ray3(Vector3.ZERO, Vector3.RIGHT).hitRect(rect, 0.4f, 0.2f))              // parallel
        assertNull(forward.hitRect(rect, 0f, 0.2f))
    }

    @Test fun rotatedRectangle() {
        // Turned 90 degrees left (yaw) and placed to the left: faces +X, i.e. towards the origin.
        val left = Pose3(Vector3(-1f, 0f, 0f), Quaternion.axisAngle(Vector3.UP, (PI / 2).toFloat()))
        val hit = Ray3(Vector3.ZERO, Vector3(-1f, 0f, 0f)).hitRect(left, 0.4f, 0.2f)!!
        assertEquals(1f, hit.distance, 1e-5f)
        assertEquals(0.5f, hit.u, 1e-5f)
    }

    @Test fun forwardRayOfPose() {
        val turned = Pose3(Vector3(0f, 1f, 0f), Quaternion.axisAngle(Vector3.UP, (PI / 2).toFloat())).forwardRay()
        assertEquals(-1f, turned.direction.x, 1e-6f)
        assertEquals(1f, turned.origin.y, 0f)
        assertEquals(1f, Ray3(Vector3.ZERO, Vector3(0f, 0f, -5f)).direction.length, 1e-6f)
    }
}
