# API reference (0.1.0)

Package `io.github.alexskuznetsov.xrspatial`. Modules:

- `xrspatial-core` — plain Kotlin/JVM: the pose data model, the warm-up gate and `Vector3` / `Quaternion` /
  `Pose3` (`PoseSample.pose`). Usable in tests and tools.
- `xrspatial` — Android library: the session, the renderer interface and the native OpenXR frame loop.
  Depends on `xrspatial-core` (`api`).
- `xrspatial-scene` — cards, images and text without OpenGL; documented in [scene.md](scene.md).

Coordinates everywhere: OpenXR LOCAL space of the current session — metres, right-handed, +X right, +Y up,
−Z forward. Origin and yaw are the head's at session start; +Y is against gravity.

## XrSpatialSession

```kotlin
class XrSpatialSession(activity: Activity, surfaceView: SurfaceView, config: Config = Config()) : SpatialTracking
```

| Member | Description |
| --- | --- |
| `Config(appName = "xrspatial", nearZ = 0.05f, farZ = 100f, waitingHud = true)` | `appName` goes to `xrCreateInstance`; near/far planes of `EyeFrame.projection`; `waitingHud` shows "WAITING FOR TRACKING" until the first `USABLE` pose. |
| `start(renderer: XrRenderer, listener: PoseListener): Result<Unit>` | Sets `renderer` and starts. |
| `start(listener: PoseListener): Result<Unit>` | Starts a session and returns at once. Must be called on the main thread, with the `SurfaceView`'s surface valid and the Activity resumed and focused. Immediate failures come back in the `Result`; later ones go to `PoseListener.onError`. |
| `stop()` | Asks the frame loop to end; `PoseListener.onStopped` follows when everything is released. Also ends a pause. Call it from `onPause` and `surfaceDestroyed`. |
| `renderer: XrRenderer?` | Volatile; read on the render thread each frame. |
| `latestUsable(): PoseSample?` | Last `USABLE` sample of the current session, or null. Any thread. |
| `diagnostics(): Map<String, String>` | RayNeo SDK and runtime versions, runtime/system names, advertised OpenXR `extensions` (comma-separated), state, session epoch, last end reason. For logs only. |
| `onWindowFocusChanged(hasFocus: Boolean)` | Forward from `Activity.onWindowFocusChanged`; the runtime tracks focus, and a paused session resumes here. |
| `setHud(top: String = "", bottom: String = "")` | Head-locked debug text: small top line, larger bottom line. Upper-case Latin letters, digits and `. : / - < >`; anything else is a space. Long lines shrink; beyond about 100 characters they are clipped. Any thread. |

One session at a time per process; consecutive sessions in one process work (observed once, after a
focus loss). After `onStopped`, the
same object can be started again.

### Lifecycle

```
start() ── STARTING ── bindService (worker thread) ── RUNNING ── frame loop ── STOPPING ── STOPPED
                         │ failure                     │ stop() / runtime ends / error
                         └── onError + onStopped        └── onError (if any) + onStopped(reason)

RUNNING ── window focus lost, runtime ends the session ── STOPPING ── PAUSED
PAUSED  ── window focus back ── STARTING ── ... ── RUNNING        (new sessionEpoch, new warm-up)
PAUSED  ── stop() ── STOPPED + onStopped("STOPPED")
```

**Focus loss is a pause, not an error.** When another window takes focus (another app, the system screen
recorder, a system dialog), the RayNeo runtime ends the OpenXR session. The library then releases
everything as for a stop, reports `TrackingState.PAUSED` (no `onError`, no `onStopped`) and starts a new
session by itself when `onWindowFocusChanged(true)` arrives. The new session has a new LOCAL space
(`sessionEpoch` + 1, yaw and origin from the head at that moment) and its own warm-up of about one
second, so world-locked content re-anchors to where the wearer looks when focus returns. If the restart
fails, `onError` and `onStopped("RESUME_FAILED")` follow. If the runtime ends a session while the app
still has focus, that is `SpatialError.SessionLost`.

`onStopped(reason)`: `STOPPED`, `ERROR`, `BIND_FAILED`, `STOPPED_BEFORE_SESSION`, `SURFACE_INVALID`,
`LOADER_FAILED`, `RESUME_FAILED`.

## XrRenderer and EyeFrame

```kotlin
interface XrRenderer {
    fun onGlReady() {}                // once per session, GL context current
    fun onDrawEye(eye: EyeFrame)      // every eye of every USABLE frame
    fun onGlRelease() {}              // once per session, GL context still current
}

class EyeFrame(val eye: Int, val view: FloatArray, val projection: FloatArray,
               val width: Int, val height: Int, val head: PoseSample)
```

