# Third-party notices

| Component | Where | Source | Licence | Shipped in this repository |
| --- | --- | --- | --- | --- |
| Khronos OpenXR API headers 1.1.63 | `third_party/openxr/include/openxr/` | <https://github.com/KhronosGroup/OpenXR-SDK>, tag `release-1.1.63` (`f2448a8797c85814aa892efc1ab8707900fbcc78`) | Apache-2.0 OR MIT (`third_party/openxr/LICENSE`) | Yes, unmodified |
| Gradle Wrapper 8.11.1 | `gradlew`, `gradlew.bat`, `gradle/wrapper/` | <https://gradle.org> | Apache-2.0 | Yes |
| RayNeo OpenXR ARDK 1.1.2: `RayNeoSDK-client.aar`, `RayNeoOpenXRLoader.aar` | `vendor/rayneo-sdk-1.1.2/` (user-provided) | <https://rayneo-en.gitbook.io/rayneo-devdoc/x-series/unity-sdk/ardk-download> | RayNeo's terms (package licence string "OpenXR RAYNEO"; no redistribution grant found) | **No.** Never committed; the `xrspatial` library only compiles against the client classes, apps package both files themselves |

Build-time dependencies resolved by Gradle (Android Gradle Plugin, Kotlin, JUnit) are under their own
licences and are not part of this repository.
