package io.github.alexskuznetsov.xrspatial.scene

import io.github.alexskuznetsov.xrspatial.Pose3
import io.github.alexskuznetsov.xrspatial.Quaternion
import io.github.alexskuznetsov.xrspatial.Vector3

/** Column-major 4x4 helpers (the layout of `EyeFrame.view/projection` and `glUniformMatrix4fv`). Pure Kotlin. */
internal object Mat4 {
    /** Model matrix of [pose] with a scale applied first. */
    fun pose(p: Pose3, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f, out: FloatArray = FloatArray(16)): FloatArray {
        val x = p.rotation.rotate(Vector3.RIGHT)
        val y = p.rotation.rotate(Vector3.UP)
        val z = p.rotation.rotate(Vector3(0f, 0f, 1f))
        out[0] = x.x * sx; out[1] = x.y * sx; out[2] = x.z * sx; out[3] = 0f
        out[4] = y.x * sy; out[5] = y.y * sy; out[6] = y.z * sy; out[7] = 0f
        out[8] = z.x * sz; out[9] = z.y * sz; out[10] = z.z * sz; out[11] = 0f
        out[12] = p.position.x; out[13] = p.position.y; out[14] = p.position.z; out[15] = 1f
        return out
    }

    fun multiply(a: FloatArray, b: FloatArray, out: FloatArray = FloatArray(16)): FloatArray {
        for (c in 0 until 4) for (r in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + r] * b[c * 4 + k]
            out[c * 4 + r] = sum
        }
        return out
    }

    /** Homogeneous transform of a point: (x, y, z, w). */
    fun transform(m: FloatArray, v: Vector3) = FloatArray(4) { r -> m[r] * v.x + m[4 + r] * v.y + m[8 + r] * v.z + m[12 + r] }

    /** The eye's pose in LOCAL from a rigid view matrix (LOCAL -> eye). */
    fun eyePoseFromView(view: FloatArray): Pose3 {
        val r0 = Vector3(view[0], view[4], view[8])
        val r1 = Vector3(view[1], view[5], view[9])
        val r2 = Vector3(view[2], view[6], view[10])
        val position = -(r0 * view[12] + r1 * view[13] + r2 * view[14])
        return Pose3(position, Quaternion.fromBasis(r0, r1, r2))
    }
}

/**
 * Resolves [Placement]s to poses in LOCAL, per tracking session. Lives on the render thread.
 *
 * The display axis comes from the runtime's eye views (their rotation relative to the head), so on the
 * X3 Pro, where the eyes look about 11 degrees down, "in front" means where the wearer actually sees.
 */
internal class SceneLayout {
    private var epoch = Int.MIN_VALUE
    private val eyeInView = arrayOfNulls<Quaternion>(2)
    private val placed = HashMap<Any, Pair<Placement, Pose3>>()

    /** Head position with the display axis rotation, in LOCAL. */
    var display = Pose3.IDENTITY
        private set

    /** Call for every eye before [poseOf]; a new [sessionEpoch] drops all world-locked placements. */
    fun onEye(eye: Int, view: FloatArray, head: Pose3, sessionEpoch: Int) {
        if (sessionEpoch != epoch) {
            epoch = sessionEpoch
            placed.clear()
            eyeInView.fill(null)
        }
        eyeInView[eye.coerceIn(0, 1)] = (head.inverse() * Mat4.eyePoseFromView(view)).rotation
        val a = eyeInView[0] ?: eyeInView[1]!!
        val b = eyeInView[1] ?: a
        val sameHemisphere = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w >= 0f
        val bb = if (sameHemisphere) b else Quaternion(-b.x, -b.y, -b.z, -b.w)
        val axis = Quaternion(a.x + bb.x, a.y + bb.y, a.z + bb.z, a.w + bb.w).normalized()
        display = Pose3(head.position, head.rotation * axis)
    }

    /** Places world-locked content in front again on the next [poseOf]. */
    fun recenter() = placed.clear()

    fun poseOf(key: Any, placement: Placement): Pose3 = when (placement) {
        is Placement.InLocal -> placement.pose
        is Placement.HeadLocked ->
            display * Pose3(Vector3(placement.offset.x, placement.offset.y, placement.offset.z - placement.distance))
        is Placement.InFrontOfUser -> {
            val previous = placed[key]
            if (previous != null && previous.first == placement) previous.second
            else inFront(placement).also { placed[key] = placement to it }
        }
    }

    private fun inFront(p: Placement.InFrontOfUser): Pose3 {
        val centre = display.transform(Vector3(0f, 0f, -p.distance))
        val rotation = Quaternion.facing(display.position - centre)
        return Pose3(centre + rotation.rotate(p.offset), rotation)
    }
}
