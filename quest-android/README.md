# Assembly Guide: native Quest 3 app (Android, Kotlin, Meta Spatial SDK)

A new client for the same server (docs/HANDOFF.md §2): passthrough, the guide GLB on the real object, the
headset camera with intrinsics and pose, marker tracking, hands, voice. Framework choice and sources:
[RESEARCH.md](RESEARCH.md). Package id `com.agj.android` (the Unity app is `com.agj.unity`).

Status (phase 1): skeleton builds, unit tests pass, **never run on a headset**. Real: params/config, AGQ logger,
HUD and step panel (world-space Compose panels), passthrough on, replay camera, Camera2 passthrough-camera discovery,
REST client, manifest model, two-corner solver, marker pipeline (OpenCV, compiled, not run), fresnel ghost shader
(compiled). Stubs: live camera capture, GLB final poses, ghost renderer, perception socket, voice.

## Toolchain (macOS arm64, as installed on Sun 15 Nov 2026)

| Tool | Version | How |
|---|---|---|
| JDK | 17.0.21 (Homebrew `openjdk@17`) | `brew install openjdk@17` |
| Android cmdline-tools | latest (sdkmanager 22.0) | `brew install --cask android-commandlinetools` (already present), then `sdkmanager "cmdline-tools;latest"` |
| SDK platform | android-34 (Horizon OS = Android 14) | `sdkmanager "platforms;android-34"` |
| Build tools | 35.0.0 | was present (`sdkmanager "build-tools;35.0.0"`) |
| NDK | 27.0.12077973 (only for `glslc`, shader compile) | `sdkmanager "ndk;27.0.12077973"` |
| Licenses | accepted | `yes \| sdkmanager --licenses` |
| Gradle | 8.14.3 (wrapper) | `./gradlew` downloads it |
| AGP / Kotlin | 8.13.0 / 2.2.0 | `gradle/libs.versions.toml` |
| Meta Spatial SDK | 0.15.0 (Maven Central) + Spatial Gradle plugin 0.15.0 | same |
| OpenCV | 4.10.0 (`org.opencv:opencv`, Maven Central) | same |
| adb | 37.0.1 | `/opt/homebrew/bin/adb` |

Exact commands used:

```sh
brew install openjdk@17
yes | sdkmanager --sdk_root=$HOME/Library/Android/sdk "platforms;android-34" "cmdline-tools;latest"
yes | sdkmanager --sdk_root=$HOME/Library/Android/sdk "ndk;27.0.12077973"
```

Every shell that builds needs:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
```

## Build and test

```sh
cd quest-android
./gradlew assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk (~114 MB, arm64 only, debug)
./gradlew test              # JVM unit tests (params, AGQ JSON, manifest, frame packing, snap, coach, replay)
```

Measured here: clean `assembleDebug` 1 min 31 s; `test` 17 tests, 0 failures. Most of the APK is native
libraries: Spatial SDK 46 MB, OpenCV 21 MB, MRUK 6 MB.

## Install and launch

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.agj.android/.MainActivity \
  --es server https://HOST --es key DEMO_KEY --es job malm-drawer --es lang el --ez replay true --ez hud true
adb logcat -s AGQ:I -v raw          # one JSON object per line
```

Never commit the key. Instead of extras, the same keys can go in
`/sdcard/Android/data/com.agj.android/files/config.json` (`{"server": "...", "key": "...", "job": "...", "lang": "el",
"replay": true, "hud": true}`); extras override it. Push with
`adb push config.json /sdcard/Android/data/com.agj.android/files/config.json` (`config.json` is git-ignored).

First launch asks for the headset-camera permission (`horizonos.permission.HEADSET_CAMERA`) and spatial data
(`USE_SCENE`). For unattended runs: `adb shell pm grant com.agj.android horizonos.permission.HEADSET_CAMERA`
(**UNVERIFIED** that `pm grant` works for this permission) and `android.permission.CAMERA`.

### Replay camera

