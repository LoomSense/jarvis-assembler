# Assembly Guide: native Quest 3 app (Android, Kotlin, Meta Spatial SDK)

A new client for the same server (docs/HANDOFF.md §2): passthrough, the guide GLB on the real object, the
headset camera with intrinsics and pose, marker tracking, hands, voice. Framework choice and sources:
[RESEARCH.md](RESEARCH.md). Package id `com.agj.android` (the Unity app is `com.agj.unity`).

Status (round 1, guide + UI): builds, 29 unit tests pass, **never run on a headset** (none was connected).

- **Real and JVM-tested against the real job** (`app/src/test/resources/malm-drawer/`): own GLB reader
  (`guide/Glb.kt`: JSON + BIN chunks, node tree, mesh primitives, animation samplers), each node's final transform
  under `assembly` at the last keyframe of `walkthrough` (all 19 part/hardware nodes within 2 mm of the manifest's
  `target_m` after Z-up → Y-up, scale 1), each step's pose (the `assembly` node at the step's end in the clip),
  the `Guide` model (steps → actions, labels "Μικρή βίδα · 100365 2 από 4" from `names.el/en`, navigation that skips
  `note` actions), insertion paths, window follow spring.
- **Real, compiled, not run:** `SpatialGhostRenderer` (every part is its own mesh built from the parsed GLB, fresnel
  ghost material, placed parts subtle, one ghost at a time, mover loop along `from_dir` (1.5 s), dotted path with
  travelling dash + arrowhead, 290° turning arrow for fasten/tighten, turn preview for `turn` actions, label pill in a
  mesh-mode Compose panel with leader line, correct/wrong/unsure/lost states); glass step window with toolbar
  ornament (Back · Where am I · Repeat · Next), window follows the object (critically damped, 3 cm dead band, never
  head-locked); coach flow wired to `ServerApi` (identify → catalog/open + job polling → locate → register → guide);
  server voice (`/api/tts` → WAV → MediaPlayer, cached, next two lines prefetched).
- From phase 1: params/config, AGQ logger, HUD, passthrough, replay camera, Camera2 discovery, REST client,
  two-corner solver, marker pipeline (vision/, owned by the vision agent).
- Spec pass (XR_NATIVE_SPEC v1 on main): window 0.52 × 0.34 m (coach cards compact 0.36 × 0.20 m), em sizes
  0.85° / 1.25° / 2.0° (12 / 17.5 / 28 sp at 1 dp = 1 mm), status line "ΒΗΜΑ 2 ΑΠΟ 7 · ΣΕΛΙΔΑ 5" with
  colour + shape + word, status pill above the window on tracking loss, glass edge band 0.90 → 0.80, buttons
  88/104 × 44 mm with hover 1.03 / press 0.97 springs and a tick at pinch-down, synthesised sound family
  (`ui/Sounds.kt`: tick, place, success, step, warning, unsure, complete, lost), Next = correct sequence (green, ghost
  fades 600 ms, chime) then the next ghost, `MainActivity.onGuidanceEvent(event, say)` for perception events (wrong →
  red + octagon + spoken reason; unsure → amber), reduced motion via `--ez reduced_motion true`.
- Not yet: hole ripple rings, dashed edges (wrong / lost: the ghost only fades), the guide drawing in the window's
  right column, parts ornament, mic state in the toolbar (Where am I takes that slot), voice commands, hand-dwell
  confirmation, window fade-relocate on turns, Depth API occlusion, wiring `PerceptionSocket` to `onGuidanceEvent`.

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

Measured here: clean `assembleDebug` 1 min 31 s (incremental 14 s); `test` 29 tests, 0 failures (round 1). APK 114.3 MB. Most of the APK is native
libraries: Spatial SDK 46 MB, OpenCV 21 MB, MRUK 6 MB.

## Install and launch

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.agj.android/.MainActivity \
  --es server https://HOST --es key DEMO_KEY --es job malm-drawer --es lang el --ez replay true --ez hud true
