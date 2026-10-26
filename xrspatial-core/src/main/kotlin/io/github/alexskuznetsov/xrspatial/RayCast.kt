package io.github.alexskuznetsov.xrspatial

import kotlin.math.abs

/** A ray from [origin] along [direction] (normalised on construction), in OpenXR coordinates. */
class Ray3(val origin: Vector3, direction: Vector3) {
    val direction: Vector3 = direction.normalized()

    /** The point [distance] metres along the ray. */
    fun at(distance: Float): Vector3 = origin + direction * distance

    /** Where this ray meets the front (+Z side) of a [width] x [height] rectangle centred at [pose], or null. */
    fun hitRect(pose: Pose3, width: Float, height: Float): RectHit? {
        if (width <= 0f || height <= 0f) return null
        val normal = pose.rotation.rotate(Vector3(0f, 0f, 1f))
        val facing = direction dot normal
        if (facing > -EPSILON) return null  // parallel, or seen from behind (quads are one-sided)
        val distance = ((pose.position - origin) dot normal) / facing
        if (distance < 0f) return null
        val point = at(distance)
        val local = pose.inverse().transform(point)
        if (abs(local.x) > width / 2 || abs(local.y) > height / 2) return null
        return RectHit(distance, point, local.x / width + 0.5f, 0.5f - local.y / height)
    }

    override fun toString() = "Ray3(origin=$origin, direction=$direction)"

    private companion object { const val EPSILON = 1e-6f }
}

/**
 * A ray hit on a rectangle: [distance] along the ray (metres), the [point] in the ray's space, and the position
 * on the rectangle as [u] (0 left .. 1 right) and [v] (0 top .. 1 bottom), like image pixels.
 */
data class RectHit(val distance: Float, val point: Vector3, val u: Float, val v: Float)

/** The ray from this pose's position along its forward (-Z) axis. */
fun Pose3.forwardRay(): Ray3 = Ray3(position, rotation.rotate(Vector3.FORWARD))
