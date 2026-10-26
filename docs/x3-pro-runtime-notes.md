# RayNeo X3 Pro OpenXR runtime: observed behaviour

Observations from one RayNeo X3 Pro, OpenXR runtime `com.rayneo.xr.runtime` 1.1.7.9, RayNeo SDK 1.1.2
(Android libraries only), in 2026. They describe what the runtime did, not what RayNeo documents or
guarantees; other firmware may differ. Each item says what this library does about it.

## Bootstrap

| Observation | What the library does |
| --- | --- |
| The standard Khronos Android loader does not find the runtime (`xrEnumerateInstanceExtensionProperties` returns `XR_ERROR_RUNTIME_UNAVAILABLE`, −51). | Uses the RayNeo loader `libopenxr_loader.so` from `RayNeoOpenXRLoader.aar`, after the RayNeo client has bound the runtime service (`FxrApi.Get()`, `initAndroid`, `bindService`, `setWindowFocus`, `setSurface`). |
| `xrCreateInstance` without an `XrInstanceCreateInfoAndroidKHR` in the `next` chain crashes the process (SIGSEGV) instead of returning an error. | Always chains the Android create info; enables `XR_KHR_android_create_instance` only if advertised. |
| `xrConvertTimespecTimeToTimeKHR` is not available (`XR_ERROR_FUNCTION_UNSUPPORTED`, −7). | Not used. |
| The RayNeo SDK loads its native libraries from the app's `nativeLibraryDir`. | Apps must set `packaging.jniLibs.useLegacyPackaging = true`. |

## Frame loop

| Observation | What the library does |
| --- | --- |
| `xrEndFrame` with **zero layers** crashes inside the runtime. | Always submits exactly one `XrCompositionLayerProjection` (VIEW space), even when nothing is drawn. |
| A second `xrWaitFrame` before `xrEndFrame` returns `XR_ERROR_CALL_ORDER_INVALID` (−37): frames are strictly serialised. | Ends every begun frame; one frame in flight. |
| The runtime presents inside `xrEndFrame` with `eglSwapBuffers` on the calling thread's current EGL **window** surface. With a 1×1 pbuffer nothing is visible. | Makes an EGL window surface on the app's `SurfaceView` current on the render thread. |
| Swapchain: 640×480 per eye, `GL_SRGB8_ALPHA8`, 4 images; views report sample count 0. | Requests the recommended size (fallback 640×480) and sample count 1; adds its own 16-bit depth buffer. |
| When the app's window loses focus (observed: the user opened the system screen-recording app), the RayNeo client reports focus 0 and the runtime moves the session to STOPPING (state 6) within ~40 ms. A recording made with the on-glasses recorder therefore cannot show a running XR session. | Ends the session cleanly, reports `TrackingState.PAUSED` (no error) and starts a new session when focus returns (observed: new session usable 0.91 s after the restart, same process). |
| The glasses switch the display off after ~30 s without touch input, which pauses the app. | The sample sets `FLAG_KEEP_SCREEN_ON`; do the same in your Activity. |

## Poses and time

| Observation | What the library does |
| --- | --- |
| `predictedDisplayTime` from `xrWaitFrame` is the constant 16666667 on every frame (and the display period is 16666667 ns). | Uses it as required by `xrLocateSpace`, reports it raw in `PoseSample.xrTime`, and does not treat it as a clock. `PoseSample.hostReceiptNs` (CLOCK_MONOTONIC when `xrLocateSpace` returned) is a receipt time, not the measurement time. |
| `XrSpaceLocation.locationFlags` read 15 (all four bits) on every frame: on start-up garbage, NaN poses and with the cameras covered. | Keeps the four bits raw and independent; quality is judged by `WarmupGate`, not by the flags. |
| Every session starts with the **previous session's last pose** for 0–0.35 s, then NaN or non-unit quaternions until 0.6–1.35 s, sometimes one wild pose. All with flags 15. | `WarmupGate` (per session) marks samples `USABLE` only after 300 ms and 10 consecutive clean samples; `XrRenderer.onDrawEye` is called only for `USABLE` frames. Observed first `USABLE`: 0.9–1.7 s. |
| LOCAL is re-created at every session start: yaw ≈ 0 towards the current gaze, gravity-aligned, origin near the head (≈ (0, −0.008, +0.025) m at start). | `PoseSample.sessionEpoch` changes per session; never mix poses from different epochs. |
| After a ~90° head turn to the left the position jumped ~0.4 m, partly snapped back and kept a ~0.3 m offset (2 of 2 tries, body still). Another run had one ~1 m position jump at ≈10 s. | `WarmupGate` rejects only steps faster than its speed limits (4 m/s, 1000°/s); slower errors pass. Not fixed. |
| Covering the cameras for 5 s: flags stayed 15, ≈5 cm drift kept afterwards. | Nothing; the runtime gives no loss signal. |
| Metric scale. | NOT TESTED: moves of 0.4–0.5 m looked plausible but were not measured against a reference distance. |

## Input

| Observation | What the library does |
| --- | --- |
| During an XR session the temple touchpad reaches the Activity as ordinary **touchscreen** `MotionEvent`s (`dispatchTouchEvent`, source `0x5002`, device `cyttsp5_mt`), mapped like a 1280×480 screen: x along the temple (growing towards the eye: a swipe towards the ear has dx < 0), y across it. No key, generic-motion or trackball events; swipes are not mapped to BACK. Tap: DOWN+UP, 60–90 ms, no MOVE. Swipe forward/back: x runs to 1282 / to 2 in ~100–130 ms. Double tap: two taps ~0.2 s apart (no system double-tap event). Two-finger touches arrive as `ACTION_POINTER_DOWN` with 2 pointers. A second touch controller `cyttsp6_mt` exists; no events from it were seen. | Nothing yet; apps can use standard touch handling. Checked with the `sample-input` probe. |
| Holding the touchpad for ≳0.8 s opens a native system app, which takes window focus; the session pauses and resumes when the app is back in front (observed ~3.7 s later). | Handled as a focus-loss pause. Do not use long press as an app gesture. |
| `XR_EXT_hand_tracking` is the only advertised extension; the system reports `supportsHandTracking`, and `xrCreateHandTrackerEXT` succeeds for both hands, but the **first `xrLocateHandJointsEXT` crashes the process** inside the runtime (SIGSEGV, null dereference in `HandTracker::locateHandJoints`), also when the session is FOCUSED (2 of 2 runs). | `Config.handTracking` stays off by default and is documented as crashing on this runtime. Hands: no data obtainable. |

## Views and projection

| Observation | What the library does |
| --- | --- |
| `xrLocateViews` returns `angleLeft` and `angleDown` as **positive** magnitudes (observed left 0.2007, right 0.2229, up 0.1556, down 0.1620 rad: ≈24.3° × 18.2° per eye). The OpenXR specification has them negative. Taken literally, the projection mirrors the image; the first test showed coloured patches instead of cubes. | `EyeFrame.projection` is built from a render-only copy with `angleLeft = −|angleLeft|` and `angleDown = −|angleDown|`. The raw fov is submitted unchanged in the projection layer. |
| Both eye views are pitched ≈10.8° down relative to the head pose. | Kept: a test with a bar rendered at the start eye height lined up with a real mark at the wearer's eye height, so the pitch is physical. `EyeFrame.view` includes it. |
| Display is additive (black = transparent). | Eyes are cleared to black. |