adb logcat -s AGQ:I -v raw          # one JSON object per line
```

With `--es job malm-drawer` the app skips look/identify/build: it downloads manifest + GLB, asks `/locate` with
the latest camera frame (if any), then waits up to 5 s for the marker; without a marker pose it puts the drawer at
a fixed table spot 0.55 m ahead and 0.45 m below the head (logged as `coach` `register: fixed_spot`) and starts the
guide. Without `job` it starts at "Look at the box" and sends a frame to `/api/identify` as soon as the camera runs.
Next / Back walk the actions (`step` log with `by: button`), "Where am I" re-runs `/locate`, Repeat replays the line.

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
Extra: `camera` (discovery result: ids, intrinsics, lens pose, JPEG sizes), `coach` (stage changes), `ghost_load`
(parts, ms), `ghost_show` (step, action, kind, node, path_dots), `voice` (lang, text, ms), `voice_fetch`, `finish`.
`job_loaded` also carries `parse_ms`, `parts`, `glb_bytes`.

## Module map

Each module sits behind an interface so different people can work in parallel without touching the same files.
`MainActivity.kt` only wires them.

| Package | File | Interface → phase 1 implementation |
|---|---|---|
| `core/` | `Params.kt` | launch parameters (extras over `config.json`) |
| | `AgqLog.kt`, `Agq.kt` | AGQ JSON logger (pure) + logcat sink |
| | `Geom.kt` | `Vec3`, `Quat`, `Pose3`, frames, `blenderToGltf` |
| `guide/` | `Assembly.kt` | manifest model (lenient), `Assembly` |
| | `Glb.kt` | GLB reader: nodes, meshes, animation sampling (pure, tested) |
| | `Guide.kt` | `Guide` / `GuideStep` / `GuideAction` / `PartGeometry` built from manifest + GLB (pure, tested) |
| | `JobRepository.kt` | `JobRepository` → `HttpJobRepository` (download + parse + build) |
| | `GhostRenderer.kt` | `GhostRenderer` (`setStep`, `setState`, `setAssemblyPose`, `setTablePose`, `tick`) |
| | `SpatialGhostRenderer.kt` | the Spatial SDK implementation (entities, meshes, animation, label) |
| | `Shapes.kt` | procedural meshes: dots, cone, cylinder, turning arrow, ring; insertion path |
| | `GhostMaterial.kt` + `app/src/shaders/ghost_fresnel.*` | fresnel material, token tints |
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
| | `Panels.kt` | glass `StepPanel` + toolbar ornament, `LabelPill`, `Hud` (Compose, world-space panels) |
| | `Strings.kt` | EL/EN copy (from client/src/xr/i18n.ts and XR_NATIVE_SPEC §5.4), Greek caps without tonos |
| | `WindowFollow.kt` | step window placement beside the object, spring + dead band (pure, tested) |
| | `Voice.kt` | `Voice` → `ServerVoice` (`/api/tts`, cache, prefetch), `StubVoice` without a server |
| | `Jpeg.kt` | camera JPEG → 768 px q70 for identify/locate |

Frames and units (all metres, right-handed): WORLD = Spatial SDK tracking space (+Y up); ASSEMBLY = GLB root
`assembly` (glTF Y-up; manifest vectors are Blender Z-up, convert with `blenderToGltf`); CAMERA = OpenCV axes
(+X right, +Y down, +Z forward). `aFromB` maps B → A.

## Open questions

- Lens pose axis conversion and camera-timestamp alignment (RESEARCH.md §1): needs a marker test on device.
- Spatial SDK fingertip joint API for the two-corner touch (ISDK / AvatarBody): to confirm.
- Needs a headset (UNVERIFIED until then): `SceneMesh.meshWithMaterials` with our arrays (materialRanges
  `[0, indexCount]`, colours `-1`), `MeshManager.registerMeshCreator` + `Mesh(Uri)` per part, the custom shader on
  those meshes, `Visible`/`TransformParent`/`Scale` on custom meshes and panels, label panel facing
  (`Quaternion.lookRotationAroundY(panel − head)`; if the pill shows its back, negate the vector), panel
  `DpPerMeterDisplayOptions(1000, 3)` density, Compose hover/press states on hand rays, MediaPlayer WAV playback.
- Step window starts at (0, 1.15, −0.8) in session space; it only follows once the guide runs.
- cpu/gpu frame times for the `frame` event (OVR Metrics feature?).
- Label text quality: Compose mesh panels vs. an SDF text shader.
- APK size: 114 MB debug; release with R8 and without unused SDK modules should be smaller.
