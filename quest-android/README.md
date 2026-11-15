# Assembly Guide: native Quest 3 app (Android, Kotlin, Meta Spatial SDK)

A new client for the same server (docs/HANDOFF.md §2): passthrough, the guide GLB on the real object, the
headset camera with intrinsics and pose, marker tracking, hands, voice. Framework choice and sources:
[RESEARCH.md](RESEARCH.md). Package id `com.agj.android` (the Unity app is `com.agj.unity`).

## Status (round 1 integrated; round 2 vision/net/world on branch)

Round 2 (vision/net/world, JVM-tested, not yet on a headset): camera-pose diagnosis and clock fallback, MRUK table
placement without a marker, parts in the pile in 3D (`/parts` → rays → depth/table). See "World" below; the
MainActivity wiring is in `world/WorldModule.kt`'s KDoc.

One APK with guide/UI and vision/net wired together. Builds, 98 JVM unit tests pass (0 skipped once the synthetic
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
- **Round 2 (guide/UI, not yet on a headset):** ghost shader fix + unlit fallback + `ghost_material` event; hard
  edges (dihedral > 30°, 1.5 mm) on the ghost; wrong = red dashed outline (8/5 mm, 2 mm) pulsing 3 × at 0.83 Hz +
  octagon ✕ in the label + a spoken reason (the event's `say`, else a generic line); lost = ghost 30 % + dashed
  edges; hole lip ring + two ripples (0.5 Hz) on fasten/tighten/press actions, dim static rings on the step's other
  holes of the same code; the guide drawing (`/files/{job}/crops/step-NN.png`, or the step's `crop`) as white line
  art in the window's right column (40 %); the parts card ("ΠΑΡΕ", turntable wireframe of the part's hard edges at
  20°/s, name, code, count) on the window's outer edge; window fade-relocate (`WindowRelocator`); push-to-talk voice
  commands on the toolbar mic (Back, Repeat, mic, Next); camera preview (hud only). 70 JVM tests (1 skipped without
  the synthetic replay set).
- **Not yet:** drawing the camera-preview box for `/parts` (`showCameraBox`), hand-dwell confirmation, Depth API occlusion, long pinch on the window for push-to-talk, the wrong
  part's own position (the red outline is drawn on the current target, the server does not send where the wrong
  part is), check badge as a separate 3D badge (the status shapes live in the label pill and the window).

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

## Ghost material (first device run: solid orange ghost)

Cause, from the SDK 0.15.0 classes and Meta's custom-shader docs: a custom material's vec4 attributes are packed
into `set 3, binding 0` **in the order they are declared**, while the round-1 shaders included the SDK's default
`MaterialUniform` block (`matParams, alphaParams, stereoParams, emissiveFactor, albedoFactor, …`) and so read the
tint from past the end of the 4-vec4 buffer; and `SceneMaterial.custom(name, attrs)` defaults to
`AlphaMode.OPAQUE`. Round 2: `ghost_fresnel.vert/.frag` declare their own block (`albedoFactor, matParams,
stereoParams`, = `GhostMaterial.ATTRIBUTES`, a JVM test keeps them equal), the material is created
`AlphaMode.TRANSLUCENT`, and the plugin now also emits `ghost_fresnel.frag.mv.spv` (the native loader looks for
`.frag.mv.spv` under multiview; round 1 had only `.frag.spv`). If creating the custom material throws, or with
`--es ghost_material unlit`, every role uses the SDK's unlit shader, translucent, azure `#9ecfff`, alpha = fill +
30 % of the rim (0.14 + 0.195), and the hard-edge meshes give the brighter edges. AGQ `ghost_material` (once, at
the first ghost): `kind` (`custom_fresnel` | `unlit_translucent`), `shader`, `alpha_mode`, `attributes`,
`preferred`. **UNVERIFIED on device**: check the event and the look (azure glass with a rim, not orange).

## Voice commands (R7)

Toolbar mic (`VoiceController`, `MicRecorder`, `VoiceCommandClient` in `ui/`): hold ≥ 450 ms = push-to-talk (release
sends); a short tap records hands-free until 1.2 s of silence, 7 s, or a second tap. 16 kHz mono PCM16
(`AudioRecord`, `VOICE_RECOGNITION`, `RECORD_AUDIO` asked at start) → WAV → `POST /api/voice/{job}` with
`{audio, mime: audio/wav, lang, step, action, speak: true, rate}` and `X-Demo-Key`. The reply's `target` is applied
with `showStep(…, StepBy.VOICE)` (AGQ `step` with `by: "voice"`), `answer` is spoken from the reply's `tts` WAV (also
cached as that line), else through `/api/tts`; the transcript shows in the status line for 3 s ("ΑΚΟΥΣΑ: «…»").
Speech is stopped when recording starts (barge-in). Logs: `mic` (on/off, ms), `voice_cmd` (transcript, intent,
target, ms round trip, server_ms, model, simulated, audio_ms), `voice_apply` (moved). Tests:
`ui/VoiceCommandsTest` (MockWebServer: path, key, body fields, WAV header, reply mapping, null target, ask, 422).

