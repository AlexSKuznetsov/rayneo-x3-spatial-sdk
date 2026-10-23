package io.github.alexskuznetsov.xrspatial

import android.app.Activity
import android.content.Context
import android.view.Surface

/** JNI bridge of xrspatial_jni.cpp. One session per process at a time; the callbacks run on the render thread. */
internal object XrSpatialNative {
    init { System.loadLibrary("xrspatial") }

    /** Blocks until [cancel] (or a failure); returns the result JSON ({"instance","graphics","loop"}). */
    @JvmStatic external fun run(activity: Activity, context: Context, surface: Surface, appName: String, nearZ: Float, farZ: Float): String
    @JvmStatic external fun cancel()
    @JvmStatic external fun resetCancel()
    @JvmStatic external fun setHud(top: String, bottom: String, centre: String)

    interface Callbacks {
        fun onSessionCreated()
        fun onGlReady()
        fun onGlRelease()
        fun onSample(index: Long, xrTime: Long, hostReceiptNs: Long, flags: Long,
                     px: Float, py: Float, pz: Float, qx: Float, qy: Float, qz: Float, qw: Float)
        fun onDrawEye(eye: Int, view: FloatArray, projection: FloatArray, width: Int, height: Int)
    }

    @Volatile var callbacks: Callbacks? = null

    @JvmStatic fun onSessionCreated() { callbacks?.onSessionCreated() }
    @JvmStatic fun onGlReady() { callbacks?.onGlReady() }
    @JvmStatic fun onGlRelease() { callbacks?.onGlRelease() }
    @JvmStatic fun onSample(index: Long, xrTime: Long, hostReceiptNs: Long, flags: Long,
                            px: Float, py: Float, pz: Float, qx: Float, qy: Float, qz: Float, qw: Float) {
        callbacks?.onSample(index, xrTime, hostReceiptNs, flags, px, py, pz, qx, qy, qz, qw)
    }
    @JvmStatic fun onDrawEye(eye: Int, view: FloatArray, projection: FloatArray, width: Int, height: Int) {
        callbacks?.onDrawEye(eye, view, projection, width, height)
    }
}
