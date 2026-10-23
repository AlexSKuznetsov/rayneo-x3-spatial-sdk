package io.github.alexskuznetsov.xrspatial.sample

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import io.github.alexskuznetsov.xrspatial.PoseListener
import io.github.alexskuznetsov.xrspatial.PoseSample
import io.github.alexskuznetsov.xrspatial.SampleQuality
import io.github.alexskuznetsov.xrspatial.SpatialError
import io.github.alexskuznetsov.xrspatial.XrSpatialSession

/**
 * World-locked cubes: two cubes and an eye-height bar fixed in the room. Walk, squat and look around —
 * with working 6DoF tracking they stay where they are.
 */
class CubesActivity : Activity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var session: XrSpatialSession
    private var resumed = false
    private var surfaceReady = false
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The glasses turn the display off after 30 s without input.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this)
        session = XrSpatialSession(this, surfaceView, XrSpatialSession.Config(appName = "xrspatial-sample"))
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; maybeStart() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; session.stop() }
        })
        setContentView(surfaceView)
    }

    override fun onResume() { super.onResume(); resumed = true; maybeStart() }
    override fun onPause() { resumed = false; session.stop(); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        session.onWindowFocusChanged(hasFocus)
        maybeStart()
    }

    // The runtime needs a resumed, focused Activity with a valid surface.
    private fun maybeStart() {
        if (running || !resumed || !surfaceReady || !hasWindowFocus()) return
        running = true
        session.setHud(top = "XR SPATIAL CUBES")
        session.start(CubeSceneRenderer(), listener).onFailure { error ->
            Log.e(TAG, "start failed: $error")
            running = false
        }
    }

    private val listener = object : PoseListener {
        private var frames = 0L
        override fun onSample(sample: PoseSample, quality: SampleQuality) {
            // Render thread: keep it short. Show the raw position twice a second.
            if (quality == SampleQuality.USABLE && frames++ % 30 == 0L) {
                session.setHud(top = "XR SPATIAL CUBES", bottom = "X %.2f  Y %.2f  Z %.2f".format(sample.px, sample.py, sample.pz))
            }
        }
        override fun onError(error: SpatialError) {
            Log.e(TAG, "session error: ${error.message} operation=${error.operation} result=${error.resultCode}")
        }
        override fun onStopped(reason: String) {
            Log.i(TAG, "stopped: $reason ${session.diagnostics()}")
            running = false
        }
    }

    private companion object { const val TAG = "XrSpatialSample" }
}
