package io.github.alexskuznetsov.xrspatial.scene

import io.github.alexskuznetsov.xrspatial.Pose3
import io.github.alexskuznetsov.xrspatial.Quaternion
import io.github.alexskuznetsov.xrspatial.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan

/**
 * Layout against the X3 Pro's eye views: 640x480 per eye, fov left 0.2007 / right 0.2229 / up 0.1556 /
 * down 0.1620 rad (signs as the renderer uses them), eyes 3.25 cm apart and pitched 10.8 degrees down.
 */
class SceneLayoutTest {
    private val pitch = Quaternion.axisAngle(Vector3.RIGHT, (-10.8 * PI / 180).toFloat())
    private val eyes = listOf(Pose3(Vector3(-0.0325f, 0f, 0f), pitch), Pose3(Vector3(0.0325f, 0f, 0f), pitch))

    private fun view(head: Pose3, eye: Int) = Mat4.pose((head * eyes[eye]).inverse())

    private fun projection(near: Float = 0.05f, far: Float = 100f): FloatArray {
        val l = tan(-0.2007f); val r = tan(0.2229f); val u = tan(0.1556f); val d = tan(-0.1620f)
        return floatArrayOf(2 / (r - l), 0f, 0f, 0f, 0f, 2 / (u - d), 0f, 0f,
            (r + l) / (r - l), (u + d) / (u - d), -(far + near) / (far - near), -1f, 0f, 0f, -2 * far * near / (far - near), 0f)
    }

    /** Pixel (x right, y down) of a LOCAL point in one eye's 640x480 image, or null behind the eye. */
    private fun pixel(head: Pose3, eye: Int, p: Vector3): Pair<Float, Float>? {
        val clip = Mat4.transform(Mat4.multiply(projection(), view(head, eye)), p)
        if (clip[3] <= 0f) return null
        return (clip[0] / clip[3] + 1f) * 320f to (1f - clip[1] / clip[3]) * 240f
    }

    private fun layoutAt(head: Pose3, epoch: Int = 1) = SceneLayout().also { l -> for (e in 0..1) l.onEye(e, view(head, e), head, epoch) }

