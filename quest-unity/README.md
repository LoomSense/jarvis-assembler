# quest-unity: native Meta Quest 3 app (Unity)

The same guidance as the WebXR client (`client/src/xr/`), plus what WebXR cannot do: the passthrough camera **with its pose and intrinsics**, so the real assembly can be tracked in 6-DoF and the ghost stays on it. The server is unchanged; this is a new client. Research notes with sources: [RESEARCH.md](RESEARCH.md).

## Editor and packages

| | |
|---|---|
| Editor | **Unity 6.3 LTS, 6000.3.28f1, arm64**, with Android Build Support, SDK/NDK r27c, OpenJDK 17 |
| Path | `/Applications/Unity/Hub/Editor/6000.3.28f1/Unity.app/Contents/MacOS/Unity` |
| Installed with | the Unity CLI (`~/.unity/bin/unity install 6000.3.28f1 -a arm64 -m android android-sdk-ndk-tools android-open-jdk-17.0.18+8 --cm --accept-eula`). The Hub's own `--headless` commands now redirect to this CLI. |
| Licence | Unity Personal, activated in Unity Hub (needed for batchmode) |

`Packages/manifest.json` (versions read from the registries on 15 Nov 2026):

| Package | Version | Why |
|---|---|---|
| `com.meta.xr.sdk.core` | 209.0.0 | OVRCameraRig/OVRManager, passthrough, hands, anchors. Meta registry `https://npm.developer.oculus.com` (scope `com.meta`). Fall back to 207.0.1 for all `com.meta.*` together if 209 misbehaves. |
| `com.meta.xr.mrutilitykit` | 209.0.0 | `Meta.XR.PassthroughCameraAccess` (camera image + intrinsics + pose), scene/table anchors |
| `com.meta.xr.sdk.interaction.ovr` | 209.0.0 | Interaction SDK on OVR: hand poke/pinch for panels |
| `com.unity.xr.openxr` | 1.19.0 | Meta recommends OpenXR + the Meta XR feature group (the Oculus XR plugin is deprecated) |
| `com.unity.render-pipelines.universal` | 17.3.0 | URP, needed for the glass/ghost materials of the design spec |
| `com.unity.cloud.gltfast` + `com.unity.meshopt.decompress` | 6.20.0 + 0.3.0-exp.1 | runtime GLB. glTFast 6.20 refuses meshopt 0.1.0-preview.7 (`#error`), so 0.3.0-exp.1 is required |
| `com.unity.nuget.newtonsoft-json` | 3.2.2 | DTOs with exact server field names |
| `com.endel.nativewebsocket` | git `#upm` (1.x) | perception socket. 1.x needs `DispatchMessageQueue()` per frame (done in `PerceptionSocket.Tick`) |
| `jp.keijiro.apriltag` | 1.0.3 | AprilTag tagStandard41h12 detection + pose (npmjs scope `jp.keijiro`), Android arm64 |
| `com.unity.ugui` (incl. TextMeshPro) | 2.0.0 | world-space panels and text |

