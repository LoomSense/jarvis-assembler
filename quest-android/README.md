# Assembly Guide: native Quest 3 app (Android, Kotlin, Meta Spatial SDK)

A new client for the same server (docs/HANDOFF.md §2): passthrough, the guide GLB on the real object, the
headset camera with intrinsics and pose, marker tracking, hands, voice. Framework choice and sources:
[RESEARCH.md](RESEARCH.md). Package id `com.agj.android` (the Unity app is `com.agj.unity`).

## Status (round 1 integrated)

One APK with guide/UI and vision/net wired together. Builds, 57 JVM unit tests pass (0 skipped once the synthetic
replay set is generated). **Never run on a headset** yet: everything that touches the SDK at runtime is UNVERIFIED
(see "First device run").

- **Wired in `MainActivity`:** `VisionModule` (live Camera2 with capture-time head poses, or replay; AprilTag strip
  tracking, One-Euro filter, hold, two-corner touch fallback, hand summaries; logs `track`, `camera`,
  `camera_frame`, `hands_source`), `OkHttpPerceptionSocket` (connect when the job loads, `start`/`step`/`action`
  from the guide, frames only when the socket has a free slot, hands at 10 Hz, server events → `onGuidanceEvent`
  on the main thread), the coach flow (identify → catalog/open + job polling → locate → register → guide), the
  `SpatialGhostRenderer`, glass step window + toolbar ornament, label pill, server voice, sounds.
- **JVM-tested:** GLB reader and guide model on two real jobs (`malm-drawer`, `knagglig`: every node within 1 mm of
  `target_m`, `max_err_mm` printed and logged in `job_loaded`), frame conventions end to end (`FrameConventionTest`),
  marker pipeline on the synthetic replay set with the device code path, perception socket (MockWebServer), params,
  AGQ JSON, coach flow, window follow.
- **Not yet:** hole ripple rings, dashed edges (wrong / lost: the ghost only fades), the guide drawing in the
  window's right column, parts ornament, mic state, voice commands, hand-dwell confirmation, window fade-relocate,
  Depth API occlusion.

## Toolchain (macOS arm64, as installed on Sun 15 Nov 2026)

| Tool | Version | How |
|---|---|---|
| JDK | 17.0.21 (Homebrew `openjdk@17`) | `brew install openjdk@17` |
| Android cmdline-tools | latest (sdkmanager 22.0) | `brew install --cask android-commandlinetools`, then `sdkmanager "cmdline-tools;latest"` |
| SDK platform | android-34 (Horizon OS = Android 14) | `sdkmanager "platforms;android-34"` |
| Build tools | 35.0.0 | `sdkmanager "build-tools;35.0.0"` |
| NDK | 27.0.12077973 (only for `glslc`, shader compile) | `sdkmanager "ndk;27.0.12077973"` |
| Licenses | accepted | `yes \| sdkmanager --licenses` |
| Gradle | 8.14.3 (wrapper) | `./gradlew` downloads it |
| AGP / Kotlin | 8.13.0 / 2.2.0 | `gradle/libs.versions.toml` |
| Meta Spatial SDK | 0.15.0 (Maven Central) + Spatial Gradle plugin 0.15.0 | same |
| OpenCV | 4.10.0 (`org.opencv:opencv`, Maven Central); tests: desktop 4.9 (`org.openpnp:opencv`) | same |
| adb | 37.0.1 | `/opt/homebrew/bin/adb` |

Every shell that builds needs:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
```

## Build and test

```sh
cd quest-android
./gradlew assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk (~115 MB, arm64 only, debug)
./gradlew test              # JVM unit tests
```

Clean `assembleDebug` about 1.5 min (incremental ~15 s). Most of the APK is native libraries: Spatial SDK 46 MB,
OpenCV 21 MB, MRUK 6 MB.

The marker accuracy test (`MarkerReplayAccuracyTest`) needs the synthetic set, else it is skipped:

```sh
.venv/bin/python tools/quest/replay/make_marker_synthetic.py          # once, ~12 s, 23 MB, git-ignored
(cd quest-android && ./gradlew test)                                   # writes tools/quest/runs/android-jvm-marker-synthetic/
.venv/bin/python tools/quest/replay/score_track.py tools/quest/runs/android-jvm-marker-synthetic
.venv/bin/python tools/quest/summarize.py tools/quest/runs/android-jvm-marker-synthetic
```

Measured (strip centre ground truth, 36h11 tag + offset): 300/300 tracked, position error p50 1.35 / p90 2.15 /
max 2.45 mm, still jitter 0.028 mm RMS, yaw p50 0.014°, rotation p50 0.087°, ~3 ms per 1280×960 frame on the Mac.

## Install and launch

Use the tools (they read the server URL and DEMO_KEY from `jobs/_host/url.txt` and `.env`):

```sh
tools/quest/doctor.sh
tools/quest/install.sh android                     # [--replay marker-synthetic] pushes a replay set too
tools/quest/run.sh android --job malm-drawer --lang el --hud --secs 60    # [--replay] [--reduced-motion]
tools/quest/capture.sh android
```

`run.sh` launch extras are exactly `Params`: `server`, `key`, `job`, `lang`, `replay`, `hud`, `reduced_motion`. By
hand:

```sh
adb shell am start -n com.agj.android/.MainActivity \
  --es server https://HOST --es key DEMO_KEY --es job malm-drawer --es lang el --ez replay false --ez hud true
