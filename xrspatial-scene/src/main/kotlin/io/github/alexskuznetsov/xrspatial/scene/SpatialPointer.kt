package io.github.alexskuznetsov.xrspatial.scene

import android.graphics.Color
import io.github.alexskuznetsov.xrspatial.Pose3
import io.github.alexskuznetsov.xrspatial.Ray3
import io.github.alexskuznetsov.xrspatial.RectHit
import io.github.alexskuznetsov.xrspatial.Vector3
import io.github.alexskuznetsov.xrspatial.forwardRay

/** How a [SpatialPointer] looks. */
sealed interface PointerStyle {
    /**
     * A round dot where the pointer meets content (or [distanceWithoutHit] metres along it), [sizeDegrees] of the
     * view wide at any distance. Drawn at the depth of what it points at, so it does not double in stereo.
     */
    data class Dot(
        val sizeDegrees: Float = 0.6f,
        val color: Int = Color.WHITE,
        val distanceWithoutHit: Float = 1.5f,
    ) : PointerStyle

    /** A visible beam from the source to the hit (or [maxLength] metres), with an optional dot at its end. */
    data class Ray(
        val widthMeters: Float = 0.003f,
        val color: Int = 0xFF4FC3F7.toInt(),
        val maxLength: Float = 3f,
        val endDot: Dot? = Dot(sizeDegrees = 0.5f),
    ) : PointerStyle
}

/** Where a [SpatialPointer] comes from. */
sealed interface PointerSource {
    /**
     * The centre of the wearer's view (the display axis, which on the X3 Pro looks about 11 degrees down). Hits
     * follow the gaze. [rayOrigin] (metres in the display frame: +X right, +Y up, -Z ahead) is only where a
     * visible [PointerStyle.Ray] starts, since a beam from the eyes would be seen end-on.
     */
    data class Gaze(val rayOrigin: Vector3 = Vector3(0.1f, -0.15f, 0f)) : PointerSource

    /**
     * A pose in LOCAL given by the app every frame (called on the render thread), e.g. from a phone or a ring
     * tracked by the app; the pointer runs along its -Z axis. Return null to hide the pointer for that frame.
     */
    class Custom(val pose: () -> Pose3?) : PointerSource
}

/**
 * What a pointer points at: [node], the [point] in LOCAL, the [distance] along the pointer (metres), the position
 * on the node as [u] / [v] (0..1 from the top-left corner) and as [pixelX] / [pixelY] in the node's bitmap, which
 * for a [SpatialPanel] are its view's coordinates.
 *
 * It was found in the frame drawn with the head pose [sessionEpoch] / [frameIndex], received at [hostReceiptNs]
 * (CLOCK_MONOTONIC, the clock of `System.nanoTime()` on Android). An app that wants to refuse old hits can compare
 * `System.nanoTime() - hostReceiptNs` with its own limit; the scene already drops hits when frames stop (see
 * [SpatialPointer.hit]).
 */
data class PointerHit(
    val node: SpatialQuad,
    val point: Vector3,
    val distance: Float,
    val u: Float,
    val v: Float,
    val pixelX: Float,
    val pixelY: Float,
    val sessionEpoch: Int = 0,
    val frameIndex: Long = 0L,
    val hostReceiptNs: Long = 0L,
)

/**
 * Called on the main thread when the pointer's target changes to another node, to none, or back. Changes are
 * coalesced: when the main thread is busy, only the latest one is delivered, not a queue of old targets.
 */
fun interface PointerListener {
    fun onTargetChanged(hit: PointerHit?)
}

/**
 * A pointer drawn by a [SpatialScene] (set [SpatialScene.pointer]): a [PointerStyle.Dot] or a [PointerStyle.Ray]
 * from a [PointerSource]. It finds the nearest visible node it points at every frame, but does nothing with it:
 * what a tap or a dwell means is up to the app (see [SpatialPanel.performClickAt]).
 *
 * All properties may be changed from any thread; changes apply from the next frame.
 */
class SpatialPointer(
    style: PointerStyle = PointerStyle.Dot(),
    source: PointerSource = PointerSource.Gaze(),
) {
    /** Off: nothing is drawn, nothing is hit, [hit] is null. */
    @Volatile var enabled: Boolean = true
        set(value) { field = value; if (!value) hit = null }
    @Volatile var style: PointerStyle = style
    @Volatile var source: PointerSource = source
    @Volatile var listener: PointerListener? = null

    /**
     * The latest target, updated every drawn frame. Null when there is none, when the pointer is disabled, right
     * after [SpatialScene.recenter], and whenever no frame is drawn with a usable pose: before tracking, during the
     * warm-up after a rejected pose, and while the session is paused or stopped. So a tap never clicks what the
     * pointer was on before tracking was lost.
     */
    @Volatile var hit: PointerHit? = null
        internal set
}

/** Pure per-frame pointer geometry (no GL, no Android), unit-tested. */
internal object PointerCast {
    class Target(val key: Any, val pose: Pose3, val width: Float, val height: Float)

    /** [beamStart] -> [end] is what is drawn; [target] / [hit] what is pointed at (null: nothing). */
    class Result(val pick: Ray3, val beamStart: Vector3, val end: Vector3, val target: Target?, val hit: RectHit?)

    fun cast(source: PointerSource, style: PointerStyle, display: Pose3, targets: List<Target>): Result? {
        val (pick, beamStart) = when (source) {
            is PointerSource.Gaze -> display.forwardRay() to display.transform(source.rayOrigin)
            is PointerSource.Custom -> (source.pose() ?: return null).let { it.forwardRay() to it.position }
        }
        var best: Target? = null
        var bestHit: RectHit? = null
        for (t in targets) {
            val h = pick.hitRect(t.pose, t.width, t.height) ?: continue
            if (bestHit == null || h.distance < bestHit.distance) { best = t; bestHit = h }
        }
        val reach = when (style) {
            is PointerStyle.Dot -> style.distanceWithoutHit
            is PointerStyle.Ray -> style.maxLength
        }
        val end = bestHit?.point ?: pick.at(reach)
        return Result(pick, beamStart, end, best, bestHit)
    }
}
