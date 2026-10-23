package io.github.alexskuznetsov.xrspatial

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import com.rayneo.xr.client.FFalconXRClient
import com.rayneo.xr.client.LocalLoader
import com.rayneo.xr.sdk.FxrApi
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Head 6DoF tracking and world-locked rendering on RayNeo X3 Pro glasses, without Unity.
 *
 * Uses the RayNeo X3 Pro SDK 1.1.2 (added by the app, see README) to bind the RayNeo OpenXR runtime, then runs
 * a standard OpenXR session with its own frame loop on a dedicated render thread:
 * `xrWaitFrame -> xrBeginFrame -> xrLocateSpace(VIEW, LOCAL) -> xrLocateViews -> draw both eyes -> xrEndFrame`.
 *
 * - Every located head pose goes to [PoseListener.onSample] with its [SampleQuality] from a per-session
 *   [WarmupGate] (the runtime returns stale and broken poses for the first ~1 s of every session).
 * - If a [renderer] is set, it draws each eye of every frame whose pose is [SampleQuality.USABLE].
 * - Optional head-locked text via [setHud].
 *
 * Losing window focus (another app or the system screen recorder in front) makes the runtime end the
 * session; the session then goes to [TrackingState.PAUSED] instead of reporting an error, and starts again
 * by itself (new [PoseSample.sessionEpoch], new warm-up) when focus returns.
 *
 * Threading: call [start], [stop] and [onWindowFocusChanged] on the main thread. Call [stop] from
 * `Activity.onPause`. [surfaceView] must be the Activity's visible SurfaceView with a valid surface: the
 * runtime shows frames on it. One session at a time per process.
 */
