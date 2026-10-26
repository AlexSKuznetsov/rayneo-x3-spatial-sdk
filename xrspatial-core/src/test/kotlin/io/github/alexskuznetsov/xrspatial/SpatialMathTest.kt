package io.github.alexskuznetsov.xrspatial

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class SpatialMathTest {
    private fun assertNear(expected: Vector3, actual: Vector3, tolerance: Float = 1e-5f) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.z, actual.z, tolerance)
    }

    @Test fun yawLeftTurnsForwardToMinusX() {
        val left90 = Quaternion.axisAngle(Vector3.UP, (PI / 2).toFloat())
        assertNear(Vector3(-1f, 0f, 0f), left90.rotate(Vector3.FORWARD))
        assertNear(Vector3.UP, left90.rotate(Vector3.UP))
    }

    @Test fun productAppliesRightOperandFirst() {
        val yaw = Quaternion.axisAngle(Vector3.UP, (PI / 2).toFloat())
        val pitchDown = Quaternion.axisAngle(Vector3.RIGHT, (-PI / 2).toFloat())
        // Pitch down first (forward -> down), then yaw (down stays down).
        assertNear(Vector3(0f, -1f, 0f), (yaw * pitchDown).rotate(Vector3.FORWARD))
        // Yaw first (forward -> -X), then pitch about X leaves -X.
        assertNear(Vector3(-1f, 0f, 0f), (pitchDown * yaw).rotate(Vector3.FORWARD))
    }

    @Test fun fromBasisRoundTripsEveryBranch() {
        val rotations = listOf(
            Quaternion.IDENTITY,
            Quaternion.axisAngle(Vector3.RIGHT, 3.0f),
            Quaternion.axisAngle(Vector3.UP, 3.0f),
            Quaternion.axisAngle(Vector3(0f, 0f, 1f), 3.0f),
            Quaternion.axisAngle(Vector3(1f, 2f, -3f), 1.2f),
        )
        for (q in rotations) {
            val back = Quaternion.fromBasis(q.rotate(Vector3.RIGHT), q.rotate(Vector3.UP), q.rotate(Vector3(0f, 0f, 1f)))
            for (v in listOf(Vector3(1f, 2f, 3f), Vector3(-0.5f, 0.1f, 0.9f))) assertNear(q.rotate(v), back.rotate(v))
        }
    }

    @Test fun facingPointsPlusZAtTheViewerWithoutRoll() {
        val toViewer = Vector3(0.3f, 0.4f, 1f)
        val q = Quaternion.facing(toViewer)
        assertNear(toViewer.normalized(), q.rotate(Vector3(0f, 0f, 1f)))
        assertEquals(0f, q.rotate(Vector3.RIGHT).y, 1e-6f)  // local X stays horizontal: no roll
        assertNear(Vector3.UP, Quaternion.facing(Vector3.UP).rotate(Vector3(0f, 0f, 1f)))  // degenerate up: still a valid rotation
    }

    @Test fun poseCompositionAndInverse() {
        val head = Pose3(Vector3(1f, 1.6f, 0f), Quaternion.axisAngle(Vector3.UP, 0.7f))
        val child = Pose3(Vector3(0f, 0f, -2f), Quaternion.axisAngle(Vector3.RIGHT, -0.2f))
        val world = head * child
        assertNear(head.transform(Vector3(0f, 0f, -2f)), world.position)
        val identity = head.inverse() * head
        assertNear(Vector3.ZERO, identity.position)
        assertNear(Vector3.FORWARD, identity.rotation.rotate(Vector3.FORWARD))
    }

    @Test fun sampleExposesRawPose() {
        val s = PoseSample(1, 0, 0, 0, 15, 0.1f, 0.2f, 0.3f, 0f, 0f, 0f, 1f)
        assertEquals(Pose3(Vector3(0.1f, 0.2f, 0.3f), Quaternion.IDENTITY), s.pose)
    }
}
