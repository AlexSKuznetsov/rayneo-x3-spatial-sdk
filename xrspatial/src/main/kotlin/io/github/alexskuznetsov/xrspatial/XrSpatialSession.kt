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
 * - Experimental: with [Config.handTracking], both hands of every frame go to [handListener] (`XR_EXT_hand_tracking`).
 *
 * Losing window focus (another app or the system screen recorder in front) makes the runtime end the
 * session; the session then goes to [TrackingState.PAUSED] instead of reporting an error, and starts again
 * by itself (new [PoseSample.sessionEpoch], new warm-up) when focus returns.
 *
 * Threading: call [start] on the main thread; [stop] and [onWindowFocusChanged] may be called from any thread
 * and are applied on the main thread. Call [stop] from
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
        /**
         * Experimental: enable `XR_EXT_hand_tracking` if the runtime advertises it and reports support, and deliver
         * hands to [handListener]. Off by default. **Do not enable on X3 Pro runtime 1.1.7.9**: there the runtime
         * advertises and creates hand trackers, but its first `xrLocateHandJointsEXT` crashes the process.
         */
        val handTracking: Boolean = false,
    )

    /** Set before [start]; read on the render thread. */
    @Volatile var renderer: XrRenderer? = null

    /** Receives hands when [Config.handTracking] is on and the runtime supports it; called on the render thread. */
    @Volatile var handListener: HandListener? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "xrspatial-bind").apply { isDaemon = true } }
    private val facts = linkedMapOf<String, String>()
    @Volatile private var latest: PoseSample? = null
    @Volatile private var confidence = TrackingConfidence.NONE
    @Volatile private var jumps = 0
    private val machine = SessionStateMachine()
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
        if (machine.state != TrackingState.STOPPED) return Result.failure(SpatialError.AlreadyRunning())
        return begin(listener, resume = false)
    }

    // A start (from STOPPED) or a resume (from PAUSED): the RayNeo bind runs on a worker; its result is applied
    // on the main thread only if this attempt is still the current one.
    private fun begin(listener: PoseListener, resume: Boolean): Result<Unit> {
        if (!surfaceView.holder.surface.isValid) return Result.failure(SpatialError.SessionUnsupported("surfaceInvalid"))
        val sdk = runCatching { FxrApi.Get() as? FFalconXRClient }.getOrNull()
            ?: return Result.failure(SpatialError.VendorInitializationFailed("FxrApi.Get"))
        val initialized = runCatching { sdk.initAndroid(activity) }.getOrDefault(false)
        if (!initialized) return Result.failure(SpatialError.VendorInitializationFailed("initAndroid"))
        val generation = machine.begin(resume)
        if (generation == null) {
            runCatching { sdk.uninitAndroid() }
            return Result.failure(SpatialError.AlreadyRunning())
        }
        client = sdk
        latest = null
        epoch++
        notify(listener)
        worker.execute {
            val value = runCatching { sdk.bindService() }.getOrDefault(false)
            main.post { afterBind(generation, sdk, value, listener) }
        }
        return Result.success(Unit)
    }

    /** Ends the session asynchronously ([PoseListener.onStopped] follows). From any thread; applied on the main thread. */
    override fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { stop() }; return }
        when (machine.stop()) {
            SessionStateMachine.Stop.NONE -> Unit
            SessionStateMachine.Stop.NOW -> {
                val listener = pausedListener ?: return
                pausedListener = null
                notify(listener)
                listener.onStopped("STOPPED")
            }
            SessionStateMachine.Stop.REQUESTED -> if (machine.nativeRunning) XrSpatialNative.cancel()
        }
    }

    override fun latestUsable(): PoseSample? = latest

    /**
     * How far world-locked positions can be trusted now: NONE without a usable pose, DEGRADED for 2 s after a
     * position jump (the shifted frame is accepted after the warm-up, so content may appear offset), else NORMAL.
     * Changes also go to [PoseListener.onConfidenceChanged]. Any thread.
     */
    fun confidence(): TrackingConfidence = confidence

    override fun diagnostics(): Map<String, String> = facts + mapOf("state" to machine.state.name, "confidence" to confidence.name, "jumps" to jumps.toString(), "sessionEpoch" to epoch.toString())

    /**
     * Forward from `Activity.onWindowFocusChanged`: the RayNeo runtime tracks the window focus, and a paused
     * session resumes here. From any thread; applied on the main thread.
     */
    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onWindowFocusChanged(hasFocus) }; return }
        val resumeNow = machine.focusChanged(hasFocus)
        if (bound && machine.nativeRunning) runCatching { client?.setWindowFocus(hasFocus) }
        if (resumeNow) resume()
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

    private fun afterBind(generation: Int, sdk: FFalconXRClient, value: Boolean, listener: PoseListener) {
        when (machine.bound(generation, value)) {
            SessionStateMachine.Bind.STALE -> return  // a newer attempt owns the client now
            SessionStateMachine.Bind.FAILED -> {
                bound = false
                finish(sdk, listener, SpatialError.VendorInitializationFailed("bindService"), "BIND_FAILED"); return
            }
            SessionStateMachine.Bind.CANCELLED -> { bound = true; finish(sdk, listener, null, "STOPPED_BEFORE_SESSION"); return }
            SessionStateMachine.Bind.RUN -> bound = true
        }
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
        val tracker = ConfidenceTracker()
        confidence = TrackingConfidence.NONE
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
                val nextConfidence = tracker.update(sample, quality)
                jumps = tracker.jumps
                lastSample = sample
                if (quality == SampleQuality.USABLE) latest = sample
                if (config.waitingHud && (quality == SampleQuality.USABLE) != (lastQuality == SampleQuality.USABLE)) {
                    hudCentre = if (quality == SampleQuality.USABLE) "" else WAITING
                    pushHud()
                }
                lastQuality = quality
                listener.onSample(sample, quality)
                if (nextConfidence != confidence) {
                    confidence = nextConfidence
                    listener.onConfidenceChanged(nextConfidence)
                }
            }
            override fun onDrawEye(eye: Int, view: FloatArray, projection: FloatArray, width: Int, height: Int) {
                val head = lastSample ?: return
                if (lastQuality != SampleQuality.USABLE) return
                renderer?.onDrawEye(EyeFrame(eye, view, projection, width, height, head))
            }
            override fun onHands(index: Long, xrTime: Long, info: LongArray, positions: FloatArray) {
                val target = handListener ?: return
                fun hand(h: Int) = HandSample(session, index, xrTime, if (h == 0) Hand.LEFT else Hand.RIGHT,
                    info[h * 4].toInt(), info[h * 4 + 1] != 0L,
                    info[h * 4 + 2], Vector3(positions[h * 6], positions[h * 6 + 1], positions[h * 6 + 2]),
                    info[h * 4 + 3], Vector3(positions[h * 6 + 3], positions[h * 6 + 4], positions[h * 6 + 5]))
                target.onHands(hand(0), hand(1))
            }
        }
        XrSpatialNative.resetCancel()
        machine.nativeStarted(generation)
        notify(listener)
        if (machine.stopRequested) XrSpatialNative.cancel()
        Thread({
            val result = runCatching { XrSpatialNative.run(activity, activity.applicationContext, surface, config.appName, config.nearZ, config.farZ, config.handTracking) }
            main.post {
                val run = result.getOrNull()?.let { parse(it) }
                when (machine.nativeEnded(generation, run)) {
                    SessionStateMachine.End.STALE -> Unit
                    SessionStateMachine.End.PAUSE -> pause(sdk, listener)
                    SessionStateMachine.End.FINISH -> {
                        val error = run?.error() ?: result.exceptionOrNull()?.let { SpatialError.SessionLost("run:${it.javaClass.simpleName}") }
                        finish(sdk, listener, error, if (error == null) "STOPPED" else "ERROR")
                    }
                }
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
        // Advertised OpenXR extensions, e.g. to notice spatial anchors or containers in a future runtime.
        instance?.optJSONArray("extensions")?.let { a -> facts["extensions"] = (0 until a.length()).joinToString(",") { a.optString(it) } }
        // Experimental hand tracking: requested / enabled / supported and the trackers' results, raw JSON.
        instance?.optJSONObject("handTracking")?.let { facts["handTracking"] = it.toString() }
        graphics?.optString("surface")?.takeIf { it.isNotEmpty() }?.let { facts["surface"] = it }
        loop?.optString("endReason")?.let { facts["lastEndReason"] = it }
        return RunResult(
            instance?.optString("status"), instance?.optString("failure"),
            graphics?.optString("status"), graphics?.optString("failure"),
            loop?.optString("status"), loop?.optString("failure"), loop?.optString("endReason"))
    }

    private fun finish(sdk: FFalconXRClient, listener: PoseListener, error: SpatialError?, reason: String) {
        machine.stopping()
        notify(listener)
        release(sdk)
        machine.stopped()
        notify(listener)
        if (error != null) listener.onError(error)
        listener.onStopped(reason)
    }

    // The runtime ended the session on focus loss: release everything like a stop, but keep the listener.
    private fun pause(sdk: FFalconXRClient, listener: PoseListener) {
        machine.stopping()
        notify(listener)
        release(sdk)
        pausedListener = listener
        machine.paused()
        notify(listener)
        if (activity.hasWindowFocus()) resume()
    }

    private fun resume() {
        val listener = pausedListener ?: return
        if (!surfaceView.holder.surface.isValid) return  // stays paused; the next focus gain tries again
        pausedListener = null
        begin(listener, resume = true).onFailure { e ->
            machine.stop()  // PAUSED -> STOPPED
            notify(listener)
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
        confidence = TrackingConfidence.NONE
    }

    private fun notify(listener: PoseListener) = listener.onStateChanged(machine.state)

    private companion object {
        const val WAITING = "WAITING FOR TRACKING"
    }
}
