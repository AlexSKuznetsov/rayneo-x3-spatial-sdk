# Changelog

## 0.2.0 — in development

- New module `xrspatial-scene`: `SpatialScene`, `SpatialHost`, `SpatialPanel` (any Android View),
  `SpatialText`, `SpatialImage`, `Placement` (`InFrontOfUser`, `InLocal`, `HeadLocked`). Kotlin + GLES only.
  Host-tested; not yet run on the glasses.
- New sample `sample-scene`: a maintenance card from an XML layout.
- `xrspatial-core`: `Vector3`, `Quaternion`, `Pose3`, `PoseSample.pose`.
- `XrSpatialSession.diagnostics()["extensions"]`: the OpenXR extensions the runtime advertises.
- Window focus loss is a pause (`TrackingState.PAUSED`) with automatic resume, not `SessionLost`
  (verified on the glasses).

## 0.1.0

- `XrSpatialSession`, `XrRenderer`, `EyeFrame`, native OpenXR frame loop, `WarmupGate`, cube sample, runtime
  notes, host tests.
