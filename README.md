# RayNeo X3 Spatial SDK

Build spatial Android apps for **RayNeo X3 Pro** AR glasses in plain Kotlin: head 6DoF tracking and
world-locked content, **no Unity**.

Unofficial · Apache-2.0 · v0.2, experimental

> Not affiliated with, endorsed by or supported by RayNeo or TCL. This repository contains no RayNeo
> software; you download the RayNeo SDK yourself ([how](#2-get-the-rayneo-libraries)).

![World-locked cubes from the sample app, left eye, recorded on a RayNeo X3 Pro](docs/media/cubes-demo.gif)

*The cube sample on the glasses (left eye): the wearer steps sideways and forward, the cubes stay in place.
Recorded with `adb shell screenrecord`; the glasses are see-through, so the room is not in the picture.*

## What you get

The X3 Pro runs an OpenXR runtime with 6DoF tracking (SLAM) that RayNeo documents only for Unity. This
SDK talks to that runtime directly and gives you three levels to choose from:

| You want | Module | Main classes |
| --- | --- | --- |
| Cards, images and text in the room | `xrspatial-scene` | `SpatialScene`, `SpatialHost`, `SpatialPanel` |
| Your own OpenGL ES rendering | `xrspatial` | `XrSpatialSession`, `XrRenderer` |
| Raw head poses (robotics, vision, logging) | `xrspatial-core` | `PoseSample`, `WarmupGate` |

A card from an ordinary XML layout, fixed in the room 1.5 m in front of the wearer:

```kotlin
class MaintenanceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scene = SpatialScene()
        val card = layoutInflater.inflate(R.layout.maintenance_card, null)
        scene.add(SpatialPanel(card, widthMeters = 0.32f, placement = Placement.InFrontOfUser(1.5f)))
        SpatialHost.attach(this, scene)
    }
}
```

No OpenGL, C++ or lifecycle code: `SpatialHost` runs the tracking session with the Activity.

## Getting started

### 1. Requirements

- RayNeo X3 Pro (tested with OpenXR runtime 1.1.7.9)
- JDK 17, Android SDK 35, NDK 27.0.12077973, CMake 3.22.1
- Apps: `minSdk 30`, `arm64-v8a`

### 2. Get the RayNeo libraries

1. Download **RayNeo OpenXR ARDK 1.1.2** from
   [RayNeo's developer site](https://rayneo-en.gitbook.io/rayneo-devdoc/x-series/unity-sdk/ardk-download).
   It is a Unity package, but only two Android libraries are taken from it; Unity is not needed.
2. Copy `RayNeoSDK-client.aar` and `RayNeoOpenXRLoader.aar` from `OXR/Runtime/Plugins/Android/` into
   `vendor/rayneo-sdk-1.1.2/`.

The build checks their checksums ([vendor/README.md](vendor/README.md)). Never commit them: RayNeo's
licence does not allow redistribution.

### 3. Run the card sample

```bash
./gradlew :sample-scene:assembleDebug
```

```bash
adb install -r sample-scene/build/outputs/apk/debug/sample-scene-debug.apk
```

Open **XR Spatial Card** on the glasses. After about a second the card appears in front of you; walk
around and it stays in place. The cube sample is `:sample` ("XR Spatial Cubes").

### 4. Use it in your app

Include the modules from this repository and add the RayNeo libraries to your app:
[docs/integration.md](docs/integration.md).

## Status

Tested on one X3 Pro (runtime 1.1.7.9, RayNeo SDK 1.1.2):

- **Works:** tracking at 60 Hz; world-locked cubes and cards stay in place while walking and turning;
  stable start (nothing is drawn during the runtime's ~1 s of start-up garbage); pause and resume when
  another app takes focus; cards are centred, readable and correctly coloured.
- **Not measured yet:** metric scale, accuracy and drift, latency; other firmware versions.
- **Known issues:** position jumps were seen (~0.3–0.4 m after a ~90° head turn; once ~1 m in a 30 s run);
  the runtime's timestamps and tracking flags are not usable (see the runtime notes).

## Documentation

| Document | Contents |
| --- | --- |
| [docs/scene.md](docs/scene.md) | Scene API: panels, text, images, placement, designing for this display |
| [docs/api.md](docs/api.md) | Session, renderer, poses, quality gate, errors, lifecycle |
| [docs/integration.md](docs/integration.md) | Adding the SDK to an app; your own renderer |
| [docs/x3-pro-runtime-notes.md](docs/x3-pro-runtime-notes.md) | How the X3 Pro runtime deviates from OpenXR, and what the SDK does about it |
| [docs/architecture.md](docs/architecture.md) | How it works, repository layout |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Building, tests |
| [CHANGELOG.md](CHANGELOG.md) | Versions |

## Roadmap

- **0.3**: Jetpack Compose content in panels
- **0.4**: input (gaze, touchpad, maybe hand tracking); recorded pose traces for testing without the glasses
- **Later**: spatial anchors or OpenXR spatial containers, if the RayNeo runtime starts to support them

## Licence

Apache License 2.0 ([LICENSE](LICENSE), [NOTICE](NOTICE), [third-party notices](THIRD_PARTY_NOTICES.md)).
The RayNeo SDK is under RayNeo's own terms.