    private fun corners(pose: Pose3, w: Float, h: Float) =
        listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (a, b) -> pose.transform(Vector3(a * w / 2, b * h / 2, 0f)) }

    @Test fun eyePoseIsRecoveredFromTheViewMatrix() {
        val head = Pose3(Vector3(0.2f, 1.6f, -0.3f), Quaternion.axisAngle(Vector3(0.2f, 1f, 0.1f), 0.8f))
        val eye = Mat4.eyePoseFromView(view(head, 1))
        val expected = head * eyes[1]
        assertEquals(0f, (eye.position - expected.position).length, 1e-5f)
        assertEquals(0f, (eye.rotation.rotate(Vector3.FORWARD) - expected.rotation.rotate(Vector3.FORWARD)).length, 1e-5f)
    }

    @Test fun inFrontOfUserLandsInTheMiddleOfBothEyesAndFacesTheWearer() {
        val head = Pose3(Vector3(0f, 0f, 0f), Quaternion.axisAngle(Vector3.UP, 0.5f))
        val layout = layoutAt(head)
        val pose = layout.poseOf("card", Placement.InFrontOfUser(1.5f))
        assertEquals(1.5f, (pose.position - head.position).length, 1e-4f)
        for (eye in 0..1) {
            val (x, y) = pixel(head, eye, pose.position)!!
            assertTrue("eye $eye x=$x", abs(x - 320f) < 60f)  // only the eye offset and asymmetric fov move it
            assertTrue("eye $eye y=$y", abs(y - 240f) < 20f)  // on the display axis, not at the top edge
        }
        // The front (+Z) points at the head, the top edge stays horizontal (no roll).
        val front = pose.rotation.rotate(Vector3(0f, 0f, 1f))
        assertEquals(1f, front dot (head.position - pose.position).normalized(), 1e-4f)
        assertEquals(0f, pose.rotation.rotate(Vector3.RIGHT).y, 1e-5f)
    }

    @Test fun headForwardWouldPutTheCardAtTheTopEdge() {
        // Why the display axis matters: 1.5 m straight along the head's -Z is ~11 degrees above the image centre.
        val head = Pose3.IDENTITY
        val (_, y) = pixel(head, 0, Vector3(0f, 0f, -1.5f))!!
        assertTrue("y=$y", y < 30f)
    }

    @Test fun aDefaultSizedCardFitsTheSmallDisplay() {
        val head = Pose3.IDENTITY
        val pose = layoutAt(head).poseOf("card", Placement.InFrontOfUser(1.5f))
        for (eye in 0..1) for (c in corners(pose, 0.3f, 0.2f)) {
            val (x, y) = pixel(head, eye, c)!!
            assertTrue("eye $eye corner $x,$y", x in 0f..640f && y in 0f..480f)
        }
        // A 0.7 m wide panel at 1.5 m overflows the ~24 degree wide image.
        val wide = corners(pose, 0.7f, 0.2f).map { pixel(head, 1, it)!!.first }
        assertTrue(wide.any { it < 0f || it > 640f })
    }

    @Test fun textOfTwoCentimetresIsAboutTwentyPixelsAtOnePointFiveMetres() {
        val head = Pose3.IDENTITY
        val pose = layoutAt(head).poseOf("card", Placement.InFrontOfUser(1.5f))
        val top = pixel(head, 0, pose.transform(Vector3(0f, 0.01f, 0f)))!!.second
        val bottom = pixel(head, 0, pose.transform(Vector3(0f, -0.01f, 0f)))!!.second
        assertEquals(20f, bottom - top, 3f)
    }

    @Test fun worldLockedStaysWhileTheHeadMovesAndIsPlacedAgainInANewSession() {
        val start = Pose3.IDENTITY
        val layout = layoutAt(start, epoch = 1)
        val placed = layout.poseOf("card", Placement.InFrontOfUser())
        val moved = Pose3(Vector3(0.4f, -0.2f, -0.3f), Quaternion.axisAngle(Vector3.UP, 1.2f))
        for (e in 0..1) layout.onEye(e, view(moved, e), moved, 1)
        assertEquals(placed, layout.poseOf("card", Placement.InFrontOfUser()))
        // New session: LOCAL starts again at the head, the card comes back in front of it.
        for (e in 0..1) layout.onEye(e, view(moved, e), moved, 2)
        val again = layout.poseOf("card", Placement.InFrontOfUser())
        assertNotEquals(placed, again)
        assertEquals(1.5f, (again.position - moved.position).length, 1e-4f)
    }

    @Test fun recenterAndChangedPlacementPlaceAgain() {
        val layout = layoutAt(Pose3.IDENTITY)
        val first = layout.poseOf("card", Placement.InFrontOfUser())
        val turned = Pose3(rotation = Quaternion.axisAngle(Vector3.UP, 0.9f))
        for (e in 0..1) layout.onEye(e, view(turned, e), turned, 1)
        assertEquals(first, layout.poseOf("card", Placement.InFrontOfUser()))
        assertNotEquals(first, layout.poseOf("card", Placement.InFrontOfUser(2f)))  // new placement value
        layout.recenter()
        val again = layout.poseOf("card", Placement.InFrontOfUser(2f))
        assertTrue(again.position.x < -1f)  // turned left: now to the left of the start
    }

    @Test fun offsetMovesInTheCardsOwnFrame() {
        val layout = layoutAt(Pose3.IDENTITY)
        val centre = layout.poseOf("a", Placement.InFrontOfUser(1.5f))
        val right = layout.poseOf("b", Placement.InFrontOfUser(1.5f, Vector3(0.3f, 0f, 0f)))
        assertEquals(0.3f, (right.position - centre.position).length, 1e-5f)
        assertEquals(0.3f, (right.position - centre.position) dot centre.rotation.rotate(Vector3.RIGHT), 1e-5f)
    }

    @Test fun headLockedFollowsTheHeadAndInLocalIsFixed() {
        val layout = layoutAt(Pose3.IDENTITY)
        val fixed = Pose3(Vector3(0f, 0f, -2f))
        val turned = Pose3(Vector3(0.5f, 0f, 0f), Quaternion.axisAngle(Vector3.UP, 1.0f))
        for (e in 0..1) layout.onEye(e, view(turned, e), turned, 1)
        assertEquals(fixed, layout.poseOf("fixed", Placement.InLocal(fixed)))
        val hud = layout.poseOf("hud", Placement.HeadLocked(1f))
        for (eye in 0..1) {
            val (x, y) = pixel(turned, eye, hud.position)!!
            assertTrue("eye $eye $x,$y", abs(x - 320f) < 80f && abs(y - 240f) < 20f)
        }
    }
}
