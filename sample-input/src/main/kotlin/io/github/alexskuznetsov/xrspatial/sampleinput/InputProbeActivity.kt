package io.github.alexskuznetsov.xrspatial.sampleinput

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.alexskuznetsov.xrspatial.HandListener
import io.github.alexskuznetsov.xrspatial.HandSample
import io.github.alexskuznetsov.xrspatial.PoseListener
import io.github.alexskuznetsov.xrspatial.PoseSample
import io.github.alexskuznetsov.xrspatial.SampleQuality
import io.github.alexskuznetsov.xrspatial.TrackingState
import io.github.alexskuznetsov.xrspatial.Vector3
import io.github.alexskuznetsov.xrspatial.XrSpatialSession
import io.github.alexskuznetsov.xrspatial.scene.Placement
import io.github.alexskuznetsov.xrspatial.scene.SpatialHost
import io.github.alexskuznetsov.xrspatial.scene.SpatialScene
import io.github.alexskuznetsov.xrspatial.scene.SpatialText
import java.util.Locale

/**
 * Diagnostic: shows and logs (tag `InputProbe`) every input event the Activity receives during an XR session
 * (keys, touch, generic motion, trackball), and, when started with `--ez hands true`, what the experimental
 * `XR_EXT_hand_tracking` delivers. Nothing is interpreted: events are logged as Android reports them.
 *
 * BACK is logged and consumed so that a swipe mapped to BACK does not close the probe.
 */
class InputProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var host: SpatialHost
    private lateinit var lastEvent: SpatialText
    private lateinit var handText: SpatialText
    private val counts = linkedMapOf<String, Int>()
    private var events = 0
    private var lastShownMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hands = intent.getBooleanExtra(EXTRA_HANDS, false)
        val scene = SpatialScene()
        scene.add(SpatialText(this, "Input probe: tap, swipe, hold the touchpad", widthMeters = 0.4f, textSizeMeters = 0.016f,
            placement = Placement.InFrontOfUser(1.5f, offset = Vector3(0f, 0.12f, 0f))))
        lastEvent = scene.add(SpatialText(this, "No input yet", widthMeters = 0.4f, textSizeMeters = 0.014f,
            placement = Placement.InFrontOfUser(1.5f)))
        handText = scene.add(SpatialText(this, if (hands) "Hands: starting" else "Hands: off", widthMeters = 0.4f,
            textSizeMeters = 0.014f, placement = Placement.InFrontOfUser(1.5f, offset = Vector3(0f, -0.12f, 0f))))
        host = SpatialHost.attach(this, scene, XrSpatialSession.Config(appName = "xrspatial-input", handTracking = hands),
            object : PoseListener {
                override fun onSample(sample: PoseSample, quality: SampleQuality) = Unit
                override fun onStateChanged(state: TrackingState) { Log.i(TAG, "state $state") }
                override fun onStopped(reason: String) {
                    Log.i(TAG, "stopped $reason events=$events counts=$counts")
                    host.session.diagnostics().forEach { (key, value) -> Log.i(TAG, "diagnostics $key=$value") }
                }
            })
        if (hands) host.session.handListener = HandProbe()
        Log.i(TAG, "start hands=$hands")
        logInputDevices()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.action) { KeyEvent.ACTION_DOWN -> "DOWN"; KeyEvent.ACTION_UP -> "UP"; else -> "action${event.action}" }
        record("key", event, "$action ${KeyEvent.keyCodeToString(event.keyCode)}" +
            " scan=${event.scanCode} repeat=${event.repeatCount} long=${event.isLongPress}")
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        record("touch", event, motion(event))
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        record("generic", event, motion(event))
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTrackballEvent(event: MotionEvent): Boolean {
        record("trackball", event, motion(event))
        return super.dispatchTrackballEvent(event)
    }

    private fun motion(e: MotionEvent): String =
        "${MotionEvent.actionToString(e.actionMasked)} pointers=${e.pointerCount} x=${f(e.x)} y=${f(e.y)}" +
            " rawX=${f(e.rawX)} rawY=${f(e.rawY)} history=${e.historySize}" +
            " scrollH=${f(e.getAxisValue(MotionEvent.AXIS_HSCROLL))} scrollV=${f(e.getAxisValue(MotionEvent.AXIS_VSCROLL))}" +
            " relX=${f(e.getAxisValue(MotionEvent.AXIS_RELATIVE_X))} relY=${f(e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y))}" +
            " buttons=${e.buttonState}"

    private fun record(kind: String, event: InputEvent, detail: String) {
        events++
        counts[kind] = (counts[kind] ?: 0) + 1
        val device = event.device
        val line = "$kind $detail source=0x${Integer.toHexString(event.source)} device=${device?.name ?: "none"}" +
            " t=${event.eventTime} now=${SystemClock.uptimeMillis()}"
        Log.i(TAG, line)
        // Each text change re-renders the panel: show moves at most 10 times a second, everything else at once.
        val now = SystemClock.uptimeMillis()
        if (detail.startsWith("ACTION_MOVE") && now - lastShownMs < 100) return
        lastShownMs = now
        lastEvent.text = "#$events $kind $detail"
    }

    private fun logInputDevices() {
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            Log.i(TAG, "inputDevice name=${d.name} sources=0x${Integer.toHexString(d.sources)} keyboard=${d.keyboardType} virtual=${d.isVirtual}")
        }
    }

    /** Logs each hand's active changes, a line per second while active, and shows the state about 5 times a second. */
    private inner class HandProbe : HandListener {
        private val wasActive = BooleanArray(2)
        private val activeFrames = LongArray(2)
        private var frames = 0L
        private var lastShown = 0L

        override fun onHands(left: HandSample, right: HandSample) {  // render thread
            frames++
            for ((i, h) in listOf(left, right).withIndex()) {
                if (h.active) activeFrames[i]++
                if (h.active != wasActive[i] || (h.active && activeFrames[i] % 60 == 1L) || frames == 1L)
                    Log.i(TAG, "hand ${h.hand} frame=${h.frameIndex} result=${h.locateResult} active=${h.active}" +
                        " palmFlags=${h.palmFlags} palm=${v(h.palm)} tipFlags=${h.indexTipFlags} tip=${v(h.indexTip)}")
                wasActive[i] = h.active
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastShown < 200) return
            lastShown = now
            val text = "Hands: L ${show(left)} | R ${show(right)} | frames $frames"
            main.post { handText.text = text }
        }

        private fun show(h: HandSample) = when {
            h.locateResult != 0 -> "error ${h.locateResult}"
            !h.active -> "not tracked"
            h.palmValid -> "palm ${v(h.palm)}"
            else -> "active, palm invalid"
        }
    }

    private companion object {
        const val TAG = "InputProbe"
        const val EXTRA_HANDS = "hands"
        fun f(value: Float) = String.format(Locale.ROOT, "%.1f", value)
        fun v(p: Vector3) = String.format(Locale.ROOT, "(%.3f %.3f %.3f)", p.x, p.y, p.z)
    }
}
