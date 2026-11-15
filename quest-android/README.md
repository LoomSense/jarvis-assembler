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

With `replay=true` the camera reads `/sdcard/Android/data/com.agj.android/files/replay/` in the tools' format
(docs/QUEST_TESTING.md "Replay datasets"): `*.jpg` in name order, `intrinsics.json` `{fx, fy, cx, cy, width, height,
fps, distortion}`, optional `poses.jsonl` (`frame`, `file`, `cam_p`, `cam_q` = WORLD ← CAMERA with OpenCV axes), at
the dataset `fps`, looping, every frame delivered. In replay mode a `track` event is logged for every frame, with
`frame` and `q`, so the run can be scored:

```sh
.venv/bin/python tools/quest/replay/make_marker_synthetic.py           # once, ~10 s
tools/quest/install.sh android --replay marker-synthetic
tools/quest/run.sh android --replay --secs 40
.venv/bin/python tools/quest/replay/score_track.py tools/quest/runs/<latest>-android
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
| | `JobRepository.kt` | `JobRepository` → `HttpJobRepository` (manifest + GLB download real; final poses TODO) |
| | `GhostRenderer.kt` | `GhostRenderer` → `StubGhostRenderer` |
| | `GhostMaterial.kt` + `app/src/shaders/ghost_fresnel.*` | fresnel ghost material (compiled, not yet used) |
| `vision/` | `VisionModule.kt` | **the one entry point for MainActivity** (see "Vision and perception socket") |
| | `CameraSource.kt` | `CameraSource`, `CameraFrame` (lazy JPEG, luminance plane), `Intrinsics`, `GrayImage` |
| | `ReplayCameraSource.kt` | replay folder in the tools' format, dataset fps (real, JVM-tested) |
| | `LiveCameraSource.kt` | Camera2 passthrough capture: YUV → gray + lazy JPEG, intrinsics, lens pose, head pose (written, not run) |
| | `SpatialInput.kt` | Spatial SDK system: head pose buffer, hand joints, render fps (written, not run) |
| | `Frames.kt` | frame conversions (Spatial LH ↔ WORLD RH, lens pose, Rodrigues), `MarkerConfig`, `MarkerPlacement` |
| | `MarkerDetector.kt` | OpenCV AprilTag 36h11 + `solvePnPGeneric(IPPE_SQUARE)` (JVM-tested on the synthetic set) |
| | `MarkerTracker.kt` | `MarkerTracker` (ObjectTracker), `MarkerSolver` (ambiguity, world poses) |
| | `TrackingCore.kt` | filter + hold + touch fallback + the AGQ `track` event (pure, tested) |
| | `PoseFilter.kt` | `OneEuroPoseFilter` (outlier rejection, re-lock, hold), `ExpPoseFilter` (phase 1) |
| | `HeadPoseBuffer.kt`, `Yuv.kt`, `VisionConfig.kt` | head pose at capture time; NV21 resize, stream intrinsics; `vision.json` |
| | `Hands.kt`, `TouchTracker.kt` | hand summary (port of handChecks.ts), fingertip hold, two-corner snap (port of register.ts) |
| `net/` | `ServerApi.kt` | `ServerApi` → `HttpServerApi` (OkHttp, all REST calls) |
| | `PerceptionSocket.kt` | `PerceptionSocket` → `OkHttpPerceptionSocket` (flow control, reconnect, AGQ; MockWebServer-tested) |
| `ui/` | `CoachFlow.kt` | look → identify → build → locate → register → guide (pure, tested) |
| | `Panels.kt` | glass `StepPanel` + toolbar ornament, `LabelPill`, `Hud` (Compose, world-space panels) |
| | `Strings.kt` | EL/EN copy (from client/src/xr/i18n.ts and XR_NATIVE_SPEC §5.4), Greek caps without tonos |
| | `WindowFollow.kt` | step window placement beside the object, spring + dead band (pure, tested) |
| | `Voice.kt` | `Voice` → `ServerVoice` (`/api/tts`, cache, prefetch), `StubVoice` without a server |
| | `Jpeg.kt` | camera JPEG → 768 px q70 for identify/locate |

Frames and units (all metres, right-handed): WORLD = the Spatial SDK tracking space mirrored in Z (+Y up, +Z back;
the SDK itself is left-handed, convert with `vision/Frames.kt`); ASSEMBLY = GLB root
`assembly` (glTF Y-up; manifest vectors are Blender Z-up, convert with `blenderToGltf`); CAMERA = OpenCV axes
(+X right, +Y down, +Z forward). `aFromB` maps B → A.

## Vision and perception socket (round 1)

**Offline accuracy** (JVM, desktop OpenCV 4.9 via `org.openpnp:opencv`, the device code path `ReplayDataset` →
`MarkerTracker` → `TrackingCore`; `./gradlew test` writes `tools/quest/runs/android-jvm-marker-synthetic/agq.jsonl`,
scored with `score_track.py`): 300/300 frames tracked, position error p50 1.37 mm / p90 1.83 mm / max 1.97 mm, bias
1.19 mm, still jitter 0.014 mm, yaw error p50 0.01°, 2–3 ms per 1280×960 frame on the Mac. Reference tracker on the
same frames: p50 1.37 / p90 1.86 mm. Skipped (not failed) when the dataset is not generated.

**Wiring** (MainActivity owns the calls; the module owns the camera, tracking and the `track` log):

```kotlin
vision = VisionModule(this, params.replay)                 // reads files/vision.json if present
systemManager.registerSystem(vision.input)                  // head pose + hands + render fps, every frame
vision.listener = object : VisionListener {                 // all optional
  override fun onFrame(frame: CameraFrame, track: TrackSnapshot) { /* read frame.jpeg HERE if needed */ }
  override fun onTrackState(s: TrackSnapshot) {}
  override fun onHands(h: HandSummary) { socket.sendHands(h) }
}
vision.start()                                              // after the camera permissions (at once in replay)
vision.spatialPose()?.let { p -> Pose(Vector3(p[0], p[1], p[2]), Quaternion(p[6], p[3], p[4], p[5])) }  // ghost root
vision.stop()                                               // onDestroy
```

Frames: our WORLD is right-handed (+Z back, OpenXR); the **Spatial SDK is left-handed (+Z forward)** (its Scene docs;
`Vector3.Forward = (0,0,1)` in the 0.15.0 jar). `Frames.spatialToWorld` / `worldToSpatial` mirror Z; use
`vision.spatialPose()` for Spatial `Transform`s. Lens pose: HEAD ← CAMERA(OpenCV) = (`LENS_POSE_TRANSLATION`,
conj(`LENS_POSE_ROTATION`)), which is Android's definition and, mirrored, exactly Meta's showcase formula (unit test
`lensPoseMatchesMetaShowcaseConversion`). Camera pose = head pose at the frame's `SENSOR_TIMESTAMP` (ring buffer,
interpolated) × HEAD ← CAMERA.

Marker on the MALM drawer front: ASSEMBLY ← MARKER = 180° about X, t = (0, 0.065, 0) m: the front's inside face is
the plane Z = 0 of the GLB `assembly` frame (sides at Z < 0), the groove is at Y ≈ 0.015, the marker sits centred
between the groove and the top edge (Y 0.015…0.115 for the 100 mm cut square), TOP toward the groove. Override in
`vision.json` (`marker_offset_m`, `marker_q`).

`track` (10 Hz live, every frame in replay): `fps` (render), `tracked`, `pos_m` (marker point, WORLD), `yaw_deg`,
`reproj_px`, `source` (`marker`, `touch`, `anchor` = held after the marker was lost, `none`), `frame` + `q` (replay),
`state` (`tracked`/`held`/`lost`), `method`, `pos`, `rot`, `ms`, `raw_pos_m`, `asm_pos_m`, `age_ms`, `cam_fps`,
`solve_hz`. When the marker is hidden (MALM steps 5+) the last pose is held forever (`tracked:false`, `source:anchor`).
Other events: `camera` (all Camera2 values), `camera_frame` (first frame: capture age, head-pose availability),
`hands_source`, `vision`, `touch`, `perception`, `perception_stats`.

`vision.json` (`/sdcard/Android/data/com.agj.android/files/vision.json`, all optional) lets a device run try the
UNVERIFIED choices without a rebuild: `camera_position` (0 left), `lens_convention` (`android`/`direct`/`forward`),
`head_time_offset_ms`, `unknown_ts_latency_ms`, `track_log_hz`, `use_distortion`, `aruco3`, `marker_size_m`,
`marker_offset_m`, `marker_q`, One-Euro `pos_min_cutoff_hz`/`pos_beta`/`rot_min_cutoff_hz`/`rot_beta`.

Perception socket: `OkHttpPerceptionSocket(server, key, Agq.log)`, `connect(job, listener)`, then `start(step,
action)`, `sendFrame(jpeg, step, tsMs)` (false = dropped by `hello.limits`: max_unacked, max_hz, ack timeout),
`sendHands`, `step`, `action`, `stop`, `close`. Typed callbacks `onStepComplete`, `onWrongHardware`, `onFlipNeeded`,
`onUnsure`; reconnects with backoff and resumes the step.

## Open questions

- Lens pose axis conversion and camera-timestamp alignment (RESEARCH.md §1): needs a marker test on device.
- Spatial SDK fingertip joint API for the two-corner touch (ISDK / AvatarBody): to confirm.
- Needs a headset (UNVERIFIED until then): `SceneMesh.meshWithMaterials` with our arrays (materialRanges
  `[0, indexCount]`, colours `-1`), `MeshManager.registerMeshCreator` + `Mesh(Uri)` per part, the custom shader on
  those meshes, `Visible`/`TransformParent`/`Scale` on custom meshes and panels, label panel facing
  (`Quaternion.lookRotationAroundY(panel − head)`; if the pill shows its back, negate the vector), panel
  `DpPerMeterDisplayOptions(1000, 3)` density, Compose hover/press states on hand rays, MediaPlayer WAV playback.
- Step window starts at (0, 1.15, −0.8) in session space; it only follows once the guide runs.
- Lens pose axis conversion and camera-timestamp alignment (RESEARCH.md §1): derived and unit-tested against Meta's
  formula, still needs a marker test on device (`vision.json` `lens_convention`, `head_time_offset_ms`).
- Fingertips: `SpatialInput` tries the body-tracking skeleton (`Scene.updateBodyTrackingBuffersAtTime`, hand joints
  incl. index tips), else AvatarBody hand entities (wrist only, no touch registration). Which works: `hands_source`.
- Whether `getViewerPose()` is the current or the display-predicted head pose (affects the camera pose by up to
  ~40 ms of head motion).
- AGQ `t` is ms since start, but `tools/quest/agq.py` reads `t < 1e11` as seconds: rates and latencies from
  `summarize.py` are off by 1000× for this app until one side changes (core/AgqLog.kt, not changed here).
- GLB final transforms: parse the `walkthrough` clip's last keyframe from the file (the SDK does not seem to expose
  animation curves to Kotlin: UNVERIFIED).
- cpu/gpu frame times for the `frame` event (OVR Metrics feature?).
- Label text quality: Compose mesh panels vs. an SDF text shader.
- APK size: 114 MB debug; release with R8 and without unused SDK modules should be smaller.
