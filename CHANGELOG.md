# Changelog

## Unreleased

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
