# Adding the SDK to your app

There is no Maven artifact yet: include the modules from source, for example with this repository as a git
submodule next to your app.

## Gradle

`settings.gradle.kts` (leave out `xrspatial-scene` if you draw with your own renderer):

```kotlin
include(":xrspatial-core", ":xrspatial", ":xrspatial-scene")
project(":xrspatial-core").projectDir = file("rayneo-x3-spatial-sdk/xrspatial-core")
project(":xrspatial").projectDir = file("rayneo-x3-spatial-sdk/xrspatial")
project(":xrspatial-scene").projectDir = file("rayneo-x3-spatial-sdk/xrspatial-scene")
```

`gradle.properties`: where your copy of the RayNeo libraries is, relative to the root project:

```properties
rayneoSdkDir=rayneo-x3-spatial-sdk/vendor/rayneo-sdk-1.1.2
```

App module `build.gradle.kts`:

```kotlin
android {
    defaultConfig { minSdk = 30; ndk { abiFilters += "arm64-v8a" } }
    // The RayNeo SDK loads its native libraries from nativeLibraryDir, so they must be extracted.
    packaging { jniLibs.useLegacyPackaging = true }
}
dependencies {
    implementation(project(":xrspatial-scene"))  // or ":xrspatial" for your own renderer
    // The app packages the RayNeo libraries; the SDK only compiles against them.
    val rayneo = rootProject.file(providers.gradleProperty("rayneoSdkDir").get())
    implementation(files(rayneo.resolve("RayNeoSDK-client.aar"), rayneo.resolve("RayNeoOpenXRLoader.aar")))
}
```

The library manifest adds what the runtime needs: the `queries` entry for `com.rayneo.xr.runtime`, the two
OpenXR permissions and the `com.rayneo.mercury.app` marker the glasses' launcher looks for.

## With the scene API

`SpatialHost.attach(activity, scene)` in `onCreate` is all the Activity needs; see [scene.md](scene.md) and
the [`sample-scene`](../sample-scene) app.

## With your own renderer

The Activity owns a `SurfaceView` and runs an `XrSpatialSession` itself:

- start it once the Activity is resumed, has window focus and the surface exists;
- forward `onWindowFocusChanged` to the session;
- stop it in `onPause` and when the surface is destroyed;
- keep the display on (`FLAG_KEEP_SCREEN_ON`): the glasses switch it off after ~30 s without input.

[`CubesActivity`](../sample/src/main/kotlin/io/github/alexskuznetsov/xrspatial/sample/CubesActivity.kt) shows the
complete pattern and [`CubeSceneRenderer`](../sample/src/main/kotlin/io/github/alexskuznetsov/xrspatial/sample/CubeSceneRenderer.kt)
a renderer:

```kotlin
class MyRenderer : XrRenderer {
    override fun onGlReady() { /* create shaders and buffers */ }
    override fun onDrawEye(eye: EyeFrame) {
        Matrix.multiplyMM(mvp, 0, eye.projection, 0, eye.view, 0)
        // draw content placed in LOCAL coordinates (metres; -Z is ahead of where you looked at start)
    }
    override fun onGlRelease() { /* delete them */ }
}
```

Only poses, no drawing: call `session.start(listener)` without a renderer. The SDK still submits a black
(transparent) frame every display period, as the runtime requires.

## Things to know

- **Focus loss pauses tracking.** When another window takes focus (for example the system screen
  recorder), the runtime ends the session. The SDK reports `TrackingState.PAUSED` and starts a new session
  when focus returns; LOCAL is then re-created, so world-locked content is placed again. The on-glasses
  screen recorder therefore cannot record a running app; record from a computer with
  `adb shell screenrecord` instead.
- **Only usable poses drive content.** The runtime returns stale and broken poses for about a second at the
  start of every session; `WarmupGate` filters them and renderers are only called for usable frames.
- More in [x3-pro-runtime-notes.md](x3-pro-runtime-notes.md).