Later: `com.unity.ai.inference` (Sentis) for on-device detection (Meta's sample uses 2.2.1 with YOLO).

## UI choices (open for docs/design/XR_NATIVE_SPEC.md)

- **URP** (`Assets/AG/Settings/URP-Quest.asset`, MSAA 4x, no HDR) so custom glass and fresnel ghost shaders can be dropped in.
- **TextMeshPro** with **Inter** (SIL OFL, `Assets/AG/UI/Fonts/`) as a dynamic SDF asset with Latin + Greek pre-baked (`Inter SDF.asset`, made by SetupProject). Inter has full Greek. Google Sans (the web client's font) can replace it if its licence allows; only `UiTheme.FontAsset` changes.
- **`UiTheme` asset** (`Assets/AG/UI/UiTheme.asset`): slots for `GlassMaterial`, `GhostMaterial`, `ArrowMaterial`, the font, colours, and **angular text sizes** (`TitleDeg`, `BodyDeg`, `CaptionDeg`, `UiTheme.DegToMetres`).
- **World-space panels only**: the step panel sits beside the object, follows it with damping and turns only about world up (`IStepPanel.PlaceBeside`). Only the debug HUD follows the head.
- **Tweens**: `AG.Core.Tween` / `Ease` (OutCubic, InOutCubic, OutBack, Spring, `Tween.Damp`), no third-party dependency.

## Guide and UI (round 1, per docs/design/XR_NATIVE_SPEC.md)

**Rendering choice (spec §3.1):** eye-buffer only. Glass, ghost and marks are hand-written URP HLSL shaders in `Assets/AG/Guide/Resources/AG/` (loaded with `Resources.Load`, so always in the build): `AG/Glass` (superellipse SDF window, gradient tint 0.90/0.80, sheen, rim, inner shadow, halo, dither), `AG/Ghost` (depth-primed translucent fill + fresnel rim, dashed mode), `AG/Flat` (dots, rings, octagon/diamond badges, sweeps, line-art crops). All single-pass-instanced. Text is world-space TextMeshPro (no uGUI canvas, no OVROverlay yet). `UiTheme`'s material slots are unused; the shaders own the look.

| Piece | File | What it does |
|---|---|---|
| GLB + end pose | `Guide/GlbAnimation.cs`, `Guide/GuideLoader.cs` | Reads the LAST keyframe of `walkthrough` straight from the glTF samplers (no Animation component); glTFast (custom material generator, no glTF shaders) instantiates the meshes; `assembly` becomes `AssemblyGuide.Root` with identity local pose; every part node gets its final transform; `FinalBounds` per node and for the whole assembly. Logs `job_loaded`. |
| Repositories | `Guide/HttpJobRepository.cs` | `/files/{job}/manifest.json` + `walkthrough.glb`. Job ids `local:<name>` load from Resources (`local:malm-drawer`, `local:knagglig` are bundled via the test folder): offline demo without a server. |
| Ghost | `Guide/GhostRenderer.cs` (MonoBehaviour) | One action at a time: ghost (azure 0.14 breathing 0.42 Hz, rim 0.65, hard edges, 350 ms appear 2 cm along from_dir), dotted path (Bezier for boards, straight for hardware) with a travelling dash (2.2 s cycle) and arrowhead, 290 deg turning arrow (clockwise from the tool side, 0.8 Hz sweep) + "Half a turn until it locks" / "All the way in" chip for screws/bolts/cams, hole ring + ripples (0.5 Hz) + dim rings for the step's other holes, label pill "Name · code [2 of 4]" with leader line and dot (billboard about up, dynamic scale 0.75-1.5x), correct (green, rim flare, check badge), wrong (octagon x badge pulsing 0.83 Hz x 3, "X goes here" label on the observed part's place), unsure (amber rim + diamond ?), turn preview (edge outline rotating, max 3 times, curved arrow), tracking lost (30 %, dashed, marks hidden), completion (green rim glow + three rings). Placed parts: depth-only when tracked (the ghost hides behind real parts), faint glass when untracked. Step pose (`assemblyRotationDeg`) applied only while untracked. Reduced motion freezes every loop (key M). |
| Window | `UI/StepPanel.cs` | 0.52 x 0.34 m glass window (compact 0.36 x 0.20 for coach cards), status line (uppercase, no tonos, shape + colour + word), instruction (auto-size 1.25-2.0 deg, max 2 lines), body, chips, line-art crop well, 1.7 mm progress bar, toolbar ornament (Back · Repeat · Where am I · **Next**; Next becomes Finish on the last action), grab bar, status pill. Placement §2.6: user's right of the object (left-handed flag), inner edge 0.10 m off the bounds, 10-20 deg below eye level, 0.6-0.95 m, yaw + 8 deg back tilt, critically damped follow (0.6 s) with 3 cm dead band, fade-relocate when the user walks > 35 deg around. Coach cards: once 0.8 m ahead, 15 deg down, world-locked. |
| Input | `App/PinchRayInput.cs` | Hand pointer pose + index pinch (`OVRPlugin.GetHandState`), Touch controllers (trigger), mouse in the editor; ray + cursor drawn on hit. Hover 1.03 / press 0.97 springs, tick at pinch down, action at release. Keys: N next, B back, R repeat, W where am I, M reduced motion; editor only: T fake tracking, 1/2/3 correct/wrong/unsure. |
| Coach | `UI/CoachFlow.cs` | §5.4 cards (Look → Identifying → Found → Building with the real job stage → Loading → Finding your step → Hold still / Locked on → guide), errors as one sentence + Retry, perception events → ghost feedback + window status + cues + voice, step_complete → correct then advance after 650 ms, Where am I (= /locate), completion summary. Logs `coach`, `step` (`by`: auto/button/where_am_i), `button`, `error`. |
| Voice, sounds | `UI/VoicePlayer.cs`, `UI/UiSounds.cs` | /api/tts with cache and `Prefetch` of the next action's line; synthesised cue family (tick, place, success, step, warning, unsure, complete, lost) at -14 dBFS. |

Contract changes (all in my modules, listed for the other agents): `IGhostRenderer` + `ShowFeedback`, `SetTracking(GhostTracking)`, `ShowComplete`, `TryGetWorldBounds`, `ReducedMotion`; `IStepPanel` + `SetButtons`, `SetStatusPill`, `StepView` + `StepIndex`, `Page`, `Chips`, `BadgeKind`, `IsLast`; `PanelButton` + `WhereAmI`, `Retry`, `Cancel`; `IVoice.Prefetch`; `ICoach` + `WhereAmI`, `Retry`; `CoachContext` + `Sfx`, `RegisterTimeoutS`; `AssemblyGuide.FinalBounds`, `WalkthroughEndS`; `GuideAction.CountIndex/CountTotal`; `ManifestDto.WalkStepDto.N` is a `JToken` (the real manifests end with `"n": "finish"`, which crashed the old int DTO).

### Guide and UI round 2

| Piece | File | What it does |
|---|---|---|
| Voice commands (R7) | `UI/VoiceCommands.cs`, `App/AppRoot.cs` | Push-to-talk mic in the toolbar (Back · Repeat · **mic** · Next; "Where am I" moved to voice + key W). Hold = record until release; short tap = record until 1.2 s of silence after speech (or 5 s, or a second tap). `MicRecorder` (Microphone, 16 kHz mono, resampled if the device needs another rate, 8 s max, RECORD_AUDIO asked at first use) → `WavEncoder` (PCM16 WAV) → `POST /api/voice/{job}` with `speak:true` through `AG.Core.Http` (`VoiceCommandClient`, DTOs in UI; Net untouched) → `VoiceReplyMap` (target {step, action} → step index; `ask` highlight / go_to_step actions; `none` says nothing) → `CoachFlow.ApplyVoice` moves the guide (AGQ `step` `by:"voice"`), shows «transcript» + answer as the window caption, plays the reply's `tts` WAV (else `/api/tts`). Logs `voice` {ms, intent, transcript, model, server_ms}. Key V = hold to talk in the editor. Mic states: idle (white), listening (red glyph + level ring driven by the voice level), thinking (amber). |
| Parts ornament (§2.5) | `UI/StepPanel.Ornaments.cs` | 0.14 × 0.18 m glass card on the window's outer edge: "ΠΑΡΕ", a turntable copy of the action's part mesh (ghost material + hard edges, fitted to 8.5 cm, 20°/s, frozen by reduced motion), name, code · count. Hidden on coach cards and actions without a part. |
| Camera preview | `UI/StepPanel.Ornaments.cs`, `App/AppRoot.cs` | 12 × 9 cm image on the outer edge above the parts card (beside the compact card on coach cards, so the Look card shows it too), the source's texture (or a copy of its CPU pixels) at 10 Hz, `AppRoot.PreviewBox` (last server box, image UV) drawn on it, caption "κάμερα ζωντανά · 30 fps". World-locked with the window. Toggle: key P or a long press (0.8 s) on Repeat; logs `camera_preview`. Shader `AG/Flat` gained `_Image` (texture colour). |
| Pick targets (§4.6) | `IGhostRenderer.ShowPickTargets(IReadOnlyList<PickTarget>)` | World-space: azure ellipse on the table 2 cm larger than the footprint (10 % fill + 2 mm stroke), a 14 mm chevron 6 cm above bobbing ±1 cm at 0.5 Hz, label pill "Πάρε · name · code" with leader (first two targets). `AppRoot.ShowPickTargets` forwards. Null/empty clears. |
| Wrong (§4.8) | `Guide/GhostRenderer.cs` | Red dashed outline (8 mm dash, 5 mm gap, 2 mm, camera-facing quads along the hard edges) where the wrong part sits (the current action's place), alpha 0.6↔1.0 at 0.83 Hz for 3 cycles, plus the octagon ✕ and "X goes here" on the observed part's place. |
| Window on turns (§2.6 rules 5, 6) | `UI/StepPanel.cs` | When the object's world bounds change shape (it turns) the window holds still until the object is still for 1 s; any re-placement > 0.25 m away fades out 150 ms, moves, fades in 250 ms (logs `window_relocate`); never slides across the work zone. Untracked, `MaxSideDeg = 24` keeps the window next to the preview assembly, which `AppRoot` now puts 0.55 m ahead and 0.22 m below the eyes when the guide starts (head pose known by then). |
| Cancel | `UI/CoachFlow.cs` | Each attempt runs on a linked token: Cancel on the identify / build / locate cards aborts the request in flight (`UnityWebRequest.Abort`) and starts over (`coach` Cancel/Cancelled). |
| Guide drawing | `UI/CoachFlow.cs` | Server jobs fetch the manifest `crop`, else `crops/step-NN.png`, from `/files/{job}/`; shown as white line art in the window's right column. |
| Ellipsis | `Guide/GuideVisuals.cs` `Txt.EnsureGlyphs` | Adds "…" and the UI symbols to the dynamic Inter SDF atlas once per font (the saved asset has an empty character table), so truncation draws "…". |
| Vision round 1 wiring | `App/AppRoot.cs` | `CpuPixels`, `MarkerSetup.LoadForApp` (strip at (0, 0.064, 0), Euler(0,0,180)), `HandJointSource` + `HandsSummarizer` (10 Hz `hands` to the socket, target = the ghost part's world box), `TwoCornerTracker`, async JPEG for the socket, tracker disposed. Socket drops show "Χωρίς σύνδεση" in the status pill. OVR project config: hands + controllers, passthrough camera access, scene, anchors (also set by SetupProject). NativeWebSocket removed from the manifest. |

What Vision must provide for pick targets: from `POST /api/perception/{job}/parts` boxes (`box_2d` [ymin, xmin, ymax, xmax] 0–1000 of the frame sent), unproject the box centre with that frame's intrinsics and camera pose, intersect with the table plane (MRUK table anchor, or the plane through the tracked assembly's base), and the box corners likewise for `FootprintM`; then `AppRoot.ShowPickTargets(list)` (and `AppRoot.PreviewBox` = the box in image UV, y up, for the preview).

### Tests (EditMode, batchmode)

```sh
$U -batchmode -nographics -projectPath quest-unity -buildTarget Android -runTests -testPlatform EditMode -testResults /tmp/editmode.xml -logFile -
```

`Assets/AG/Tests/` (editor-only asmdef `AG.Tests.EditMode`), real jobs copied to `Assets/AG/Tests/Resources/{malm-drawer,knagglig}/` (`manifest.json`, `walkthrough.glb.bytes`). 15 tests: FrameConvert (points, directions, Euler conjugation property over 20 random rotations, 90 deg about Z), glTF→Unity rules, AgLog line format, Greek uppercase without tonos, GLB guard; manifest counts (7 steps, 7 boards + 5 hardware codes, 19 nodes); **every action node vs `target_m` after FrameConvert: max_err_mm 0.0000 on malm-drawer (19 nodes) and knagglig (37)**; from_dir agrees with the rest offset 18/18; step-2 `assemblyRotationDeg` equals the GLB's `step_02` assembly pose; full glTFast instantiate (19/19 nodes at their final transform, bounds 0.380 x 0.130 x 0.418 m vs manifest 380 x 418 x 130 mm).

Editor capture without a headset: `AG_SNAP_OUT=<dir> AG_SNAP_STEPS=2:2,3:0 $U -batchmode -projectPath quest-unity -executeMethod AG.Tests.Snapshot.Run -logFile -` (no `-nographics`, no `-quit`) renders the real renderer + window for those step:action pairs to PNG (untracked mode: placed parts as faint glass, step pose applied, no crop because guide pages are not bundled). Captures: `eval/judge/round-2/unity-editor-step2.png` (step 2, right side panel: ghost + edges, dotted path from above, label "Δεξί πλαϊνό [2 από 2]" with leader line, window with toolbar) and `unity-editor-step1-cam-bolt.png` (turning arrow, ripple, label "Βίδα έκκεντρου · 118331 [1 από 2] [Ως μέσα]").

## Open, build, install

```sh
U=/Applications/Unity/Hub/Editor/6000.3.28f1/Unity.app/Contents/MacOS/Unity
# first time (or after pulling Assets/AG/Editor changes): player settings, URP, XR loader, theme, font, scene
$U -quit -batchmode -nographics -projectPath quest-unity -buildTarget Android -executeMethod AG.Editor.SetupProject.Run -logFile -
# APK -> quest-unity/Builds/ag.apk
$U -quit -batchmode -nographics -projectPath quest-unity -buildTarget Android -executeMethod AG.Editor.Build.Apk -logFile -
```

The editor can exit with code 134 (`libc++abi: terminate_handler`) *after* "Exiting batchmode successfully"; that is a shutdown crash, not a failure. Check the log for `AG build: Succeeded`.

In the GUI: Unity Hub > Add > `quest-unity/`, open with 6000.3.28f1, switch platform to Android, open `Assets/AG/Scenes/Main.unity`. Menus **AG > Setup Project** and **AG > Build APK**. Then run **Meta > Tools > Project Setup Tool > Fix All** once, and check what SetupProject logged as `SETUP-TODO` (OVRManager: *Enabled Passthrough Camera Access*, Hand Tracking Support *Controllers And Hands*, Scene and Anchor support).

Install and run (headset in developer mode, USB debugging allowed):

```sh
adb install -r quest-unity/Builds/ag.apk
adb push my-config.json /sdcard/Android/data/com.agj.unity/files/config.json      # server, key, job, lang, replay, hud
adb shell am start -n com.agj.unity/com.unity3d.player.UnityPlayerGameActivity \
  --es server https://HOST --es key KEY --es job malm-drawer --es lang el --ez hud true
adb logcat -s AGQ        # one JSON object per line
```

## Shared conventions with quest-android/ (Kotlin) and tools/quest/

- Package id **`com.agj.unity`**.
- **Launch params** `server`, `key`, `job` (optional), `lang` (`el`|`en`), `replay` (bool), `hud` (bool). Later sources override earlier: `StreamingAssets/local.config.json` (git-ignored, dev only) < `persistentDataPath/config.json` < intent extras (`--es`/`--ez`; booleans also as strings). `AG.Core.LaunchParams`. Committed example: `Assets/StreamingAssets/config.example.json`. **Never commit a key.**
- **Logs**: logcat tag `AGQ`, one JSON per line `{"t": ms since start, "app": "unity", "ev": ..., ...}` via `android.util.Log` on device, `Debug.Log("AGQ ...")` in the editor (`AG.Core.AgLog`). Events: `start` (version, params with key redacted), `job_loaded` (job, steps, actions, ms), `identify` (ms, product, confidence), `locate` (ms, next_step), `track` 1 Hz (fps, tracked, pos_m [x,y,z], yaw_deg, reproj_px, source `marker`|`touch`|`none`), `perception` (event, ms), `step` (step, action, by `auto`|`button`), `frame` every 5 s (fps), `error` (where, message). Extra: `camera`, `coach`.
- **Replay camera** (`replay=true`, `AG.Vision.ReplayCameraSource`): folder `persistentDataPath/replay/` (= `/sdcard/Android/data/com.agj.unity/files/replay/`) or `replay_dir` in the editor. Sorted `*.jpg`, `intrinsics.json` `{fx, fy, cx, cy, width, height, fps}` in pixels of those JPEGs (OpenCV convention), optional `poses.jsonl` in the canonical tools format (docs/QUEST_TESTING.md): `frame` (int), `t`, `file`, `cam_p`, `cam_q` (OpenCV camera axes), `cam_q_xr`, world right-handed Y up. Unity camera pose = mirror of (`cam_p`, `cam_q_xr`): `(x, y, -z)`, `(-qx, -qy, qz, qw)`. Legacy phase-1 lines (`pos`/`rot`/string `frame`) still parse. Plays at the dataset `fps` (default 10), loops; frames carry `CameraFrame.ReplayFrame` for the `track.frame` field.
- **Debug HUD** (`hud=true`): fps, camera, tracker state/source and reprojection error, last perception event + latency, step/action, server reachability (`GET /healthz` every 10 s).

## Module map and ownership

Each folder is one assembly (asmdef) and one owner. Interfaces (`I*.cs`, DTOs, `AssemblyModel.cs`) are **contracts**: change them only by agreement and note it here. Implementations are free.

| Folder / asmdef | Owner | Contracts | First implementations |
|---|---|---|---|
| `Assets/AG/Core` `AG.Core` | lead | `AgLog`, `LaunchParams`, `Http`, `Tween`/`Ease` | all working |
| `Assets/AG/Guide` `AG.Guide` | Guide agent | `IJobRepository`, `IGhostRenderer`, `AssemblyModel.cs` (AssemblyGuide, GuideStep, GuideAction, PartNode, PartInfo), `ManifestDto.cs`, `FrameConvert` | `HttpJobRepository` + `ResourcesJobRepository` + `GuideLoader`/`GlbAnimation` (GLB + walkthrough end pose, **tested**), `GuideBuilder`, `GhostRenderer` (real, editor-verified; `GhostRendererStub` kept as fallback) |
| `Assets/AG/Vision` `AG.Vision` | Vision agent | `ICameraSource` (+ `CameraFrame`, `CameraIntrinsics`), `IObjectTracker` (+ `TrackResult`, `MarkerConfig`), `IPoseFilter` | `ReplayCameraSource` (canonical poses.jsonl), `PcaCameraSource` (crop-aware intrinsics, async readback, untested on device), `MarkerObjectTracker` (AprilTag 41h12 + real-intrinsics pose, gates, hold; replay-scored), `TwoCornerTracker` (fingertip dwell capture), `HandJointSource` (XR Hands), `SmoothingPoseFilter` (One Euro), `MarkerSetup`, `FrameJpeg` (+ worker-thread encode). See "Vision and Net" below |
| `Assets/AG/Net` `AG.Net` | Net agent | `IServerApi`, `IPerceptionSocket`, `Dtos.cs` (exact server JSON) | `HttpServerApi` (working code, not yet run against the server), `PerceptionSocket` on `WsTransport` (ClientWebSocket; reconnect + resume, limits, typed events, latency logs; mock-server tested), `HandsSummarizer` |
| `Assets/AG/UI` `AG.UI` | UI agent | `ICoach` (+ `CoachStage`, `CoachContext`), `IStepPanel` (+ `StepView`), `IVoice`, `IDebugHud`, `UiTheme` | `CoachFlow` (§5.4 cards, retry, events, Where am I), `StepPanel` (glass window + toolbar), `StepPanelStub` (debug HUD only), `VoicePlayer` (TTS + prefetch), `UiSounds` |
| `Assets/AG/App` `AG.App` | Guide/UI agent (round 1) | `AppRoot` (composition root: the only place that names concrete classes) | `AppRoot` (tracking state -> ghost + pill, window placement, input, keys), `PinchRayInput` |
| `Assets/AG/Editor` `AG.Editor` | lead | `SetupProject.Run`, `Build.Apk` | |

Dependencies: Core <- Guide, Vision, Net <- UI <- App. Guide, Vision and Net do not reference each other.

### Frames and units (read before writing any pose code)

- Metres everywhere. Unity world is left-handed, Y up.
- **Assembly frame** = local frame of the GLB root node `assembly` after glTFast import. Manifest vectors are Blender Z-up: Blender `(x, y, z)` -> Unity `(-x, z, -y)` (`FrameConvert`; glTFast negates X). **Check once** against the GLB node at the end of the walkthrough clip (HANDOFF §2.4 milestone 1); fix only in `FrameConvert`.
- Parts rest hidden (scale 1e-4); final transforms come from sampling the `walkthrough` clip at its last frame (glTFast with `AnimationMethod.Legacy`, then `clip.SampleAnimation(root, clip.length)`).
- Camera: intrinsics in pixels, top-left origin, v down; `CameraFrame.Pixels` rows are bottom-up (Unity order). `CameraFrame.CameraPose` is in world, +Z = optical axis.
- Tracking: `TrackResult.AssemblyInWorld` maps the assembly frame to world. AppRoot copies it to `AssemblyAnchor`; the ghost renderer only ever works in the assembly frame under that anchor.

## Vision and Net (round 1)

**Marker.** The shared print is the dual strip (`tools/quest/marker/marker.json`): 36h11 id 0 for OpenCV apps, **tagStandard41h12 id 0** for this app (`jp.keijiro.apriltag` decodes only 41h12). Tag size passed to AprilTag = **0.0571429 m** (inner edge of the 80 mm black ring; 0.08 would scale every pose by 1.4x). Tag centre at **+0.05 m** along the strip x. `track.pos_m` = the **strip centre**. `MarkerSetup.LoadForApp()` reads `persistentDataPath/marker.json` when pushed (`adb push tools/quest/marker/marker.json /sdcard/Android/data/com.agj.unity/files/`), else built-in defaults, and logs `marker`.

**Axes.** AprilTag returns x right, y DOWN, z INTO the tag in the OpenCV camera. Unity camera = OpenCV camera mirrored in y, Unity tag frame = AprilTag frame mirrored in y (x right, y to the TOP edge, z INTO the face), so `R' = S R S`, `t' = S t`, `S = diag(1,-1,1)` (`TagDetection.InUnityCamera`). Strip in Unity axes: marker.json's ArUco offset `(x, y, z)` is `(x, y, -z)`.

**MALM placement** (`MarkerSetup.MalmStripInAssembly`, read from `walkthrough.glb` on 15 Nov): the drawer front is the unanimated base part, x [-0.19, 0.19], y [-0.015, 0.115], z [0, 0.016] in the assembly frame; the inside face is z = 0 and assembly -Z is up; the groove is at y ~ 0.0135. Strip centre at **(0, 0.064, 0)**, TOP edge toward the groove (-Y), so rotation **Euler(0, 0, 180)**. UNVERIFIED on a real drawer: check that the ghost front lands on the real one in step 1, then adjust `MalmStripCentreY` only.

**Tracker** (`MarkerObjectTracker`): grayscale on the main thread, AprilTag detect + AprilTag's own `estimate_tag_pose` with the frame's real fx, fy, cx, cy (+0.5 px for AprilTag's pixel-centre convention) on a worker thread; gates: hamming <= 1, decision margin >= 25, reprojection <= 3 px, jump gate (> 4 cm / 15 deg needs 3 agreeing detections); camera pose at capture; One Euro filter (`SmoothingPoseFilter`); hold: `Tracked` stays true 0.5 s after the last detection, then false with the pose kept (AppRoot then leaves the anchor where it was). Auto row-order flip after 45 frames without a tag (`track_rows`). `track` 1 Hz: fps, tracked, pos_m, yaw_deg, reproj_px, source, + state, method, asm_m, det_hz, det_ms, margin, held, age_ms. In replay (`LogEveryDetection`) one `track` per frame with integer `frame` and `q` (Unity values; score with `--unity`).

**Offline accuracy** (EditMode test `AG.Vision.Tests.VisionTests.MarkerTracker_ReplayAccuracy`, regenerated `marker-synthetic`, 15 Nov): 300/300 frames tracked; `score_track.py --unity`: filtered p50 0.83 mm / p90 0.94 mm / max 1.06 mm, still jitter 0.014 mm, yaw 0.008 deg, rotation 0.019 deg; unfiltered p50 0.77 mm / p90 1.09 mm (reference OpenCV 41h12: p50 0.81 mm / p90 1.19 mm). 13 ms/frame in the editor including JPEG decode.

```sh
.venv/bin/python tools/quest/replay/make_marker_synthetic.py
$U -batchmode -nographics -projectPath quest-unity -buildTarget Android -runTests -testPlatform EditMode -testResults /tmp/r.xml -logFile -
.venv/bin/python tools/quest/replay/score_track.py tools/quest/runs/unity-editmode-marker-synthetic --unity
```

**PCA camera** (`PcaCameraSource`): asks HEADSET_CAMERA, requests 1280x960 at 30 fps, detects new images by `Timestamp` (not `IsUpdatedThisFrame`, which depends on script order), carries `GetCameraPose()` (pose at the image timestamp) with the frame, non-blocking `AsyncGPUReadback` for CPU pixels (one in flight, <= `CpuMaxHz`; falls back to MRUK's blocking `GetColors` after 3 readback errors). Intrinsics: MRUK's crop + scale (`CropScale`). One `camera` event at start with sensor size, focal, principal point, lens offset, derived intrinsics, the naive plain-scaled ones for comparison, and FoV; `camera_first` with the image age.

**Socket** (`PerceptionSocket` on `WsTransport`): `ClientWebSocket` with worker-thread send/receive (NativeWebSocket 1.x sends synchronously on the main thread), ordered send queue, reconnect 0.5 to 8 s, re-sends start/step/action after the new hello, fatal closes 4400/4401/4404/4409 stop retrying. Typed events `StepComplete`, `WrongHardware`, `FlipNeeded`, `WrongPart`, `ConnectionChanged`. AGQ: `perception` {event, kind, ms (server), rtt_ms (client), step, action, p, seq, reason, votes}, `perception_ack` every 5 s, `perception_conn`, `perception_hello`, `hands` 1 Hz {hz, present, hand_at_target, still, raised}.

**Hands**: `HandJointSource` (Unity XR Hands: index tips and palms, world via the rig's TrackingSpace) -> `AG.Net.HandsSummarizer` (hand_at_target 5 cm to the ghost part's world box, still 1 cm / 0.5 s, raised above head - 5 cm) -> `SendHands` at 10 Hz. `TwoCornerTracker` fallback: fingertip held still 0.8 s on corner A, then B (MALM default: both ends of the front's long edge away from the groove).

## Status (15 Nov 2026)

- Batchmode Android build **succeeds**: `Builds/ag.apk`, 60 MB, about 7 min from a clean Library, 4 min incremental (IL2CPP + Gradle). `aapt2 dump badging`: package `com.agj.unity` 0.1.0, targetSdk 34, arm64-v8a, launchable `com.unity3d.player.UnityPlayerGameActivity`, permissions INTERNET, HEADSET_CAMERA, HAND_TRACKING, USE_SCENE, USE_ANCHOR_API; features PASSTHROUGH, vr.headtracking, handtracking (not required).
- SetupProject applied: IL2CPP/ARM64, min 32 / target 34, Vulkan, linear, single-pass instanced, URP asset, OpenXR loader with the Meta XR feature group + Meta Quest Support + hand tracking, OVRCameraRig with passthrough on, `PassthroughCameraAccess`, theme + Inter SDF, the scene. Still by hand (logged as SETUP-TODO): OVRManager *Enabled Passthrough Camera Access*, hand tracking mode, scene/anchor support, then Project Setup Tool > Fix All.
- TMP Essential Resources were extracted into `Assets/TextMesh Pro/` (batchmode cannot run the import dialog).
- Not run on a headset yet.
- Round 1 guide/UI (15 Nov): EditMode 15/15 in batchmode; `AG build: Succeeded`, `Builds/ag.apk` 61.5 MB (includes the two bundled test jobs, ~3 MB) with the real ghost renderer and window.

### Needs the headset (cannot be verified in the editor)

- Hand pointer pose + pinch from `OVRPlugin.GetHandState` on the OpenXR backend (UNVERIFIED); controllers via `OVRInput`. Fallback if hands give nothing: Interaction SDK ray interactors.
- Single-pass instanced rendering of `AG/Glass`, `AG/Ghost`, `AG/Flat` (written with the stereo macros; check both eyes) and TMP text in both eyes.
- Legibility at 0.8 m (sizes are in degrees per spec §2.2), glass contrast over real passthrough, frame rate with the ghost edges (`MeshTopology.Lines`) and per-frame dot billboards.
- Window placement against a real tracked drawer (needs the Vision agent's tracker); ghost alignment within 2 cm (spec §8 item 17).
- Sounds' loudness against TTS; spatial blend 0.8.

## Open questions

- PCA on the OpenXR backend with Meta Core 209 (RESEARCH.md says default path, not stated explicitly). If the camera never starts: check Horizon OS >= v74, the HEADSET_CAMERA prompt, OVRManager "Enabled Passthrough Camera Access".
- PCA intrinsics: resolved from MRUK 209's source (centred crop + scale, see Vision and Net); the first device run's `camera` event must confirm the numbers (sensor size, principal point near the centre, hfov ~70-80°).
- PCA CPU row order: MRUK flips the texture to Unity order, so `GetColors`/readback rows should be bottom-up (what the tracker expects). The tracker flips by itself if it finds no tag for 45 frames (`track_rows` event says which).
- `ClientWebSocket` (WsTransport) on Android IL2CPP: expected to work (NativeWebSocket uses the same class); `link.xml` keeps it from stripping. UNVERIFIED on device.
- GameActivity entry (`com.unity3d.player.UnityPlayerGameActivity`) with Meta SDK 209: check the Project Setup Tool does not ask to switch to Activity; if it does, update the manifest and the `am start` line here and in tools/quest/.
- Marker placement on the MALM drawer front (`MarkerSetup.MalmStripCentreY` = 0.064 m, from the GLB) needs one check on a real drawer.