With `replay=true` the camera reads `/sdcard/Android/data/com.agj.android/files/replay/`: `*.jpg` in name order,
`intrinsics.json` `{fx, fy, cx, cy, width, height}`, optional `poses.jsonl` (one line per frame, WORLD ← CAMERA with
OpenCV axes: `{"frame": i, "pos_m": [x,y,z], "quat_xyzw": [x,y,z,w]}`; `p`/`q` and `"file": "<name>"` accepted),
at 10 fps, looping. The `poses.jsonl` field names are a proposal until tools/quest/replay/ fixes them.

```sh
adb push tools/quest/replay/<dataset>/. /sdcard/Android/data/com.agj.android/files/replay/
```

## Logs (shared with the Unity app)

Tag `AGQ`, `{"t": ms since start, "app": "android", "ev": ..., ...}`. Events: `start` (version, params; key only as
`key_set`), `job_loaded`, `identify`, `locate`, `track` (every 1 s: fps = camera fps, tracked, pos_m, yaw_deg,
reproj_px, source), `perception`, `step`, `frame` (every 5 s: render_fps, cpu_ms, gpu_ms = null for now), `error`.
Extra: `camera` (discovery result: ids, intrinsics, lens pose, JPEG sizes), `coach` (stage changes), `ghost_show`,
`voice`.

## Module map

Each module sits behind an interface so different people can work in parallel without touching the same files.
`MainActivity.kt` only wires them.

| Package | File | Interface → phase 1 implementation |
|---|---|---|
| `core/` | `Params.kt` | launch parameters (extras over `config.json`) |
| | `AgqLog.kt`, `Agq.kt` | AGQ JSON logger (pure) + logcat sink |
| | `Geom.kt` | `Vec3`, `Quat`, `Pose3`, frames, `blenderToGltf` |
| `guide/` | `Assembly.kt` | manifest model (lenient), `Assembly` |
| | `JobRepository.kt` | `JobRepository` → `HttpJobRepository` (manifest + GLB download real; final poses TODO) |
| | `GhostRenderer.kt` | `GhostRenderer` → `StubGhostRenderer` |
| | `GhostMaterial.kt` + `app/src/shaders/ghost_fresnel.*` | fresnel ghost material (compiled, not yet used) |
| `vision/` | `CameraSource.kt` | `CameraSource`, `CameraFrame`, `Intrinsics` |
| | `ReplayCameraSource.kt` | replay folder at 10 fps (real) |
| | `LiveCameraSource.kt` | Camera2 passthrough discovery (real), capture TODO |
| | `ObjectTracker.kt` | `ObjectTracker`, `TrackResult` |
| | `MarkerTracker.kt` | AprilTag 36h11 + IPPE_SQUARE PnP (written, not run) |
| | `TouchTracker.kt` | two-corner snap (`solveSnap` real, fingertip input TODO) |
| | `PoseFilter.kt` | `PoseFilter` → `ExpPoseFilter` (One-Euro TODO) |
| `net/` | `ServerApi.kt` | `ServerApi` → `HttpServerApi` (OkHttp, all REST calls) |
| | `PerceptionSocket.kt` | `PerceptionSocket` (+ `packFrame`, tested) → `StubPerceptionSocket` |
| `ui/` | `CoachFlow.kt` | look → identify → build → locate → register → guide (pure, tested) |
| | `Panels.kt` | `StepPanel`, `Hud` (Compose, world-space panels) |
| | `Voice.kt` | `Voice` → `StubVoice` |

Frames and units (all metres, right-handed): WORLD = Spatial SDK tracking space (+Y up); ASSEMBLY = GLB root
`assembly` (glTF Y-up; manifest vectors are Blender Z-up, convert with `blenderToGltf`); CAMERA = OpenCV axes
(+X right, +Y down, +Z forward). `aFromB` maps B → A.

## Open questions

- Lens pose axis conversion and camera-timestamp alignment (RESEARCH.md §1): needs a marker test on device.
- Spatial SDK fingertip joint API for the two-corner touch (ISDK / AvatarBody): to confirm.
- GLB final transforms: parse the `walkthrough` clip's last keyframe from the file (the SDK does not seem to expose
  animation curves to Kotlin: UNVERIFIED).
- cpu/gpu frame times for the `frame` event (OVR Metrics feature?).
- Label text quality: Compose mesh panels vs. an SDF text shader.
- APK size: 114 MB debug; release with R8 and without unused SDK modules should be smaller.
