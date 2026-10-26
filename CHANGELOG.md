# Changelog

## Unreleased

- Lifecycle hardening (from a code review): `XrSpatialSession` runs a JVM-tested state machine where every
  start or resume is a numbered attempt and stale bind or native results are ignored; `stop()` and
  `onWindowFocusChanged()` may be called from any thread. `SpatialHost` ignores other Activities' events and
  starts again by itself after a normal stop while its Activity is still resumed and focused (a quick pause and
  resume could leave the scene stopped).
- `SpatialQuad` publishes its bitmap and version as one snapshot; the renderer draws each node from a single
  snapshot per frame (size, texture and pointer pixels always match).
- `TrackingConfidence` (`NONE`, `NORMAL`, `DEGRADED` for 2 s after a position jump) via
  `XrSpatialSession.confidence()` and `PoseListener.onConfidenceChanged`; `ConfidenceTracker` in the core.

- Pointer API in `xrspatial-scene`: `SpatialScene.pointer = SpatialPointer(...)` draws a dot
  (`PointerStyle.Dot`) or a ray (`PointerStyle.Ray`) from the centre of the view (`PointerSource.Gaze`) or an
  app-supplied pose (`PointerSource.Custom`), switchable with `enabled`; `hit` / `PointerListener` report the
  node, point and pixel it points at. `SpatialPanel.performClickAt(hit)` clicks the view there. New sample
  `sample-pointer`. On the glasses: the dot sits on the card without doubling, taps click the button under it,
  `enabled` switches it off and on, ray and recenter work. `xrspatial-core`: `Ray3`, `RectHit`, `Pose3.forwardRay()`.

- New sample `sample-input`: logs every input event the Activity receives during an XR session. Run on the
  glasses: the temple touchpad arrives as ordinary touchscreen events; a long press takes focus away
  (see the runtime notes).
- Experimental `XrSpatialSession.Config.handTracking` + `handListener` (`XR_EXT_hand_tracking`, palm and index
  tip in LOCAL), off by default. **Crashes on X3 Pro runtime 1.1.7.9**: the runtime's first hand locate
  segfaults. Kept for other runtimes.

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
