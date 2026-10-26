# Changelog

## 0.3.1 — unreleased

**Pointer safety** (from a code review)
- `SpatialPointer.hit` is null whenever no frame is drawn with a usable pose (start-up, the warm-up after a
  rejected pose, paused or stopped) and right after `SpatialScene.recenter()`; before, it kept the last target, so
  a tap after a tracking loss could click it.
- `PointerHit` carries the head pose it was found with: `sessionEpoch`, `frameIndex`, `hostReceiptNs`
  (CLOCK_MONOTONIC). New parameters at the end, with defaults.
- `PointerListener` deliveries are coalesced: a busy main thread gets the latest target, not a queue of old ones.
- `XrRenderer.onFrameSkipped(head)` (default no-op): once per frame not drawn because the pose is not usable.

**Cheaper panel updates**
- The renderer reuses one staging buffer and overwrites textures of unchanged size with `glTexSubImage2D`
  instead of allocating new storage per change.
- `SpatialPanel.update()` alternates between two bitmaps instead of allocating one per call.
- Timings for logs: `SpatialScene.diagnostics()` (texture upload time and bytes, panel updates, bitmaps
  created) and `frameInterval` / `renderTime` in `XrSpatialSession.diagnostics()`; `TimingStats` in the core.
- `sample-pointer`: a line on the card redrawn 10 times a second, timings logged every 5 s, hit age logged per tap.
- Docs: touchpad gesture recipe (`docs/scene.md`).

## 0.3.0 — 2026-10-25

**Pointer**
- `SpatialScene.pointer = SpatialPointer(...)` draws a dot (`PointerStyle.Dot`) or a ray (`PointerStyle.Ray`)
  from the centre of the view (`PointerSource.Gaze`) or an app-supplied pose (`PointerSource.Custom`).
  `enabled` switches it off and on; `hit` and `PointerListener` report the node, point, distance and panel pixel
  it points at. `SpatialPanel.performClickAt(hit)` clicks the view there. What a gesture means is the app's
  choice. `xrspatial-core`: `Ray3`, `RectHit`, `Pose3.forwardRay()`.
- New sample `sample-pointer` (tap clicks, swipe towards the eye switches dot/ray, swipe towards the ear switches
  the pointer off and on, two-finger tap recenters). On the glasses: the dot sits on the card without doubling,
  taps click the button under it, on/off, ray and recenter work.

**Input facts**
- The temple touchpad arrives as ordinary touchscreen events during an XR session (x along the temple, growing
  towards the eye); a long press opens a system app, which pauses the session. New diagnostic sample
  `sample-input`.
- Experimental `XrSpatialSession.Config.handTracking` + `handListener` (`XR_EXT_hand_tracking`, palm and index
  tip in LOCAL), off by default. **Crashes on X3 Pro runtime 1.1.7.9**: the runtime's first hand locate
  segfaults, also in a FOCUSED session. Kept for other runtimes.

**Robustness** (from a code review)
- `XrSpatialSession` runs a JVM-tested state machine: every start or resume is a numbered attempt, stale bind or
  native results are ignored, native runs never overlap; `stop()` and `onWindowFocusChanged()` may be called
  from any thread.
- `SpatialHost` ignores other Activities' events and starts again by itself after a normal stop while its
  Activity is still resumed and focused (a quick pause and resume could leave the scene stopped). Verified on the
  glasses: HOME and back restores the scene.
- `SpatialQuad` publishes bitmap and version as one snapshot; the renderer draws each node from one snapshot per
  frame.
- `TrackingConfidence` (`NONE`, `NORMAL`, `DEGRADED` for 2 s after a position jump) via
  `XrSpatialSession.confidence()` and `PoseListener.onConfidenceChanged`; `ConfidenceTracker` in the core. Jumps
  before the session's first usable pose (start-up garbage) do not count.

## 0.2.0 — 2026-10-25

- New module `xrspatial-scene`: `SpatialScene`, `SpatialHost`, `SpatialPanel` (any Android View),
  `SpatialText`, `SpatialImage`, `Placement` (`InFrontOfUser`, `InLocal`, `HeadLocked`). Kotlin + GLES only.
  Run on the glasses: the card is centred, readable and world-locked. Parentless panel views get
  LayoutParams (changing a `SpatialText` crashed in the first device run).
- New sample `sample-scene`: a maintenance card from an XML layout.
- `xrspatial-core`: `Vector3`, `Quaternion`, `Pose3`, `PoseSample.pose`.
- `XrSpatialSession.diagnostics()["extensions"]`: the OpenXR extensions the runtime advertises.
- Window focus loss is a pause (`TrackingState.PAUSED`) with automatic resume, not `SessionLost`
  (verified on the glasses).

## 0.1.0

- `XrSpatialSession`, `XrRenderer`, `EyeFrame`, native OpenXR frame loop, `WarmupGate`, cube sample, runtime
  notes, host tests.