- All three calls run on the render thread `xrspatial-render`, which owns an OpenGL ES 3 context. Use
  `android.opengl.GLES30` there only.
- In `onDrawEye` the eye's framebuffer (colour + 16-bit depth) is bound, the viewport is the full image
  and it is cleared to black. Don't change the framebuffer binding. Restore GL state you changed if the
  next draw depends on it (the library draws its HUD afterwards with its own state).
- `view` (LOCAL → eye) and `projection` (eye → clip) are column-major, ready for
  `android.opengl.Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)` and
  `glUniformMatrix4fv(location, 1, false, mvp, 0)`. Depth range is OpenGL's [−1, 1]. The arrays are reused;
  copy them to keep them.
- Frames whose pose is not `USABLE` are not passed to the renderer; those eyes stay black.
- An exception thrown from any callback is printed to logcat, cleared and counted (`callbackExceptions`
  in the result); the frame is still submitted. Don't rely on this: a renderer that throws every frame
  shows nothing.

## PoseListener

```kotlin
interface PoseListener {
    fun onSample(sample: PoseSample, quality: SampleQuality)
    fun onStateChanged(state: TrackingState) {}
    fun onError(error: SpatialError) {}
    fun onStopped(reason: String) {}
}
```

`onSample` is called on the render thread for every located frame (about 60 per second), including
rejected ones: keep it short and use only `USABLE` samples for content. The other callbacks arrive on the
main thread.

## PoseSample

Raw result of `xrLocateSpace(VIEW, LOCAL, predictedDisplayTime)`; nothing is normalised or filled in.

| Field | Meaning |
| --- | --- |
| `sessionEpoch: Int` | Increments per session. LOCAL changes with it. |
| `frameIndex: Long` | 0-based frame index within the session. |
| `xrTime: Long` | Raw `predictedDisplayTime`. Constant on the X3 Pro runtime, see runtime notes. |
| `hostReceiptNs: Long` | `CLOCK_MONOTONIC` when `xrLocateSpace` returned. A receipt time, not the measurement time. |
| `locationFlags: Long` | Raw bits; `orientationValid` (1), `positionValid` (2), `orientationTracked` (4), `positionTracked` (8), `allFourFlags`. |
| `px, py, pz` | Head position, metres, float32. |
| `qx, qy, qz, qw` | Head orientation quaternion, float32, not normalised. |
| `finite`, `quaternionNorm`, `sameRawPose(other)` | Helpers used by the gate. |

## SampleQuality and WarmupGate

`WarmupGate(config).classify(sample)` returns, in this order of checks:

| Quality | When |
| --- | --- |
| `FLAGS_INCOMPLETE` | Not all four flag bits set. |
| `NON_FINITE` | NaN/Inf in position or orientation. |
| `NON_UNIT_QUATERNION` | ‖q‖ differs from 1 by more than `unitTolerance` (1e-3). |
| `STALE_START` | Bit-identical to the session's first sample, until the pose first changes. |
| `JUMP` | Moved faster than `maxLinearSpeedMps` (4 m/s) + `linearSlackM` (2 cm), or turned faster than `maxAngularSpeedDegPerS` (1000°/s) + `angularSlackDeg` (5°), since the previous sample. |
| `WARMING_UP` | Clean, but the current clean run is shorter than `windowNs` (300 ms) or `minSamples` (10). |
| `USABLE` | Clean, after an uninterrupted clean run of at least 300 ms and 10 samples. |

Any rejection restarts the clean run. Use one gate per session. `XrSpatialSession` already runs one; the
class is public so pose-only consumers and tests can apply the same rule.

## SpatialError

Every error keeps the failing operation's name and the raw result code (an `XrResult` where there is one):
`error.operation`, `error.resultCode`.

| Type | Raised when |
| --- | --- |
| `VendorInitializationFailed` | RayNeo client missing, `initAndroid` or `bindService` failed. |
| `LoaderInitFailed` | Window hand-over or loading the RayNeo loader failed. |
| `RuntimeUnavailable` | Loader initialisation, instance or system creation failed. |
| `SessionUnsupported` | Not on the main thread, invalid surface, EGL/GLES or `xrCreateSession` failed. |
| `SessionLost` | The frame loop failed, or the runtime ended the session while the app had window focus. |
| `AlreadyRunning` | `start` while a session is running. |
| `PositionTrackingUnavailable`, `PermissionDenied` | Reserved; not raised by 0.1.0. |