adb logcat -s AGQ:I -v raw          # one JSON object per line
```

With `--es job malm-drawer` the app skips look/identify/build: it downloads manifest + GLB, opens the perception
socket, asks `/locate` with the latest camera frame (if any), arms the two-corner touch and waits up to 5 s for the
marker; without a pose it puts the drawer at a fixed table spot 0.55 m ahead and 0.45 m below the head (`coach`
`register: fixed_spot`) and starts the guide. Without `job` it starts at "Look at the box" and sends a frame to
`/api/identify` as soon as the camera runs. Next / Back walk the actions (`step` with `by: button`), "Where am I"
re-runs `/locate`, Repeat replays the line; perception `step_complete` jumps to the next step, `wrong_hardware` /
`flip_needed` turn the ghost red with the spoken reason, `unsure_show_part` amber.

Never commit the key. Instead of extras, the same keys can go in
`/sdcard/Android/data/com.agj.android/files/config.json` (`{"server": "...", "key": "...", "job": "...", "lang": "el",
"replay": true, "hud": true, "reduced_motion": false}`); extras override it (`config.json` is git-ignored).

First launch asks for `horizonos.permission.HEADSET_CAMERA`, `android.permission.CAMERA` and
`com.oculus.permission.USE_SCENE`. For unattended runs `adb shell pm grant com.agj.android <perm>` (**UNVERIFIED** for
HEADSET_CAMERA).

### Replay camera

With `replay=true` the camera reads `/sdcard/Android/data/com.agj.android/files/replay/` in the tools' format
(docs/QUEST_TESTING.md "Replay datasets"): `*.jpg` in name order, `intrinsics.json` `{fx, fy, cx, cy, width, height,
fps, distortion}`, optional `poses.jsonl` (`frame`, `file`, `cam_p`, `cam_q` = WORLD ← CAMERA with OpenCV axes), at
the dataset `fps`, looping. In replay a `track` event is logged for every frame with `frame` and `q`, so a device
run can be scored:

```sh
tools/quest/install.sh android --replay marker-synthetic
tools/quest/run.sh android --replay --secs 40
.venv/bin/python tools/quest/replay/score_track.py tools/quest/runs/<latest>-android
```

## Frames (one convention)

All app math is right-handed, metres:

- **WORLD** = OpenXR tracking space: +X right, +Y up, −Z forward at session start.
- **ASSEMBLY** = GLB root node `assembly` (glTF, +Y up). Manifest vectors are Blender Z-up → `blenderToGltf`.
- **CAMERA** = OpenCV (+X right, +Y down, +Z forward). **MARKER** = the printed strip's centre, ArUco axes.
- `aFromB` maps B → A.

The **Spatial SDK is left-handed (+Z forward)** (its Scene docs; `Vector3.Forward = (0,0,1)` in the 0.15.0 jar), so
its world is WORLD mirrored in Z. The conversion happens in one place, built on `Frames.mirrorZ`:
`guide/SpatialGhostRenderer.kt` `Pose3.toSdk()` (every entity Transform, root and children), `Vec3.toSdk()`,
`Pose.toPose3()` (viewer pose → WORLD; `SpatialInput` does the same for head and hands), `facingPose()` (panels:
position mirrored, rotation from the SDK's `lookRotationAroundY`), and `SpatialMesh.fromGltf` (mesh vertices and
normals mirrored, triangle winding reversed). Our meshes come from our own GLB reader, not the SDK's glTF importer.
`FrameConventionTest` simulates the SDK tree in Spatial axes: the left side panel stays on the wearer's left, the
drawer front is nearer than its back, every part keeps its head-relative place for arbitrary poses, faces keep their
outward side, the fixed spot and the step window are in front of the wearer.

## Vision and perception socket

`VisionModule` is the vision package's single entry point (see its KDoc). Camera pose = head pose at the frame's
`SENSOR_TIMESTAMP` (ring buffer, interpolated) × HEAD ← CAMERA from Camera2 `LENS_POSE_*` (Android's definition,
unit-tested equal to Meta's showcase formula; `vision.json` `lens_convention` switches it on device).

**Marker:** the dual strip (tools/quest/marker/marker.json): 36h11 id 0 (80 mm, detected with OpenCV) at −50 mm and
41h12 id 0 at +50 mm from the strip centre. The app detects the 36h11 tag and reports the **strip centre**:
WORLD ← STRIP = WORLD ← TAG × Translation(−`offset_m`) (`MarkerConfig.tagOffset`, a test keeps it equal to
marker.json). The strip centre sits where the single tag's centre was on the MALM drawer front, so ASSEMBLY ← MARKER
= 180° about X, t = (0, 0.065, 0) m (inside face = plane Z = 0, TOP toward the groove; derivation in
`vision/Frames.kt` `MarkerPlacement`). The GLB's own `marker` node (manifest `markerNode`) is the screw-hole ring of
the viewer, not the sticker, so it is not used for this.

`vision.json` (`/sdcard/Android/data/com.agj.android/files/vision.json`, all optional) tries UNVERIFIED choices
without a rebuild: `camera_position` (0 left), `lens_convention` (`android`/`direct`/`forward`),
`head_time_offset_ms`, `unknown_ts_latency_ms`, `track_log_hz`, `use_distortion`, `aruco3`, `marker_size_m`,
`marker_tag_offset_m` (`[0,0,0]` for the old single-tag print), `marker_offset_m`, `marker_q`, One-Euro
`pos_min_cutoff_hz`/`pos_beta`/`rot_min_cutoff_hz`/`rot_beta`.

**Perception socket:** `OkHttpPerceptionSocket(server, key, Agq.log)`: `connect(job, listener)`, `start(step,
action)`, `sendFrame(jpeg, step, tsMs)` (false = dropped by `hello.limits`: max_unacked, max_hz, ack timeout),
`sendHands`, `step`, `action`, `stop`, `close`; reconnects with backoff and resumes the step. MainActivity encodes a
camera JPEG only when it is needed: the LOOK frame for identify, one a second for locate, and a perception frame
when `canSend()` (live frames encode lazily to 768 px q70).

## Logs (shared with the Unity app)

Tag `AGQ`, `{"t": epoch ms, "app": "android", "ev": ..., "t_rel": ms since start, ...}` (`t` = wall time at start +
monotonic clock). Events: `start` (version, params; key only as `key_set`), `job_loaded` (`ms`, `parse_ms`,
`parts`, `glb_bytes`, `max_err_mm`), `identify`, `locate`, `coach`, `panels`, `ghost_load`, `ghost_show`, `step`,
`guidance`, `tracking`, `voice`, `voice_fetch`, `finish`, `frame` (every 5 s: render_fps), `error`; from vision:
`vision` (config), `camera` (all Camera2 values), `camera_frame` (first frame: capture age, head-pose availability),
`hands_source`, `track`, `touch_start`, `touch`; from the socket: `perception` (hello, events with latency,
closed/failure), `perception_stats`.

`track` (10 Hz live, every frame in replay): `fps` (render), `tracked`, `pos_m` (strip centre, WORLD), `yaw_deg`,
`reproj_px`, `source` (`marker`, `touch`, `anchor` = held after the marker was lost, `none`), `frame` + `q`
(replay), `state` (`tracked`/`held`/`lost`), `method`, `pos`, `rot`, `ms`, `raw_pos_m`, `asm_pos_m`, `age_ms`,
`cam_fps`, `solve_hz`. When the marker is hidden (MALM steps 5+) the last pose is held (`source: anchor`).

## Module map

Each module sits behind an interface; `MainActivity.kt` only wires them.

| Package | File | What |
|---|---|---|
| `core/` | `Params.kt` | launch parameters (extras over `config.json`) |
| | `AgqLog.kt`, `Agq.kt` | AGQ JSON logger (pure) + logcat sink |
| | `Geom.kt` | `Vec3`, `Quat`, `Pose3`, `blenderToGltf` |
| `guide/` | `Assembly.kt` | manifest model (lenient), `Assembly` |
| | `Glb.kt` | GLB reader: nodes, meshes, animation sampling (pure, tested) |
| | `Guide.kt` | steps → actions, geometry, labels, step poses, `maxTargetErrorMm` (pure, tested) |
| | `JobRepository.kt` | `HttpJobRepository` (download + parse + build, `job_loaded`) |
| | `GhostRenderer.kt` | `GhostRenderer` interface + stub |
| | `SpatialGhostRenderer.kt` | the Spatial SDK implementation; **the only RH → LH conversion** (`toSdk`, `toPose3`, `facingPose`) |
| | `Shapes.kt` | procedural meshes, insertion path, `SpatialMesh.fromGltf` |
| | `GhostMaterial.kt` + `app/src/shaders/ghost_fresnel.*` | fresnel material, token tints |
| `vision/` | `VisionModule.kt` | **the one vision entry point for MainActivity** |
| | `CameraSource.kt`, `LiveCameraSource.kt`, `ReplayCameraSource.kt` | Camera2 capture (YUV → gray + lazy JPEG, intrinsics, lens pose, head pose) / replay folder |
| | `SpatialInput.kt`, `HeadPoseBuffer.kt` | Spatial SDK system: head pose ring buffer, hand joints, render fps |
| | `Frames.kt` | frame conversions, `MarkerConfig` (strip tag offset), `MarkerPlacement` |
| | `MarkerDetector.kt`, `MarkerTracker.kt` | OpenCV AprilTag 36h11 + `solvePnPGeneric(IPPE_SQUARE)`, ambiguity, strip centre |
| | `TrackingCore.kt`, `PoseFilter.kt` | One-Euro filter + hold + touch fallback + the `track` event |
| | `Hands.kt`, `TouchTracker.kt` | hand summary (port of handChecks.ts), two-corner snap (port of register.ts) |
| | `Yuv.kt`, `VisionConfig.kt` | NV21 resize, stream intrinsics; `vision.json` |
| `net/` | `ServerApi.kt` | `HttpServerApi` (OkHttp, all REST calls) |
| | `PerceptionSocket.kt` | `OkHttpPerceptionSocket` (flow control, reconnect, AGQ) |
| `ui/` | `CoachFlow.kt` | look → identify → build → locate → register → guide (pure, tested) |
| | `Panels.kt` | glass `StepPanel` + toolbar ornament, `LabelPill`, `Hud` (Compose, world-space panels) |
| | `Strings.kt` | EL/EN copy |
| | `WindowFollow.kt` | step window beside the object (spring + dead band); `Placement` (fixed spot, panels ahead) |
| | `Voice.kt`, `Sounds.kt`, `Jpeg.kt` | server TTS + cache, synthesised cues, JPEG downscale |

## First device run: what to check

docs/QUEST_TESTING.md and eval/judge/round-1.md §5 have the full script. App-specific checks, with the log line
that answers each:

1. `camera` event: a camera id with `position` 0 (left), intrinsics non-zero, `LENS_POSE_*` present; else
   `vision.json` `camera_position`.
2. `camera_frame`: `head_found: true`, `head_samples` > 0, `cam_p` set and `age_ms` of tens of ms.
   `head_found: false` = the timestamp clock (`ts_source`) does not match `elapsedRealtimeNanos`
   (`unknown_ts_latency_ms`, `head_time_offset_ms`).
3. `hands_source`: `skeleton` (fingertips, touch registration works) or `avatar_body` (wrists only).
4. Frames: the step window and the fixed spot appear **in front** (not behind); the drawer ghost is not mirrored
   (left side panel on the left); labels and the window show their front. If panels show their back, negate the
   vector in `facingPose` (one place).
5. Marker: with the strip on the drawer front, `track` `state: tracked` at ~10 Hz and the ghost on the drawer. If
   it is offset by a constant, try `lens_convention` and `head_time_offset_ms` in `vision.json`.
6. Socket: `perception` `kind: hello`, then `perception_stats` with frames sent and acks; hands every 100 ms.

## Open questions

- Lens pose axis convention and camera-timestamp alignment (RESEARCH.md §1): derived and unit-tested against Meta's
  formula, needs the marker on device.
- Whether `getViewerPose()` is the current or the display-predicted head pose (up to ~40 ms of head motion).
- Fingertips: body-tracking skeleton vs AvatarBody (`hands_source`).
- SDK runtime details (UNVERIFIED): `SceneMesh.meshWithMaterials` with our arrays, `registerMeshCreator` +
  `Mesh(Uri)` per part, the custom shader on those meshes, `Visible`/`TransformParent`/`Scale` on custom meshes and
  panels, panel facing, `DpPerMeterDisplayOptions(1000, 3)` density, Compose hover/press on hand rays, MediaPlayer
  WAV playback; that the SDK rotates vectors with the standard quaternion formula (the frame test assumes it).
- Two-corner touch corners come from `overall_mm` with the origin assumed centred (`Assembly.frontCorners`).
- cpu/gpu frame times for the `frame` event (OVR Metrics?). Label text quality (Compose mesh panels vs SDF).
- APK size: 115 MB debug; release with R8 and without unused SDK modules should be smaller.
