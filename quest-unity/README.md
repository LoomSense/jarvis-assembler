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
- **Replay camera** (`replay=true`, `AG.Vision.ReplayCameraSource`): folder `persistentDataPath/replay/` (= `/sdcard/Android/data/com.agj.unity/files/replay/`) or `replay_dir` in the editor. Sorted `*.jpg`, `intrinsics.json` `{fx, fy, cx, cy, width, height}` in pixels of those JPEGs (top-left origin), optional `poses.jsonl`: one line per frame `{"file":"000000.jpg","t":0.0,"pos":[x,y,z],"rot":[x,y,z,w],"frame":"unity"|"openxr"}`, camera pose in world (`unity`: left-handed Y-up, camera +Z forward; `openxr`: right-handed, -Z forward, converted by mirroring Z). 10 fps, loops.
- **Debug HUD** (`hud=true`): fps, camera, tracker state/source and reprojection error, last perception event + latency, step/action, server reachability (`GET /healthz` every 10 s).

## Module map and ownership

Each folder is one assembly (asmdef) and one owner. Interfaces (`I*.cs`, DTOs, `AssemblyModel.cs`) are **contracts**: change them only by agreement and note it here. Implementations are free.

| Folder / asmdef | Owner | Contracts | First implementations |
|---|---|---|---|
| `Assets/AG/Core` `AG.Core` | lead | `AgLog`, `LaunchParams`, `Http`, `Tween`/`Ease` | all working |
| `Assets/AG/Guide` `AG.Guide` | Guide agent | `IJobRepository`, `IGhostRenderer`, `AssemblyModel.cs` (AssemblyGuide, GuideStep, GuideAction, PartNode, PartInfo), `ManifestDto.cs`, `FrameConvert` | `HttpJobRepository` (manifest + files work; **GLB + walkthrough sampling TODO**), `GuideBuilder` (manifest -> model, working), `GhostRendererStub` (cube at target) |
| `Assets/AG/Vision` `AG.Vision` | Vision agent | `ICameraSource` (+ `CameraFrame`, `CameraIntrinsics`), `IObjectTracker` (+ `TrackResult`, `MarkerConfig`), `IPoseFilter` | `ReplayCameraSource` (working), `PcaCameraSource` (written against MRUK 209 API, untested on device), `MarkerObjectTracker` (**stub**: AprilTag + PnP TODO), `TwoCornerTracker` (solver written), `SmoothingPoseFilter`, `FrameJpeg` |
| `Assets/AG/Net` `AG.Net` | Net agent | `IServerApi`, `IPerceptionSocket`, `Dtos.cs` (exact server JSON) | `HttpServerApi` (working code, not yet run against the server), `PerceptionSocket` (framing, limits, routing; reconnect TODO) |
| `Assets/AG/UI` `AG.UI` | UI agent | `ICoach` (+ `CoachStage`, `CoachContext`), `IStepPanel` (+ `StepView`), `IVoice`, `IDebugHud`, `UiTheme` | `CoachFlow` (happy path), `StepPanelStub` (TMP text on a quad), `VoicePlayer` (TTS WAV playback, working) |
| `Assets/AG/App` `AG.App` | lead | `AppRoot` (composition root: the only place that names concrete classes) | |
| `Assets/AG/Editor` `AG.Editor` | lead | `SetupProject.Run`, `Build.Apk` | |

Dependencies: Core <- Guide, Vision, Net <- UI <- App. Guide, Vision and Net do not reference each other.

### Frames and units (read before writing any pose code)

- Metres everywhere. Unity world is left-handed, Y up.
- **Assembly frame** = local frame of the GLB root node `assembly` after glTFast import. Manifest vectors are Blender Z-up: Blender `(x, y, z)` -> Unity `(-x, z, -y)` (`FrameConvert`; glTFast negates X). **Check once** against the GLB node at the end of the walkthrough clip (HANDOFF §2.4 milestone 1); fix only in `FrameConvert`.
- Parts rest hidden (scale 1e-4); final transforms come from sampling the `walkthrough` clip at its last frame (glTFast with `AnimationMethod.Legacy`, then `clip.SampleAnimation(root, clip.length)`).
- Camera: intrinsics in pixels, top-left origin, v down; `CameraFrame.Pixels` rows are bottom-up (Unity order). `CameraFrame.CameraPose` is in world, +Z = optical axis.
- Tracking: `TrackResult.AssemblyInWorld` maps the assembly frame to world. AppRoot copies it to `AssemblyAnchor`; the ghost renderer only ever works in the assembly frame under that anchor.

## Status (15 Nov 2026)

- Batchmode Android build **succeeds**: `Builds/ag.apk`, 60 MB, about 7 min from a clean Library, 4 min incremental (IL2CPP + Gradle). `aapt2 dump badging`: package `com.agj.unity` 0.1.0, targetSdk 34, arm64-v8a, launchable `com.unity3d.player.UnityPlayerGameActivity`, permissions INTERNET, HEADSET_CAMERA, HAND_TRACKING, USE_SCENE, USE_ANCHOR_API; features PASSTHROUGH, vr.headtracking, handtracking (not required).
- SetupProject applied: IL2CPP/ARM64, min 32 / target 34, Vulkan, linear, single-pass instanced, URP asset, OpenXR loader with the Meta XR feature group + Meta Quest Support + hand tracking, OVRCameraRig with passthrough on, `PassthroughCameraAccess`, theme + Inter SDF, the scene. Still by hand (logged as SETUP-TODO): OVRManager *Enabled Passthrough Camera Access*, hand tracking mode, scene/anchor support, then Project Setup Tool > Fix All.
- TMP Essential Resources were extracted into `Assets/TextMesh Pro/` (batchmode cannot run the import dialog).
- Not run on a headset yet.

## Open questions

- PCA on the OpenXR backend with Meta Core 209 (RESEARCH.md says default path, not stated explicitly). If the camera never starts: check Horizon OS >= v74, the HEADSET_CAMERA prompt, OVRManager "Enabled Passthrough Camera Access".
- PCA intrinsics: given for `SensorResolution`; we scale to `CurrentResolution` (UNVERIFIED, compare with Meta's CameraToWorld sample).
- `jp.keijiro.apriltag` pose assumes the principal point at the image centre; may need our own PnP with cx, cy.
- NativeWebSocket on Android IL2CPP (UNVERIFIED); fallback: `System.Net.WebSockets.ClientWebSocket`.
- GameActivity entry (`com.unity3d.player.UnityPlayerGameActivity`) with Meta SDK 209: check the Project Setup Tool does not ask to switch to Activity; if it does, update the manifest and the `am start` line here and in tools/quest/.
- Marker placement on the MALM drawer front (`MarkerConfig.TagInAssembly`) needs measuring.
