package io.github.alexskuznetsov.xrspatial.scene

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewTreeObserver
import android.view.WindowManager
import io.github.alexskuznetsov.xrspatial.PoseListener
import io.github.alexskuznetsov.xrspatial.PoseSample
import io.github.alexskuznetsov.xrspatial.SampleQuality
import io.github.alexskuznetsov.xrspatial.SpatialError
import io.github.alexskuznetsov.xrspatial.TrackingConfidence
import io.github.alexskuznetsov.xrspatial.TrackingState
import io.github.alexskuznetsov.xrspatial.XrSpatialSession

/**
 * Runs a [SpatialScene] in an Activity with no further code: it makes the Activity's content a full-screen
 * SurfaceView, keeps the display on, and starts and stops the [XrSpatialSession] with the Activity's
 * lifecycle, window focus and surface. Call [attach] once from `onCreate`.
 *
 * ```
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     val scene = SpatialScene()
 *     scene.add(SpatialText(this, "Hello", widthMeters = 0.3f))
 *     SpatialHost.attach(this, scene)
 * }
 * ```
 */
class SpatialHost private constructor(
    private val activity: Activity,
    val scene: SpatialScene,
    config: XrSpatialSession.Config,
    private val listener: PoseListener?,
) {
    val surfaceView = SurfaceView(activity)
    /** The underlying session, for [XrSpatialSession.diagnostics], HUD text or the latest pose. */
    val session = XrSpatialSession(activity, surfaceView, config)

    private val gate = HostGate(activity)

    // Registered on the Activity itself (Activity.registerActivityLifecycleCallbacks), so only its own events
    // arrive; the gate still ignores any other Activity.
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) { if (gate.onResumed(a)) maybeStart() }
        override fun onActivityPaused(a: Activity) { if (gate.onPaused(a)) session.stop() }
        override fun onActivityDestroyed(a: Activity) {
            if (!gate.isOwner(a)) return
            session.stop()
            activity.unregisterActivityLifecycleCallbacks(this)
        }
        override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(a: Activity) = Unit
        override fun onActivityStopped(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) = Unit
    }

    private val focus = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        session.onWindowFocusChanged(hasFocus)
        maybeStart()
    }

    private val forward = object : PoseListener {
        override fun onSample(sample: PoseSample, quality: SampleQuality) { listener?.onSample(sample, quality) }
        override fun onConfidenceChanged(confidence: TrackingConfidence) { listener?.onConfidenceChanged(confidence) }
        override fun onStateChanged(state: TrackingState) { listener?.onStateChanged(state) }
        override fun onError(error: SpatialError) {
            Log.e(TAG, "session error: ${error.message} operation=${error.operation} result=${error.resultCode}")
            listener?.onError(error)
        }
        override fun onStopped(reason: String) {
            val restart = gate.onStopped(reason)
            listener?.onStopped(reason)
            if (restart) maybeStart()  // e.g. resumed again while the previous session was still stopping
        }
    }

    private fun install() {
        // The glasses switch the display off after ~30 s without touch input, which pauses the app.
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { gate.onSurface(true); maybeStart() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { gate.onSurface(false); session.stop() }
        })
        surfaceView.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        activity.setContentView(surfaceView)
        activity.registerActivityLifecycleCallbacks(lifecycle)
    }

    // The runtime needs a resumed, focused Activity with a valid surface.
    private fun maybeStart() {
        if (!gate.shouldStart(activity.hasWindowFocus())) return
        session.start(scene.renderer, forward).onFailure { error ->
            Log.e(TAG, "start failed: $error")
            gate.onStartFailed()
        }
    }

    companion object {
        private const val TAG = "SpatialHost"

        /** Hosts [scene] in [activity]; call from `onCreate`, on the main thread. */
        @JvmStatic
        @JvmOverloads
        fun attach(
            activity: Activity,
            scene: SpatialScene,
            config: XrSpatialSession.Config = XrSpatialSession.Config(),
            listener: PoseListener? = null,
        ): SpatialHost = SpatialHost(activity, scene, config, listener).also { it.install() }
    }
}