class XrSpatialSession(
    private val activity: Activity,
    private val surfaceView: SurfaceView,
    private val config: Config = Config(),
) : SpatialTracking {
    data class Config(
        /** Passed to xrCreateInstance. */
        val appName: String = "xrspatial",
        /** Near/far clip planes of [EyeFrame.projection], metres. */
        val nearZ: Float = 0.05f,
        val farZ: Float = 100f,
        /** Show "WAITING FOR TRACKING" in the centre while the pose is not usable yet. */
        val waitingHud: Boolean = true,
    )

    /** Set before [start]; read on the render thread. */
    @Volatile var renderer: XrRenderer? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "xrspatial-bind").apply { isDaemon = true } }
    private val facts = linkedMapOf<String, String>()
    @Volatile private var latest: PoseSample? = null
    @Volatile private var state = TrackingState.STOPPED
    @Volatile private var nativeRunning = false
    private var stopRequested = false
    private var focusLostInRun = false
    private var pausedListener: PoseListener? = null
    private var client: FFalconXRClient? = null
    private var bound = false
    private var epoch = 0
    @Volatile private var hudTop = ""
    @Volatile private var hudBottom = ""
    @Volatile private var hudCentre = ""

    /** Convenience: set the [renderer] and start. */
    fun start(renderer: XrRenderer, listener: PoseListener): Result<Unit> {
        this.renderer = renderer
        return start(listener)
    }

    override fun start(listener: PoseListener): Result<Unit> {
        if (Looper.myLooper() != Looper.getMainLooper()) return Result.failure(SpatialError.SessionUnsupported("start:notMainThread"))
        if (state != TrackingState.STOPPED) return Result.failure(SpatialError.AlreadyRunning())
        return begin(listener)
    }

    private fun begin(listener: PoseListener): Result<Unit> {
        if (!surfaceView.holder.surface.isValid) return Result.failure(SpatialError.SessionUnsupported("surfaceInvalid"))
        val sdk = runCatching { FxrApi.Get() as? FFalconXRClient }.getOrNull()
            ?: return Result.failure(SpatialError.VendorInitializationFailed("FxrApi.Get"))
        val initialized = runCatching { sdk.initAndroid(activity) }.getOrDefault(false)
        if (!initialized) return Result.failure(SpatialError.VendorInitializationFailed("initAndroid"))
        client = sdk
        stopRequested = false
        focusLostInRun = false
        latest = null
        epoch++
        setState(listener, TrackingState.STARTING)
        worker.execute {
            val value = runCatching { sdk.bindService() }.getOrDefault(false)
            main.post { afterBind(sdk, value, listener) }
        }
        return Result.success(Unit)
    }

    override fun stop() {
        if (state == TrackingState.STOPPED) return
        if (state == TrackingState.PAUSED) {
            val listener = pausedListener ?: return
            pausedListener = null
            setState(listener, TrackingState.STOPPED)
            listener.onStopped("STOPPED")
            return
        }
        stopRequested = true
        if (nativeRunning) XrSpatialNative.cancel()
    }

    override fun latestUsable(): PoseSample? = latest

    override fun diagnostics(): Map<String, String> = facts + mapOf("state" to state.name, "sessionEpoch" to epoch.toString())

    /**
     * Forward from `Activity.onWindowFocusChanged`: the RayNeo runtime tracks the window focus, and a paused
     * session resumes here.
     */
    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus && nativeRunning) focusLostInRun = true
        if (bound && nativeRunning) runCatching { client?.setWindowFocus(hasFocus) }
        if (hasFocus && state == TrackingState.PAUSED) resume()
    }

    /**
     * Head-locked debug text drawn over the frame (upper-case Latin letters, digits and `. : / - < >`; other
     * characters show as spaces): one small line at the top, one larger line at the bottom. Long lines shrink;
     * beyond about 100 characters they are clipped.
     */
    fun setHud(top: String = "", bottom: String = "") {
        hudTop = top
        hudBottom = bottom
        pushHud()
    }

    private fun pushHud() = runCatching { XrSpatialNative.setHud(hudTop, hudBottom, hudCentre) }

    private fun afterBind(sdk: FFalconXRClient, value: Boolean, listener: PoseListener) {
        bound = value
        if (!value) { finish(sdk, listener, SpatialError.VendorInitializationFailed("bindService"), "BIND_FAILED"); return }
        if (stopRequested) { finish(sdk, listener, null, "STOPPED_BEFORE_SESSION"); return }
        val surface = surfaceView.holder.surface
        if (!surface.isValid) { finish(sdk, listener, SpatialError.SessionUnsupported("surfaceInvalid"), "SURFACE_INVALID"); return }
        try {
            sdk.setWindowFocus(activity.hasWindowFocus())
            sdk.setSurface(surface, surfaceView.width, surfaceView.height)
            facts["rayneoSdkVersion"] = LocalLoader.Version(activity)?.version ?: "null"
            facts["rayneoRuntimeVersion"] = LocalLoader.runtimeVersion(activity)?.version ?: "null"
            System.loadLibrary("openxr_loader")  // the RayNeo loader shipped in the RayNeo SDK
        } catch (e: Throwable) {
            finish(sdk, listener, SpatialError.LoaderInitFailed("windowOrLoader:${e.javaClass.simpleName}"), "LOADER_FAILED"); return
        }
        val gate = WarmupGate()
        val session = epoch
        var lastSample: PoseSample? = null
        var lastQuality: SampleQuality? = null
        hudCentre = if (config.waitingHud) WAITING else ""
        pushHud()
        XrSpatialNative.callbacks = object : XrSpatialNative.Callbacks {
            override fun onSessionCreated() { runCatching { sdk.setWindowFocus(activity.hasWindowFocus()) } }
            override fun onGlReady() { renderer?.onGlReady() }
            override fun onGlRelease() { renderer?.onGlRelease() }
            override fun onSample(index: Long, xrTime: Long, hostReceiptNs: Long, flags: Long,
                                  px: Float, py: Float, pz: Float, qx: Float, qy: Float, qz: Float, qw: Float) {
                val sample = PoseSample(session, index, xrTime, hostReceiptNs, flags, px, py, pz, qx, qy, qz, qw)
                val quality = gate.classify(sample)
                lastSample = sample
                if (quality == SampleQuality.USABLE) latest = sample
                if (config.waitingHud && (quality == SampleQuality.USABLE) != (lastQuality == SampleQuality.USABLE)) {
                    hudCentre = if (quality == SampleQuality.USABLE) "" else WAITING
                    pushHud()
                }
                lastQuality = quality
                listener.onSample(sample, quality)
            }
            override fun onDrawEye(eye: Int, view: FloatArray, projection: FloatArray, width: Int, height: Int) {
                val head = lastSample ?: return
                if (lastQuality != SampleQuality.USABLE) return
                renderer?.onDrawEye(EyeFrame(eye, view, projection, width, height, head))
            }
        }
        XrSpatialNative.resetCancel()
        nativeRunning = true
        setState(listener, TrackingState.RUNNING)
        if (stopRequested) XrSpatialNative.cancel()
        Thread({
            val result = runCatching { XrSpatialNative.run(activity, activity.applicationContext, surface, config.appName, config.nearZ, config.farZ) }
            main.post {
                nativeRunning = false
                val run = result.getOrNull()?.let { parse(it) }
                if (run != null && run.pausedByFocusLoss(focusLostInRun, stopRequested)) {
                    pause(sdk, listener)
                    return@post
                }
                val error = run?.error() ?: result.exceptionOrNull()?.let { SpatialError.SessionLost("run:${it.javaClass.simpleName}") }
                finish(sdk, listener, error, if (error == null) "STOPPED" else "ERROR")
            }
        }, "xrspatial-render").start()
    }

    private fun parse(json: String): RunResult {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return RunResult(null, json, null, null, null, null, null)
        val instance = root.optJSONObject("instance")
        val graphics = root.optJSONObject("graphics")
        val loop = root.optJSONObject("loop")
        instance?.optString("runtimeName")?.takeIf { it.isNotEmpty() }?.let { facts["runtimeName"] = it }
        instance?.optString("runtimeVersion")?.takeIf { it.isNotEmpty() }?.let { facts["runtimeVersion"] = it }
        instance?.optString("systemName")?.takeIf { it.isNotEmpty() }?.let { facts["systemName"] = it }
        graphics?.optString("surface")?.takeIf { it.isNotEmpty() }?.let { facts["surface"] = it }
        loop?.optString("endReason")?.let { facts["lastEndReason"] = it }
        return RunResult(
            instance?.optString("status"), instance?.optString("failure"),
            graphics?.optString("status"), graphics?.optString("failure"),
            loop?.optString("status"), loop?.optString("failure"), loop?.optString("endReason"))
    }

    private fun finish(sdk: FFalconXRClient, listener: PoseListener, error: SpatialError?, reason: String) {
        setState(listener, TrackingState.STOPPING)
        release(sdk)
        setState(listener, TrackingState.STOPPED)
        if (error != null) listener.onError(error)
        listener.onStopped(reason)
    }

    // The runtime ended the session on focus loss: release everything like a stop, but keep the listener.
    private fun pause(sdk: FFalconXRClient, listener: PoseListener) {
        setState(listener, TrackingState.STOPPING)
        release(sdk)
        pausedListener = listener
        setState(listener, TrackingState.PAUSED)
        if (activity.hasWindowFocus()) resume()
    }

    private fun resume() {
        val listener = pausedListener ?: return
        if (!surfaceView.holder.surface.isValid) return  // stays paused; the next focus gain tries again
        pausedListener = null
        begin(listener).onFailure { e ->
            setState(listener, TrackingState.STOPPED)
            listener.onError(e as? SpatialError ?: SpatialError.SessionLost("resume:${e.javaClass.simpleName}"))
            listener.onStopped("RESUME_FAILED")
        }
    }

    private fun release(sdk: FFalconXRClient) {
        XrSpatialNative.callbacks = null
        if (bound) runCatching { sdk.unbindService() }
        bound = false
        runCatching { sdk.uninitAndroid() }
        client = null
        latest = null
    }

    private fun setState(listener: PoseListener, next: TrackingState) {
        state = next
        listener.onStateChanged(next)
    }

    private companion object {
        const val WAITING = "WAITING FOR TRACKING"
    }
}
