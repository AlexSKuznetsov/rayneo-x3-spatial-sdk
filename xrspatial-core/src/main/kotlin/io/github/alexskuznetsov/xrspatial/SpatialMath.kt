package io.github.alexskuznetsov.xrspatial

import kotlin.math.sqrt

/** A point or direction in OpenXR coordinates (metres; right-handed, +X right, +Y up, -Z forward). */
data class Vector3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vector3) = Vector3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vector3) = Vector3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vector3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vector3(-x, -y, -z)
    infix fun dot(o: Vector3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vector3) = Vector3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Float get() = sqrt(this dot this)
    fun normalized(): Vector3 = length.let { if (it > 0f) this * (1f / it) else this }

    companion object {
        val ZERO = Vector3(0f, 0f, 0f)
        val RIGHT = Vector3(1f, 0f, 0f)
        val UP = Vector3(0f, 1f, 0f)
        /** OpenXR forward is -Z. */
        val FORWARD = Vector3(0f, 0f, -1f)
    }
}

/** Unit rotation quaternion (x, y, z, w), OpenXR order. */
data class Quaternion(val x: Float, val y: Float, val z: Float, val w: Float) {
    /** Rotation `this` after [o] (apply [o] first). */
    operator fun times(o: Quaternion) = Quaternion(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    fun conjugate() = Quaternion(-x, -y, -z, w)

    fun rotate(v: Vector3): Vector3 {
        val u = Vector3(x, y, z)
        val t = (u cross v) * 2f
        return v + t * w + (u cross t)
    }

    val norm: Float get() = sqrt(x * x + y * y + z * z + w * w)
    fun normalized(): Quaternion = norm.let { if (it > 0f) Quaternion(x / it, y / it, z / it, w / it) else IDENTITY }

    companion object {
        val IDENTITY = Quaternion(0f, 0f, 0f, 1f)

        fun axisAngle(axis: Vector3, radians: Float): Quaternion {
            val a = axis.normalized()
            val s = kotlin.math.sin(radians / 2)
            return Quaternion(a.x * s, a.y * s, a.z * s, kotlin.math.cos(radians / 2))
        }

        /** Rotation whose local X/Y/Z axes are the given orthonormal [x], [y], [z] in the parent frame. */
        fun fromBasis(x: Vector3, y: Vector3, z: Vector3): Quaternion {
            val trace = x.x + y.y + z.z
            val q = when {
                trace > 0f -> {
                    val s = sqrt(trace + 1f) * 2f
                    Quaternion((y.z - z.y) / s, (z.x - x.z) / s, (x.y - y.x) / s, 0.25f * s)
                }
                x.x > y.y && x.x > z.z -> {
                    val s = sqrt(1f + x.x - y.y - z.z) * 2f
                    Quaternion(0.25f * s, (y.x + x.y) / s, (z.x + x.z) / s, (y.z - z.y) / s)
                }
                y.y > z.z -> {
                    val s = sqrt(1f + y.y - x.x - z.z) * 2f
                    Quaternion((y.x + x.y) / s, 0.25f * s, (z.y + y.z) / s, (z.x - x.z) / s)
                }
                else -> {
                    val s = sqrt(1f + z.z - x.x - y.y) * 2f
                    Quaternion((z.x + x.z) / s, (z.y + y.z) / s, 0.25f * s, (x.y - y.x) / s)
                }
            }
            return q.normalized()
        }

        /**
         * Rotation whose local +Z points along [back] (so its front, -Z, looks the other way) with no roll
         * relative to [up]. A quad whose front face is +Z, rotated by `facing(viewer - quad)`, faces the viewer.
         */
        fun facing(back: Vector3, up: Vector3 = Vector3.UP): Quaternion {
            val z = back.normalized()
            var x = up cross z
            if (x.length < 1e-6f) x = Vector3.RIGHT  // looking straight up/down: any roll will do
            x = x.normalized()
            return fromBasis(x, z cross x, z)
        }
    }
}

/** A rigid transform: child-to-parent rotation then translation, as an OpenXR XrPosef. */
data class Pose3(val position: Vector3 = Vector3.ZERO, val rotation: Quaternion = Quaternion.IDENTITY) {
    /** Pose of [child] (given in this pose's frame) in the parent frame. */
    operator fun times(child: Pose3) = Pose3(position + rotation.rotate(child.position), rotation * child.rotation)
    fun transform(point: Vector3): Vector3 = position + rotation.rotate(point)
    fun inverse(): Pose3 = rotation.conjugate().let { r -> Pose3(-r.rotate(position), r) }

    companion object { val IDENTITY = Pose3() }
}

/** The head pose of a raw sample as math types (raw float values, not normalised; check quality first). */
val PoseSample.pose: Pose3 get() = Pose3(Vector3(px, py, pz), Quaternion(qx, qy, qz, qw))
