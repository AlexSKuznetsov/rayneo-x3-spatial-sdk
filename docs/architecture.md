# Architecture

## Layers

```
xrspatial-scene   SpatialScene, SpatialHost, panels/text/images, placement     Kotlin + GLES
       |
xrspatial         XrSpatialSession, XrRenderer, EyeFrame, native frame loop    Kotlin + C++ (JNI)
       |
xrspatial-core    PoseSample, WarmupGate, Vector3/Quaternion/Pose3             pure Kotlin (JVM)
       |
RayNeo SDK 1.1.2  RayNeoSDK-client.aar (binds the runtime service),            user-provided
                  RayNeoOpenXRLoader.aar (OpenXR loader for this runtime)
       |
RayNeo OpenXR runtime on the glasses (SLAM, 6DoF)
```

Each layer only uses the one below; apps can stop at any level.

## One session

```
Activity (main thread)                 render thread "xrspatial-render"
 FxrApi.initAndroid / bindService  -->  xrInitializeLoaderKHR, xrCreateInstance (+ Android create info)
 setWindowFocus / setSurface            EGL context on the SurfaceView, xrCreateSession (GLES binding)
 System.loadLibrary("openxr_loader")    loop: xrWaitFrame -> xrBeginFrame
                                              xrLocateSpace(VIEW, LOCAL)   -> PoseListener + WarmupGate
                                              xrLocateViews                -> EyeFrame matrices
                                              per eye: acquire swapchain image, XrRenderer.onDrawEye
                                              xrEndFrame with one projection layer
```

The RayNeo part is the bootstrap from RayNeo's samples: bind the runtime service and hand it the window.
The native part uses only standard OpenXR 1.0 calls, resolved from the loader the RayNeo SDK has loaded
(`dlsym` + `xrGetInstanceProcAddr`); the standard Khronos loader cannot find this runtime. The runtime
presents frames inside `xrEndFrame` on the render thread's EGL window surface.

## Repository layout

| Path | Contents |
| --- | --- |
| `xrspatial-core/` | Pure Kotlin: poses, quality gate, math types |
| `xrspatial/` | Android library; C++ OpenXR frame loop in `src/main/cpp` |
| `xrspatial-scene/` | Android library: scene, host, nodes, placement (no native code) |
| `sample/` | Cube app with its own `XrRenderer` |
| `sample-scene/` | Maintenance card from an XML layout |
| `tests/native/` | Host tests of the C++ headers against a fake OpenXR runtime |
| `tools/` | `run_native_tests.py` |
| `third_party/openxr/` | Khronos OpenXR 1.1.63 headers |
| `vendor/` | Where the RayNeo libraries go (ignored by git) |
| `docs/` | Documentation and demo media |