## Camera preview (hud)

With `hud=true` the side column shows the latest camera frame (≈ 12 × 9 cm, world-locked beside the step window,
8 fps, decoded at 320 px off the main thread from `VisionListener.onFrame`'s lazy JPEG) with the caption
"camera live · N fps". `MainActivity.showCameraBox(PreviewBox(x0, y0, x1, y1, label))` (normalized 0..1, main
thread) draws a box on it, for the parts-in-pile / perception results.

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

**Camera pose (round 2).** The head buffer is filled by `SpatialInput.execute()` (the SDK's per-frame system tick,
registered in `onCreate`, the same loop thread as MainActivity's FrameTick); identity poses at the origin (before
tracking starts) are skipped, and the first good sample logs `head_pose` (render frame, thread). The camera thread
resolves each frame with `CameraPoseResolver` (pure, tested): head at `SENSOR_TIMESTAMP` (+ `head_time_offset_ms`)
× lens pose. If the buffer is fresh but the capture time falls outside it (a clock mismatch), it falls back to the
head at `now − unknown_ts_latency_ms` so the marker can still lock. `CameraPoseLog` logs `camera_pose` (`cam_p`,
`cam_q`, `head_p`, `head_samples`, `head_newest_age_ms`, `capture_age_ms`, frames with/without pose) on the first
pose and every 10 s, and `error` `where: camera_pose_missing` with `reason` (`no_head_samples`, `head_stale`,
`capture_outside_buffer`, `no_lens_pose`) after 25 frames without a pose, then every 10 s; once with
`ts_fallback: true` when the fallback is used. `track` carries `cam_pose` (did the last frame have one).

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

## World: table placement and parts in the pile (round 2)

`world/WorldModule` is the entry point (KDoc has the MainActivity lines). Needs `MRUKFeature(this, systemManager)` in
`registerFeatures()`; without it the table step logs `table_missing reason: no_mruk` and everything falls back.

**Table without a marker (R3/R8).** `SceneTable.start()` (after the `USE_SCENE` prompt; it re-checks the permission
for 30 s) calls MRUK `loadSceneFromDevice(removeMissingRooms = true, requestSceneCaptureIfNoDataFound = false)`,
never starting a new room scan. Every anchor's plane rectangle and volume top is converted to WORLD
(`Frames.spatialToWorld` + `mirrorZ` on local points) and `TableFinder` (pure, tested) picks: the `TABLE` in front
of the user (nearest), else the largest level surface 0.6–1.0 m above the floor in front, else a `TABLE` within 3 m.
`world.tablePose(head, assembly)` puts the footprint (`overall_mm` L × W) on it: 0.45 m ahead of the head, clamped
so the footprint stays on the table, yaw so the assembly front (+Z) faces the user; same TABLE frame as
`Placement.fixedTableSpot`, so it replaces it in `ghost.setTablePose`. Logs `scene` (load result, rooms, anchors),
`table_found` (`size_m`, `height_m`, `y_m`, `center_m`, `dist_m`, `reason`, `source` plane/volume, `plane_up_axis`,
labels) or `table_missing` (`reason`: `permission_denied`, `error_no_rooms_found` = no scene model, `no_level_surface`,
`none_in_front`, `no_head_pose`, …, `fallback: fixed_spot`).

**Parts in the pile in 3D (R11).** While a `place`/`fasten` action is shown (`world.setAction`), `PartsFinder` grabs
a camera frame every 5 s (`VisionModule.grabFrame`: JPEG at 1280 px long edge q80 + intrinsics + camera pose at
capture), posts it to `POST /api/perception/{job}/parts` with `want` = the action's part id and/or hardware code,
and turns each `found[].box_2d` (`[ymin, xmin, ymax, xmax]` 0–1000) into a pixel (`PickRays.boxCenterPx`), a WORLD
ray (intrinsics + camera pose, optional undistortion with `use_distortion`) and a point: Meta's **environment
raycaster** (depth, MRUK `raycastEnvironment`, hits 0.15–4 m) first, then the MRUK table rectangle, the floor, the
work-surface plane (`world.workSurfaceY` = the fixed spot, else the tracked assembly's height). `world.pickTargets` =
`PickTarget(id, label, worldPos, confidence, ageMs, kind, method)`; an item the server reports `missing` is dropped,
a new action clears them. Logs `parts` (`ok`, `ms`, `server_ms`, `want`, `found[{id, confidence, box_2d, method,
pos_m}]`, `missing`, `targets`, `cam_pose`, `simulated`) and `depth_raycaster` (start result).
`PickMarkers` draws the §4.6 cue for each target (azure ring 2 cm larger than the footprint estimated from the box
size and distance, chevron 6 cm above, bobbing ±1 cm at 0.5 Hz, fading out over the last 3 s of a 15 s age) and logs
`pick_markers`. Not yet: the "Πάρε / Pick up" label pill on the pile part, and moving the cue to the insertion path
when the part is lifted.

Wired in MainActivity (0733538): `MRUKFeature`, `WorldModule`, `PickMarkers`, `world.start/stop`, `setAction`,
`tablePose` in the REGISTER fallback, the found box on the camera preview.

## Registration without a marker (the demo path)

The printed strip is not used in the demo, so the assembly is placed, not tracked. `WorldModule` owns one anchor
(`world/AssemblyAnchor.kt`, WORLD ← TABLE) and hands it to the tracker with `VisionModule.placeAssembly(pose,
source)`: `vision.worldFromAssembly()` is then non-null (state `held`), so MainActivity's REGISTER → GUIDE flow and
`ghost.setAssemblyPose` work as with a marker, and `track.source` says what placed it. The marker code is kept and
still wins when a strip is seen.

| Source (`track.source`, `place.source`) | When |
|---|---|
| `table` | at once when the guide is loaded and the scene has a table: the base part centred in front of the user on the MRUK table, front facing the user (`TableFinder.place`) |
| `fixed_spot` | no scene table 2.5 s after the guide loaded: 0.55 m ahead, 0.45 m below the head; replaced by `table` if one turns up |
| `server_box` | the server's box for the **base part** (the first part the guide places: the drawer front on malm-drawer) in a camera frame, ray-cast onto the anchor's plane (`BasePlacement.fromBox`) |
| `manual` | `world.nudge(rightM, forwardM, yawDeg)`; the camera does not move it again until `world.realign()` |
| `markerless` | `world.onObjectPose(worldFromAssembly, confidence, source = "markerless")`, the plug for `vision/PanelPose.kt` |

- **No jitter.** A server box moves the anchor once (`reason: first`). After that only `world.realign()`
  (`realign`), or answers more than 10 cm from the anchor that agree with each other within 6 cm for at least 4 s
  (`disagree`). The automatic check runs every 10 s and only while the base part's own step is on. Plugged-in
  poses need confidence ≥ 0.5 and a change of more than 1.5 cm or 2°. A simulated server answer is never used.
- **Yaw from the box** only when the box is clearly elongated: measured on the table in metres, its aspect is at
  least 0.75 × the part's own (a 380 × 130 mm front: within about 8° of the picture axes) and its length 0.6–1.5 ×
  the part's; of the two headings 180° apart, the one nearer the current yaw. Otherwise the yaw is kept.
- **Outline + coach line.** `PlaceOutline` draws the base part's rectangle (grown 1 cm, 2.5 mm azure tubes) on the
  table while the anchor is a placed one and the base part's step is on; `world.coachLine(lang)` gives "Place the
  drawer front inside the blue outline." / «Βάλε το κομμάτι «Πρόσοψη συρταριού» μέσα στο μπλε περίγραμμα.»
  (`WorldStrings`), null when there is nothing to say. Putting the real part into the outline is a valid way to register.
- **Logs.** `place_base` (base part, footprint), `place` (`source`, `reason`, `pos_m`, `yaw_deg`, `moved_m`,
  `prev`), `place_candidate` (every server answer for the base part: `center_m`, `length_m`, `yaw_from_box`,
  `dist_m`, `on_table`, `applied`), `track` with `source` = the placing source and `state: held`.
- **Not done:** pinch-drag of the ghost on the table (only the `nudge` API), the outline needs the mesh hook passed
  to `WorldModule` (see its KDoc for the three MainActivity lines: constructor hook, `world.setAssembly(a)`,
  `world.tick(dt)`).
- **UNVERIFIED on device:** all of it. In particular the anchor height without a scene table (the fixed spot is a
  guess, so a server box lands with parallax error), and the box quality on real photos.

## Markerless front panel (`vision/PanelPose.kt`)

A Kotlin port of `server/perception/panel.py` (method, gates, numbers and failure cases:
`tools/markerless/FRONT_PANEL.md`): x, y and yaw of the 380 × 130 × 16 mm drawer front lying flat on the table, from
one colour frame, the camera pose and the table height. **Measured on renders only; not yet seen on the headset.**

- `PanelMath` (pure): the rectification homography, the smear correction, the far-corner anchor, the gates.
  `PanelPose.detect` (OpenCV Java API): warp, Lab whiteness, 8 thresholds, contours, `minAreaRect`. Every constant
  of the reference is a named field of `PanelParams`.
- `PanelFrames`: the detector's TABLE frame (+Z up, origin on the surface; `tableAtHeight(y)` = WORLD +X, −Z, +Y)
  and ASSEMBLY ← PANEL = 180° about X, t = (0, 0.05, 0.016) m, from the same mesh numbers as `MarkerPlacement` (the
  front lies inside face up; PANEL is 15 mm from the strip centre along the marker's +Y and 16 mm below it). All
  right-handed WORLD; nothing here touches the Spatial SDK's left-handed frame.
- `PanelPoseTracker`: on the camera thread it checks the rate (3 Hz), the job (`malm-drawer` only), the camera pose,
  the table height and that the camera looks down, then encodes one 1280 px JPEG (`CameraFrame.jpegAt`, the only
  colour path) for its own thread. Three trusted results in a row that agree within 10 mm and 3° → their median →
  `onObjectPose(worldFromAssembly, confidence, "markerless")`. `prevYaw` = the yaw of the pose the ghost shows now.
- AGQ `panel`, at most once a second: `trusted`, `reason`, `ms`, `pos_m` (WORLD, panel centre on the table),
  `xy_m`, `yaw_deg`, `size_err_mm`, `rectness`, `contrast`, `streak`, `reports`, `table_y_m`, `img`.
- Launch extra `--ez markerless false` (default true; `Params.markerless`).

Tests (`PanelPoseTest`): the maths against numbers exported from the Python reference
(`tools/markerless/export_kotlin_ref.py` → `src/test/resources/panel_pose_ref.json`), and the whole detector on the
eight sample frames with the desktop OpenCV bindings: same trusted / reason as Python, x, y within 1 mm, yaw 0.2°.

Optional, on the whole render set (git-ignored): export the reference with
`python tools/markerless/export_kotlin_ref.py <data/synth> <ref.json>`, then
`AGQ_PANEL_DATASET=<data/synth> AGQ_PANEL_REF=<ref.json> ./gradlew testDebugUnitTest --tests '*PanelPoseTest'`.
Measured (360 un-degraded frames, the evaluation's noisy camera pose and table prior): the same 73 frames trusted
as Python, the same reason on 358 of 360, at most 2.0 mm between the two, Kotlin against the ground truth p50 4.4 /
p90 10.7 / max 16.5 mm, 9 ms p50 on the Mac. One of the 73 is a later assembly state (`s08_f012`, step 4,
rectness 0.862), trusted by the reference too on un-degraded frames: switch the tracker off after step 1
(`panel?.enabled = …` below).

**Not wired in MainActivity yet.** The lines (compiled against this tree, then taken out again):

```kotlin
import com.agj.android.vision.PanelPoseTracker
private var panel: PanelPoseTracker? = null
// onCreate, after `world = WorldModule(…)`:
if (params.markerless) {
  panel =
      PanelPoseTracker(
          tableHeight = { world.table?.y },
          ghostPose = { vision.worldFromAssembly() },
          onObjectPose = { pose, confidence, source -> world.onObjectPose(pose, confidence, source) },
      )
}
// openJob(), after world.setAssembly(a):
panel?.setJob(a.jobId)
// visionListener.onFrame, first line:
panel?.onFrame(frame)
// showStep(), after world.setAction(…): only while the front is alone on the table
panel?.enabled = step == assembly?.stepNumbers?.firstOrNull()
// onDestroy, after world.stop():
panel?.stop()
```

**Continuous tracking of the parts (Sun 15 Nov).** A server answer takes 3–5 s, so between answers the parts are
followed in the camera picture. `/parts` is asked with `mode: "step"` for everything the step picks up (the action's
own item first); every box of every item (`items[].boxes`, e.g. each of 4 screws) becomes a **world-anchored track**
(`world/PartTracks.kt`, pure) and a `PickTarget` (`key` stable per real thing, `instance`, `name`, `countText`
"φαίνονται 3 από 4", `tracked`, `moving`, `current`, `pill(lang)`). `world/PartTracking.kt` takes camera frames
from `VisionModule.frameTap` (camera thread: only a grey copy downscaled to 640 px; busy = the frame is skipped),
and on its own thread `agj-flow` (≤ 30 Hz, ≤ ⅓ of a core) runs `vision/PartFlowTracker.kt`: Shi–Tomasi corners in
each box (region grown 15 %, at least 24 px a side for small hardware), pyramidal Lucas–Kanade forward and backward,
median flow with outlier rejection, translation only; a track is lost on too few points, off the image, or points
collapsing / scattering, and is looked for again 1 s later where its world point projects.
**Head motion vs part motion:** each frame the track's WORLD point is projected with that frame's camera pose and
compared with where the 2D tracker sees the part. Agreement: the point is not touched (a world-locked marker cannot
swim). More than 10 px apart for 3 frames while the head is fairly steady (< 25°/s, < 0.4 m/s; faster, a few ms of
pose timing error looks like motion, so the decision waits): the part moved, and its point is re-solved every frame
(ray onto the horizontal plane at its height, then the depth raycast → table → work height when it rests and every
0.5 s while it moves), smoothed with an 80 ms time constant. A new answer is matched to the nearest track of the same
item (where the track was when the picture was taken), so markers keep their part; a part that moved after the picture
keeps the tracker's newer place. The **base part's** track moves the ghost: its steps go to the assembly anchor as
source `flow` (position only; not while the marker tracks, after a manual nudge, within 3 s of a `markerless` pose,
or when the seeding box failed `onBase`'s size gate). AGQ `part_track` (≤ 1 Hz): `tracks`, `alive`, `moving`,
`seeded`, `lost`, `lost_why`, `pts` (mean points per live track), `ms`, `ms_max`, `frames`, `hz`, `moved` (world
points that started moving because the part moved), `head_gated` (frames held for head motion), `base_moves`, `ids`;
`cam_pose: false` every 5 s while frames have no camera pose (then nothing is tracked). Tests:
`world/PartTracksTest` (synthetic camera motion, identity, loss, pixel mapping, step-mode answer),
`vision/PartFlowTrackerTest` (LK on synthetic white-on-cream pictures, desktop OpenCV). **UNVERIFIED on device.**

## Logs (shared with the Unity app)

Tag `AGQ`, `{"t": epoch ms, "app": "android", "ev": ..., "t_rel": ms since start, ...}` (`t` = wall time at start +
monotonic clock). Events: `start` (version, params; key only as `key_set`), `job_loaded` (`ms`, `parse_ms`,
`parts`, `glb_bytes`, `max_err_mm`), `identify`, `locate`, `coach`, `panels`, `ghost_load`, `ghost_show`, `step`,
`guidance`, `tracking`, `voice`, `voice_fetch`, `finish`, `frame` (every 5 s: render_fps), `error`; from vision:
`vision` (config), `camera` (all Camera2 values), `camera_frame` (first frame: capture age, head-pose availability),
`hands_source`, `head_pose`, `camera_pose`, `error` `camera_pose_missing`, `track`, `touch_start`, `touch`; round 2:
`ghost_material`, `mic`, `voice_cmd`, `voice_apply`, `drawing` (guide crop loaded: step, path, bytes), `window`
(fade-relocations); from world: `scene`, `table_found`, `table_missing`, `depth_raycaster`, `parts`, `pick_markers`, `place_base`,
`place`, `place_candidate`;
from the socket: `perception` (hello, events with latency,
closed/failure), `perception_stats`.

`track` (10 Hz live, every frame in replay): `fps` (render), `tracked`, `pos_m` (strip centre, WORLD), `yaw_deg`,
`reproj_px`, `source` (`marker`, `touch`, `anchor` = held after the marker was lost, `none`), `frame` + `q`
(replay), `state` (`tracked`/`held`/`lost`), `method`, `pos`, `rot`, `ms`, `raw_pos_m`, `asm_pos_m`, `age_ms`,
`cam_fps`, `solve_hz`, `cam_pose`. When the marker is hidden (MALM steps 5+) the last pose is held (`source: anchor`).

## Module map

Each module sits behind an interface; `MainActivity.kt` only wires them.

| Package | File | What |
|---|---|---|
| `core/` | `Params.kt` | launch parameters (extras over `config.json`) |
| | `AgqLog.kt`, `Agq.kt` | AGQ JSON logger (pure) + logcat sink |
| | `Geom.kt` | `Vec3`, `Quat`, `Pose3`, `blenderToGltf` |
| `guide/` | `Assembly.kt` | manifest model (lenient), `Assembly` |
| | `Edges.kt` | hard edges (dihedral > 30°), dashed outline, tube meshes (pure, tested) |
| | `Glb.kt` | GLB reader: nodes, meshes, animation sampling (pure, tested) |
| | `Guide.kt` | steps → actions, geometry, labels, step poses, `maxTargetErrorMm` (pure, tested) |
| | `JobRepository.kt` | `HttpJobRepository` (download + parse + build, `job_loaded`) |
| | `GhostRenderer.kt` | `GhostRenderer` interface + stub |
| | `SpatialGhostRenderer.kt` | the Spatial SDK implementation; **the only RH → LH conversion** (`toSdk`, `toPose3`, `facingPose`) |
| | `Shapes.kt` | procedural meshes, insertion path, `SpatialMesh.fromGltf` |
| | `GhostMaterial.kt` + `app/src/shaders/ghost_fresnel.*` | `GhostMat`: fresnel custom material or unlit fallback, token tints |
| `vision/` | `VisionModule.kt` | **the one vision entry point for MainActivity** |
| | `CameraSource.kt`, `LiveCameraSource.kt`, `ReplayCameraSource.kt` | Camera2 capture (YUV → gray + lazy JPEG, intrinsics, lens pose, head pose) / replay folder |
| | `SpatialInput.kt`, `HeadPoseBuffer.kt` | Spatial SDK system: head pose ring buffer, hand joints, render fps |
| | `CameraPose.kt` | `CameraPoseResolver` (head × lens at capture, clock fallback, miss reason) + `CameraPoseLog` (pure, tested) |
| | `Frames.kt` | frame conversions, `MarkerConfig` (strip tag offset), `MarkerPlacement` |
| | `MarkerDetector.kt`, `MarkerTracker.kt` | OpenCV AprilTag 36h11 + `solvePnPGeneric(IPPE_SQUARE)`, ambiguity, strip centre |
| | `TrackingCore.kt`, `PoseFilter.kt` | One-Euro filter + hold + touch fallback + the `track` event |
| | `Hands.kt`, `TouchTracker.kt` | hand summary (port of handChecks.ts), two-corner snap (port of register.ts) |
| | `Yuv.kt`, `VisionConfig.kt` | NV21 resize, stream intrinsics; `vision.json` |
| `net/` | `ServerApi.kt` | `HttpServerApi` (OkHttp, all REST calls, incl. `parts`) |
| | `PerceptionSocket.kt` | `OkHttpPerceptionSocket` (flow control, reconnect, AGQ) |
| `world/` | `WorldModule.kt` | **the one world entry point for MainActivity**: table pose, pick targets |
| | `Table.kt` | `TableFinder`: scene corners → level rectangle, choice, footprint placement (pure, tested) |
| | `SceneTable.kt` | MRUK scene load → WORLD surfaces → table; `MrukDepthRaycaster` (environment raycast) |
| | `PickRays.kt` | box → pixel, pixel → WORLD ray, undistortion, ray-plane (pure, tested) |
| | `AssemblyAnchor.kt` | the no-marker anchor: source policy (`table` < `server_box` < `markerless`, `manual`), `BasePlacement` (box → pose), `Footprint` (pure, tested) |
| | `PlaceOutline.kt` | the blue outline of the base part on the table (`OutlineLayout` pure, tested) |
| | `PickMarkers.kt` | the §4.6 pick-up cue: ring under the real part + chevron bobbing ±1 cm at 0.5 Hz, fades with age (`PickCue` pure, tested) |
| | `PartsFinder.kt` | the `/parts` loop every 5 s, ray → depth / table / floor / plane, `PickTarget`, `parts` log (tested with fakes) |
| `ui/` | `CoachFlow.kt` | look → identify → build → locate → register → guide (pure, tested) |
| | `Panels.kt` | glass `StepPanel` + toolbar ornament, `LabelPill`, `Hud` (Compose, world-space panels) |
| | `Strings.kt` | EL/EN copy |
| | `WindowFollow.kt` | step window beside the object (spring + dead band); `Placement` (fixed spot, panels ahead) |
| | `Voice.kt`, `Sounds.kt`, `Jpeg.kt` | server TTS + cache (+ play a reply's WAV), synthesised cues, JPEG downscale |
| | `VoiceCommands.kt`, `VoiceController.kt` | WAV, `/api/voice` client, AudioRecord capture; push-to-talk flow |
| | `SidePanel.kt`, `Images.kt` | parts card + camera preview panel; guide crop → white line art, preview feed |
| | `WindowFollow.kt` | + `WindowRelocator` (fade-relocate, hold during turns) |

## First device run: what to check

docs/QUEST_TESTING.md and eval/judge/round-1.md §5 have the full script. App-specific checks, with the log line
that answers each:

1. `camera` event: a camera id with `position` 0 (left), intrinsics non-zero, `LENS_POSE_*` present; else
   `vision.json` `camera_position`.
2. `head_pose` once (the buffer fills), then `camera_pose` with `cam_p`/`cam_q` within a second and every 10 s.
   The first `camera_frame` may say `head_samples: 0` (the camera starts before the first render tick): that is fine
   if `camera_pose` follows. Otherwise `error` `camera_pose_missing` names the `reason`: `no_head_samples`
   (SpatialInput not ticking / `getViewerPose` failing: look for `error head_pose`), `head_stale`,
   `capture_outside_buffer` (clock mismatch: `lookup_minus_newest_ms`; the app falls back to `now − latency`, tune
   `unknown_ts_latency_ms`), `no_lens_pose`.
3. `hands_source`: `skeleton` (fingertips, touch registration works) or `avatar_body` (wrists only).
4. Frames: the step window and the fixed spot appear **in front** (not behind); the drawer ghost is not mirrored
   (left side panel on the left); labels and the window show their front. If panels show their back, negate the
   vector in `facingPose` (one place).
5. Marker: with the strip on the drawer front, `track` `state: tracked` at ~10 Hz and the ghost on the drawer. If
   it is offset by a constant, try `lens_convention` and `head_time_offset_ms` in `vision.json`.
6. Ghost: `ghost_material` `kind: custom_fresnel` and the ghost is azure glass with a brighter rim and edges. If it
   is still wrong, relaunch with `--es ghost_material unlit` and compare.
7. Voice: hold the mic, say "επόμενο" / "next": `voice_cmd` with `intent: next`, then `step` `by: voice`.
8. Socket: `perception` `kind: hello`, then `perception_stats` with frames sent and acks; hands every 100 ms.
9. Scene: `scene result: success` and `table_found` with a plausible `height_m` (~0.75) and `size_m`; the ghost
   then stands on the real table, facing you, when no marker is seen. `plane_up_axis` should be `z` (OpenXR planes).
   `table_missing reason: error_no_rooms_found` = run Space Setup on the headset once.
10. Parts: during a `place` step, `parts` every ~5 s with `found[].method` (`depth` if the environment raycaster
   works, else `table`) and `pos_m` on the real part; `depth_raycaster result` says whether depth is available.

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
