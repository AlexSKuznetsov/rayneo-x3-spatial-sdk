# Contributing

Issues and pull requests are welcome, especially reports from other X3 Pro firmware/runtime versions.

## Build

Put the RayNeo libraries into `vendor/rayneo-sdk-1.1.2/` (see the README), set `sdk.dir` in
`local.properties`, then:

```bash
./gradlew :xrspatial:assembleRelease :xrspatial-scene:assembleRelease :sample:assembleDebug :sample-scene:assembleDebug :sample-input:assembleDebug
```

## Tests

Kotlin unit tests and lint (no device needed):

```bash
./gradlew :xrspatial-core:test :xrspatial:testDebugUnitTest :xrspatial-scene:testDebugUnitTest :sample:testDebugUnitTest :xrspatial:lintDebug :xrspatial-scene:lintDebug
```

Native tests of the C++ frame loop, instance setup, render maths and HUD against a fake OpenXR runtime (any
C++17 compiler: `$CXX`, `c++`, `clang++`, `g++`, or MSVC on Windows):

```bash
python tools/run_native_tests.py
```

Host tests do not prove tracking. Behaviour on the glasses is checked by running the samples; please say
which runtime version you tested with (`diagnostics()` prints it).

## Rules

- Never commit RayNeo binaries (`.aar`, `.so`) or APKs that contain them.
- Keep raw values raw: poses, timestamps and flags are reported as the runtime returns them; quality is
  judged separately (`WarmupGate`).
- Document device behaviour you rely on in `docs/x3-pro-runtime-notes.md`, with what was observed.
