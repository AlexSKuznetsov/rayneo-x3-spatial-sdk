package io.github.alexskuznetsov.xrspatial.samplepointer

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.TextView
import io.github.alexskuznetsov.xrspatial.PoseListener
import io.github.alexskuznetsov.xrspatial.PoseSample
import io.github.alexskuznetsov.xrspatial.SampleQuality
import io.github.alexskuznetsov.xrspatial.SpatialError
import io.github.alexskuznetsov.xrspatial.TrackingConfidence
import io.github.alexskuznetsov.xrspatial.TrackingState
import io.github.alexskuznetsov.xrspatial.Vector3
import io.github.alexskuznetsov.xrspatial.scene.Placement
import io.github.alexskuznetsov.xrspatial.scene.PointerListener
import io.github.alexskuznetsov.xrspatial.scene.PointerStyle
import io.github.alexskuznetsov.xrspatial.scene.SpatialHost
import io.github.alexskuznetsov.xrspatial.scene.SpatialPanel
import io.github.alexskuznetsov.xrspatial.scene.SpatialPointer
import io.github.alexskuznetsov.xrspatial.scene.SpatialScene
import io.github.alexskuznetsov.xrspatial.scene.SpatialText
import kotlin.math.abs

/**
 * A card with two buttons and a pointer from the centre of the view. What the touchpad does is this app's own
 * choice; the SDK only draws the pointer and says what it points at:
 *
 * - tap: click the button under the pointer;
 * - swipe forward (along the temple, towards the eye): switch between dot and ray;
 * - swipe back (towards the ear): switch the pointer off and on;
 * - two-finger tap: place the card in front again.
 */
class PointerActivity : Activity() {
    private val scene = SpatialScene()
    private val pointer = SpatialPointer()
    private lateinit var card: SpatialPanel
    private lateinit var info: SpatialText
    private var target = "none"

    // Touchpad gesture being tracked: start position, time and most pointers seen.
    private var downX = 0f
    private var downTime = 0L
    private var maxPointers = 0

    @SuppressLint("InflateParams")  // the card is drawn into a texture, it has no parent view
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = layoutInflater.inflate(R.layout.pointer_card, null)
        val status = view.findViewById<TextView>(R.id.status)
        view.findViewById<TextView>(R.id.inspect).setOnClickListener { status.text = "Inspected ✓" }
        view.findViewById<TextView>(R.id.reset).setOnClickListener { status.text = "Not inspected" }
        card = scene.add(SpatialPanel(view, widthMeters = 0.34f, placement = Placement.InFrontOfUser(1.5f)))
        info = scene.add(SpatialText(this, "", widthMeters = 0.34f, textSizeMeters = 0.013f,
            placement = Placement.InFrontOfUser(1.5f, offset = Vector3(0f, -0.13f, 0f))))
        pointer.listener = PointerListener { hit ->
            target = when (hit?.node) { null -> "none"; card -> "card"; else -> "text" }
            showInfo()
        }
        scene.pointer = pointer
        showInfo()
        SpatialHost.attach(this, scene, listener = object : PoseListener {
            override fun onSample(sample: PoseSample, quality: SampleQuality) = Unit
            override fun onStateChanged(state: TrackingState) { Log.i(TAG, "state $state") }
            override fun onConfidenceChanged(confidence: TrackingConfidence) { Log.i(TAG, "confidence $confidence") }
            override fun onError(error: SpatialError) { Log.i(TAG, "error ${error.operation} ${error.resultCode}") }
            override fun onStopped(reason: String) { Log.i(TAG, "stopped $reason") }
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downTime = event.eventTime; maxPointers = 1 }
            MotionEvent.ACTION_POINTER_DOWN -> maxPointers = maxOf(maxPointers, event.pointerCount)
            MotionEvent.ACTION_UP -> gesture(event.x - downX, event.eventTime - downTime)
        }
        return true
    }

    // Thresholds from the touchpad as observed on the X3 Pro: taps ~60-90 ms without movement, swipes run
    // across most of the 0..1280 range in ~100-130 ms.
    private fun gesture(dx: Float, durationMs: Long) {
        val kind = when {
            maxPointers >= 2 -> "two-finger"
            dx > SWIPE_PX -> "swipe-forward"
            dx < -SWIPE_PX -> "swipe-back"
            abs(dx) < TAP_PX && durationMs < TAP_MS -> "tap"
            else -> "ignored"
        }
        Log.i(TAG, "gesture $kind dx=${dx.toInt()} ms=$durationMs pointers=$maxPointers")
        when (kind) {
            "two-finger" -> scene.recenter()
            "swipe-forward" -> pointer.style = if (pointer.style is PointerStyle.Dot) PointerStyle.Ray() else PointerStyle.Dot()
            "swipe-back" -> pointer.enabled = !pointer.enabled
            "tap" -> {
                val clicked = pointer.hit?.let { card.performClickAt(it) }
                Log.i(TAG, "tap hit=${pointer.hit != null} clicked=${clicked?.id?.let { resources.getResourceEntryName(it) }}")
            }
            else -> return
        }
        showInfo()
    }

    private fun showInfo() {
        val style = if (pointer.style is PointerStyle.Dot) "dot" else "ray"
        val state = if (pointer.enabled) "$style, on $target" else "off"
        info.text = "Pointer: $state\nTap: click   Swipe fwd: dot/ray   Swipe back: on/off   2 fingers: recenter"
        Log.i(TAG, "pointer $state")
    }

    private companion object {
        const val TAG = "XrSpatialSample"
        const val SWIPE_PX = 300f
        const val TAP_PX = 60f
        const val TAP_MS = 300L
    }
}
