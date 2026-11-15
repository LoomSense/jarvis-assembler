# Round 1 · Quest 3 build-off: Unity (`quest-unity/`) vs Android (`quest-android/`)

```
Round:         1
Date (Athens): 2026-11-15 11:05
Judge:         judge agent, rubric v1 + Weights v2 (RUBRIC.md end), R12 per RUBRIC-R12.md and XR_NATIVE_SPEC §8
Commits:       main 0ef9168 for both apps (git archive of main into the scratchpad, never the builders' worktrees)
                 android = 5b6c461 → ea1cdb8 → 39afde1 → 0ef9168 (phase 1 + round-1 guide/UI + spec pass + vision/socket)
                 unity   = e57d543 (phase 1 skeleton only; round-1 guide/UI and vision/net work is on agent branches)
Server:        not exercised by the judge; server numbers quoted from jobs/_verify/bench_sc.txt and docs/HANDOFF.md
Device:        none. No Quest 3 run of either app exists: every device-only criterion is capped at 2, partly-device at 3.
Artifacts:     eval/judge/round-1/
                 android-build.log                     ./gradlew --no-daemon test assembleDebug, exit 0, 31 s (warm Gradle cache)
                 android-apk.sha256                    app-debug.apk 114.5 MB
                 android-jvm-marker-synthetic-agq.jsonl  JVM replay of the device tracker on the 300-frame synthetic set
                 android-track.txt                     score_track.py on that log
                 android-jvm-summary.txt               summarize.py on that log (shows the AGQ `t` unit bug)
                 android-r12-checklist.md              36 items, code level
                 unity-build.log, unity-build-exit.txt Unity 6000.3.28f1 batchmode AG.Editor.Build.Apk: Succeeded, 404 s, exit 134 after success
                 unity-apk.sha256                      Builds/ag.apk 60.1 MB
                 unity-r12-checklist.md                36 items, code level
                 secrets.txt                           git grep of main + APK strings: no keys
                 worktrees.txt, apps.txt               open branches (not scored)
```

**Not scored (open work at judging time).** Android integration agent: wiring `VisionModule` and `PerceptionSocket` into `MainActivity`, applying the Spatial SDK left-handed frame convention, fixing the AGQ `t` unit. Unity: round-1 guide/UI and vision/net agents on their branches (`agent-a07b58c…` 17 dirty files, `agent-a7bdaecd…` "wip: vision round 1"). Only what is on main counts.

## 1. Scores

### android · total **36.0 / 100** (Weights v2; 38.6 on the v1 weights)

| ID | Criterion | Wt | Score | Ev. | Points | Evidence | Cap / unverified |
|---|---|---|---|---|---|---|---|
| R1 | Identify | 6 | **2** | E1 + server E2 | 2.4 | `MainActivity.startCamera` → first camera frame in LOOK triggers `identify()` with no button; `ui/Jpeg.kt` 768 px q70; result → `catalog/open` + job polling when not ready. Server answers 0.99 in 3–4 s on the MALM page (HANDOFF §1). No identify run through this app's encoder by the judge. | partly device; same basis as round 0's "code alone 2" |
| R2 | Guide load and transforms | 6 | **2** | E2 | 2.4 | Own GLB reader + walkthrough sampling, JVM-tested on the real `malm-drawer` job: `GuideTest` (9 tests pass) asserts every part/hardware node within **2 mm** of `target_m` after Z-up → Y-up. Anchor 3 needs ≤ **1 mm** and also `knagglig`; only malm-drawer is tested and the max error is not printed. **Risk:** mesh vertices and poses go to the SDK as right-handed values (`Pose3.toSdk`, `SpatialGhostRenderer.kt:27`) while `vision/Frames.kt` says the SDK is left-handed: the drawer may render mirrored in Z. | partly device |
| R3 | 6-DoF tracking | 16 | **2** | E2 | 6.4 | AprilTag 36h11 + `solvePnPGeneric(IPPE_SQUARE)` + One-Euro, run by the JVM test through `ReplayDataset → MarkerTracker → TrackingCore`: **300/300 tracked, position error p50 1.37 / p90 1.83 / max 1.97 mm, yaw p50 0.01°, still jitter 0.014 mm, solve 2.3 ms** (`round-1/android-track.txt`). Rotation error other than yaw not scored by the tool. Synthetic, clean renders. **On main the live path cannot track:** `MainActivity.kt:202` builds `LiveCameraSource(this)` without a `HeadPoseBuffer`, so `frame.worldFromCamera` is null and `MarkerSolver.solve` returns `tracked = false` on every frame (`MarkerTracker.kt:39-44`); after 5 s the ghost goes to the fixed table spot. | cap 2, code 3 once VisionModule is wired |
| R4 | Step and state detection | 9 | **2** | E1 + server E2 | 3.6 | `/locate` with the latest JPEG in the coach flow and on "Where am I". Server baseline 83.7 % at 0.45, 0 false completes (`jobs/_verify/bench_sc.txt`). `OkHttpPerceptionSocket` (flow control, reconnect, MockWebServer tests pass) is **not used by `MainActivity` on main**: no `step_complete` can arrive. | partly device; no replay through this client's socket |
| R5 | On-object guidance | 10 | **2** | E1 | 4.0 | `SpatialGhostRenderer`: per-part GLB meshes, fresnel ghost, one ghost, mover along `from_dir` 1.5 s, dotted path + arrowhead, 290° turning arrow, turn preview, label pill with leader (name · code · "2 από 4"), pulses ≤ 1 Hz. Missing: hole ripple rings, dashed wrong outline, depth occlusion. Never seen rendered. | cap 2, code 2–3 |
| R6 | Hands and intent | 8 | **1** | E1 | 1.6 | `vision/Hands.kt` (port of `handChecks.ts`) and `SpatialInput` exist, but neither is registered by `MainActivity` on main and no `hands` message is sent. Hands are used only as panel rays. | code 2 once wired |
| R7 | Voice | 7 | **2** | E1 | 2.8 | `ServerVoice`: `/api/tts` → WAV → MediaPlayer, EN/EL, cache, two-line prefetch, step-done and lost lines. Perception `say` lines only via the uncalled `onGuidanceEvent`. No voice input. | cap 2 |
| R8 | Zero-setup flow | 8 | **2** | E1 | 3.2 | Code path: launch → one permission prompt set (CAMERA, HEADSET_CAMERA, USE_SCENE) → auto identify → build/poll → auto locate → register (marker or 5 s fixed-spot fallback) → guide; Next per action (no dwell, no perception on main). Not timed. | cap 2 |
| R9 | Performance and comfort | 8 | **2** | E1 | 3.2 | World-locked window, motion ≤ 1 Hz, `frame` render-fps every 5 s. No refresh-rate request, no foveation. **Cost risk:** `MainActivity.kt:207` `lastJpeg = frame.jpeg` forces the lazy JPEG encode on every camera frame. No fps measured. | cap 2 |
| R10 | Engineering | 5 | **3** | E2 | 3.0 | Clean build reproduced: `test assembleDebug` exit 0, **49/49 tests pass** (README says 29: stale), APK 114.5 MB, no keys in git or APK. Unit tests for axis conversion, GLB sampling, snap, lens pose, One-Euro, socket flow control. Replay camera = same tracker code path, scored headless by `score_track.py`. Not 4: no step-detection replay, and the AGQ `t` (ms since start) is read as seconds by `tools/quest/agq.py:19`, so `summarize.py` reports a 10 s log as **9966 s, 0.03 Hz, tracked 0 → gate FAIL** (`round-1/android-jvm-summary.txt`). `lint` not run. | |
| R11 | Find parts | 5 | **1** | E1 | 1.0 | Label pill with the next part's name, code, count. No `part_in_hand`, nothing in the pile. | |
| R12 | Visual and interaction quality | 12 | **1** | E1 | 2.4 | Code-level checklist **26/36 written and reachable, critical 7/8** (item 26 wrong-state unreachable, no dashed outline), `round-1/android-r12-checklist.md`. No editor/emulator or headset capture, so E1 → at most 1. | cap 1 (E1), code would support 2 with an E2 capture |
| | **Total** | **100** | | | **36.0** | | 11 of 12 criteria capped by missing device evidence |

**Measured numbers**

| Metric | Value | Source |
|---|---|---|
| Build | pass, 31 s (warm cache; README: 1 min 31 s clean), APK 114.5 MB | `round-1/android-build.log` |
| Unit tests | 49 / 49 (Params 5, Socket 3, Guide 9, Modules 6, MarkerReplay 1, VisionMath 16, Ui 3, AgqLog 6) | `app/build/test-results` in the archive copy |
| identify p50 ms, correct k/n | n/a (not run through the app) | |
| job_loaded ms (malm-drawer) | n/a | |
| max transform error mm | ≤ 2 mm asserted (value not printed), malm-drawer only | `GuideTest` |
| track rate / tracked % / jitter / error | replay: 300/300 tracked, 0.014 mm still jitter, p50 1.37 mm, yaw 0.01°; live on main: never tracked (no head pose) | `round-1/android-track.txt` |
| step accuracy, false completes | 83.7 %, 0 (server, not through this client) | `jobs/_verify/bench_sc.txt` |
| fps p50 / p5 | n/a | |
| user actions / seconds to first ghost | code: 1 permission set + 0 taps to the first ghost (fixed spot or marker), Next per action; not timed | `MainActivity.onCreate`, `frameTick` |
| errors in log | 0 in the JVM replay log | `round-1/android-jvm-marker-synthetic-agq.jsonl` |

### unity · total **23.8 / 100** (Weights v2; 23.8 on v1)

| ID | Criterion | Wt | Score | Ev. | Points | Evidence | Cap / unverified |
|---|---|---|---|---|---|---|---|
| R1 | Identify | 6 | **2** | E1 + server E2 | 2.4 | `CoachFlow.RunAsync`: first PCA frame → `FrameJpeg.Encode` → `IdentifyAsync`, no button; catalog open + polling. `PcaCameraSource` written against MRUK 209, permission request is a TODO. Same server basis as Android. | partly device |
| R2 | Guide load and transforms | 6 | **1** | E1 | 1.2 | Manifest → `GuideBuilder` → steps/actions with `FrameConvert` targets (Blender → Unity `(-x, z, -y)`). **GLB loading is a TODO** (`HttpJobRepository.cs:47`): no parts, no walkthrough sampling. | |
| R3 | 6-DoF tracking | 16 | **0** | E1 | 0.0 | `MarkerObjectTracker.Process` is a stub returning `Tracked = false`; `TwoCornerTracker` is not wired. `AssemblyAnchor` stays 0.6 m ahead, 0.5 m below the eyes where `Start()` put it. The planned package (`jp.keijiro.apriltag`) decodes **tagStandard41h12**; the printed marker and the Android app use **36h11** (`tools/quest/marker/marker.json`). | |
| R4 | Step and state detection | 9 | **2** | E1 + server E2 | 3.6 | `/locate` in the coach flow; `PerceptionSocket` connected in Guide, frames sent every `Update` when `CanSendFrame`, `step_complete` advances the step, `say_text` spoken. Reconnect TODO, NativeWebSocket on IL2CPP unverified. Server baseline 83.7 % / 0 FC. | partly device |
| R5 | On-object guidance | 10 | **1** | E1 | 2.0 | `GhostRendererStub`: a 3 cm cube at the action's manifest target under a fixed anchor; panel text only. | |
| R6 | Hands and intent | 8 | **0** | E1 | 0.0 | No hand code; hand tracking support is still a `SETUP-TODO`. | |
| R7 | Voice | 7 | **2** | E1 | 2.8 | `VoicePlayer` → `/api/tts` WAV for every action line and perception `say_text` in the launch language (text only sent, no `lang`, no fallback voice). No voice input. | cap 2 |
| R8 | Zero-setup flow | 8 | **2** | E1 | 3.2 | Code path runs launch → identify → build → load → locate → register (waits 10 s) → guide with no taps. But the panel has **no buttons**: `StepPanelStub.Pressed` is only raised by a test hook, so Next/Back/Repeat cannot be used; steps move only on `step_complete`. Errors end at "Pinch to retry" with no pinch handler. | cap 2 |
| R9 | Performance and comfort | 8 | **2** | E1 | 3.2 | Step panel world-placed with damping, yaw only; debug HUD head-locked only with `hud=true`; `frame` fps every 5 s. `FrameJpeg.Encode` for the socket on the main thread each frame it may send. No fps measured. | cap 2 |
| R10 | Engineering | 5 | **2** | E2 | 2.0 | Documented batchmode build reproduced: `AG build: Succeeded`, 0 errors, 6 min 44 s clean, APK 60.1 MB (`round-1/unity-build.log`, exit 134 after success = known shutdown crash). **0 EditMode/PlayMode tests on main.** AGQ logger, replay camera source, HUD. No keys in git. | |
| R11 | Find parts | 5 | **1** | E1 | 1.0 | Part named only inside the action sentence; no code, no label. | |
| R12 | Visual and interaction quality | 12 | **1** | E1 | 2.4 | Quad + one TMP block; code-level checklist **about 5/36, critical 4/8** (`round-1/unity-r12-checklist.md`). | cap 1 (E1) |
| | **Total** | **100** | | | **23.8** | | |

**Measured numbers**

| Metric | Value | Source |
|---|---|---|
| Build | pass: `AG build: Succeeded`, 0 errors, 6 min 44 s from a clean Library (player build 4 min 44 s), editor exit 134 after success (known shutdown crash), APK 60.1 MB, no keys in the APK | `round-1/unity-build.log`, `unity-build-exit.txt` |
| Unit tests | 0 (no test assembly on main; `-runTests` not run) | `find quest-unity -path "*Tests*"` → none |
| identify / job_loaded / transform error | n/a | |
| track rate / tracked % / jitter / error | none: tracker is a stub | `MarkerObjectTracker.cs` |
| step accuracy, false completes | 83.7 %, 0 (server only) | `jobs/_verify/bench_sc.txt` |
| fps p50 / p5 | n/a | |
| user actions / seconds to first ghost | code: 0 taps (cube at a fixed spot after ≤ 10 s register wait); not timed | `CoachFlow.cs` |

## 2. Comparison

| ID | Criterion | Wt v2 | WebXR r0 | Unity r1 | Android r1 | Best |
|---|---|---|---|---|---|---|
| R1 | Identify | 6 | 1 | 2 | 2 | tie (native) |
| R2 | Guide load | 6 | 2 | 1 | 2 | Android / WebXR |
| R3 | Tracking | 16 | 1 | 0 | **2** | Android |
| R4 | Step detection | 9 | 1 | 2 | 2 | tie (native) |
| R5 | On-object guidance | 10 | 2 | 1 | 2 | Android / WebXR |
| R6 | Hands and intent | 8 | 2 | 0 | 1 | WebXR |
| R7 | Voice | 7 | 2 | 2 | 2 | tie |
| R8 | Zero setup | 8 | 1 | 2 | 2 | tie (native) |
| R9 | Performance | 8 | 2 | 2 | 2 | tie |
| R10 | Engineering | 5 | 2 | 2 | **3** | Android |
| R11 | Find parts | 5 | 1 | 1 | 1 | tie |
| R12 | Visual quality | 12 | (not scored) | 1 | 1 | tie on score; Android 26/36 vs Unity ~5/36 in code |
| | **Weighted total, v2** | 100 | **26.4** (R12 = 0) · 28.8 if R12 = 1 | **23.8** | **36.0** | Android by 12.2 |
| | Weighted total, v1 (R1–R11) | 100 | 29.8 | 23.8 | 38.6 | |
| | Criteria capped by missing device evidence | | 7 of 11 | 9 of 12 | 11 of 12 | |
| | Device evidence level reached | | E3 (camera failure) | none | none | |

WebXR round 0 on Weights v2: the round-0 scores (1,2,1,1,2,2,2,1,2,2,1) give **26.4** without R12; R12 was not scored in round 0 (RUBRIC-R12 §header: round 0 not re-scored). If its canvas panels were scored at E1 like the natives here (1), it would be **28.8**.

| Metric | WebXR r0 | Unity r1 | Android r1 |
|---|---|---|---|
| Track rate / tracked % / error | none (touch) | none (stub) | replay 300/300, p50 1.37 mm; live unwired on main |
| fps p5 | n/a | n/a | n/a |
| Actions to first ghost (code) | ≥ 5 + 2 prompts | 0 taps (cube) | 1 prompt set, 0 taps |
| Step accuracy / false completes | server 83.7 % / 0 | same, socket wired | same, socket not wired |
| Build from clean checkout | typecheck | pass, 6 min 44 s, 0 tests | pass, 31 s, 49/49 tests |

## 3. Recommendation

- **Lead after round 1:** Android, 36.0 vs 23.8 (+12.2), driven by R3 (replay-proven marker pose, +6.4), R5/R2 (real GLB ghost, +3.2) and R10 (+1.0). Both natives already beat or match the WebXR baseline only on code; neither has a headset log.
- **Confidence:** low to medium. None of the lead is device-verified; Android's lead rests on JVM replay and code. Two Android defects on main could erase R3/R5 on the device: live tracking has no head pose, and the RH → LH conversion is not applied to what the renderer draws.
- **What would change it:** (1) the first Android device run showing the ghost mirrored, behind the user, or never tracked; (2) Unity merging its round-1 guide/UI and a 36h11 marker tracker with a replay score ≤ 15 mm / 5° (+6.4 on R3 alone, +3.2 on R2/R5) would close most of the gap.
- **For the demo at 18:00:** Android if the integration merge plus one device run shows the ghost on the drawer; otherwise WebXR with the phone-camera fallback. Say "marker-based tracking", never "markerless".

## 4. Top fixes for round 2

Ordered by points gained per hour. Points use Weights v2.

### android

**A1. [R3, R5, R8, R9, R12, R1] First device run of the integrated build** (+11.4 pts possible: R3 2 → 3 +3.2, R5 2 → 3 +2.0, R8 2 → 3 +1.6, R9 2 → 3 +1.6, R12 1 → 2 +2.4, R1 2 → 3 +0.6; ~1.5 h → about 7.6 pts/h; needs A2 merged)
- File: no code; `tools/quest/install.sh android`, `tools/quest/run.sh android --job malm-drawer --lang el --hud --secs 300`, `tools/quest/mirror.sh --record android --audio`.
- Symptom: 0 device logs; 11 of 12 criteria capped; `round-1/` has only JVM evidence.
- Expected: `start.build` = the merged commit, `start.device` Quest 3, `track` ≥ 10 Hz with `state:"tracked"` ≥ 70 % of steps 1–4, jitter ≤ 3 mm, ghost ruler offset ≤ 2 cm at 3 actions, fps p50 ≥ 72 / p5 ≥ 60 over 5 min, ≤ 5 actions and ≤ 90 s to the first ghost; a capture showing window, ornament, ghost with rim.
- Verify: `python3 -I tools/quest/summarize.py tools/quest/runs/<ts>-android/agq.jsonl` gates "tracked ≥ 80 %", "fps.p5 ≥ 60", "jitter ≤ 3 mm" pass; device checklist below complete.

**A2. [R6, R3, R4] Land the integration on main: head poses, socket, hands, LH frames, `t` unit** (+1.6 pts now: R6 1 → 2; unlocks R3/R4/R6 = 3 on device; ~1 h, in progress)
- File: `quest-android/app/src/main/java/com/agj/android/MainActivity.kt:98` (`MarkerTracker()`), `:202` (`LiveCameraSource(this)`), `:207` (`lastJpeg = frame.jpeg`); replace with `VisionModule` + `systemManager.registerSystem(vision.input)`; create `OkHttpPerceptionSocket` in `openJob`, route `onStepComplete`/`onWrongHardware`/`onUnsure` to `onGuidanceEvent`; `onHands → socket.sendHands`. Every `Transform` from WORLD through `Frames.worldToSpatial` (`SpatialGhostRenderer.kt:27` `toSdk`, mesh Z) and `fixedTableSpot` forward (`:570`, uses −Z while `Frames.kt` says the SDK's forward is +Z). AGQ `t`: `core/AgqLog.kt:31` or `tools/quest/agq.py:19`.
- Symptom: on main `MarkerSolver.solve` gets `worldFromCamera = null` and returns `tracked=false` for every live frame; no `hands` or `perception` events can appear; `summarize.py` on a 10 s log prints 9966 s and 0.03 Hz (`round-1/android-jvm-summary.txt`).
- Expected: live frames carry `worldFromCamera`; `perception` and `hands` (~10 Hz) events in the log; the fixed spot and the step window appear in front of the user; `summarize.py` reports the JVM replay log as ~10 s and ~30 Hz with tracked 1.0.
- Verify: `./gradlew test` still 49+/49+; `python3 -I tools/quest/summarize.py tools/quest/runs/android-jvm-marker-synthetic/agq.jsonl` shows duration ≈ 10 s, gate "tracked ≥ 80 %" PASS; on device `grep '"ev":"hands"' agq.jsonl | wc -l` ≥ 50 per 5 s of hands in view.

**A3. [R2] 1 mm transform test on two jobs, value logged** (+1.2 pts: 2 → 3; ~0.5 h → 2.4 pts/h)
- File: `quest-android/app/src/test/java/com/agj/android/GuideTest.kt:71` (tolerance 0.002) and new `app/src/test/resources/knagglig/`; log `max_err_mm` in `job_loaded` from `HttpJobRepository`.
- Symptom: only `malm-drawer`, tolerance 2 mm, max error not printed.
- Expected: every node of `malm-drawer` and `knagglig` within 1.0 mm of `target_m` (after Z-up → Y-up) at the walkthrough end; test prints `max_err_mm`.
- Verify: `./gradlew test --tests '*GuideTest*' -i | grep max_err_mm` shows ≤ 1.0 for both jobs.

**A4. [R4] Step detection replay through the app's own encoder and socket** (+1.8 pts: 2 → 3; ~1 h)
- File: new `quest-android/app/src/test/java/com/agj/android/net/SocketReplayTest.kt` (env-gated `AGQ_SERVER`, `AGQ_KEY`), sends `jobs/_verify/frames/*.jpg` via `OkHttpPerceptionSocket.sendFrame` and writes `runs/<name>/frames.jsonl` + `events.jsonl`; `/locate` on the 7 MALM frames.
- Symptom: socket only tested against MockWebServer; no accuracy number from this client.
- Expected: ≥ 85 % accuracy, 0 false completes, `locate.next_step == truth_step` on ≥ 5 of 7.
- Verify: `python eval/phase2/p2.py score eval/phase2/labels.json runs/android-replay/frames.jsonl runs/android-replay/events.jsonl` → ≥ 85 %, FC 0.

**A5. [R12] Wrong and lost drawn on the object: red dashed outline + ✕ badge, hole ripple, fade-relocate** (+4.8 pts: 1 → 3, only with an E4 capture where all 8 critical items pass; ~2 h)
- File: `quest-android/app/src/main/java/com/agj/android/guide/SpatialGhostRenderer.kt` (`GhostState.WRONG` branch, currently a red tint pulse only), `guide/Shapes.kt` (ring), `MainActivity.frameTick` window follow (`WindowFollow`) for fade-relocate.
- Symptom: checklist items 26 (★), 22, 32 missing in code; 26 also unreachable until A2.
- Expected: on a staged wrong runner (step 6), red dashed outline + octagon ✕ on the object and a spoken reason within 2 s; a ring on the current hole; the window fades out and in (≤ 300 ms each) instead of sliding when it relocates more than 10 cm.
- Verify: re-capture device script step 6 and one relocation; items 22, 26, 32 pass in `round-2/android-r12-checklist.md`, critical 8/8.

### unity

**U1. [R8, R9, R1] First device run of the current build** (+3.8 pts: R8 2 → 3 +1.6, R9 2 → 3 +1.6, R1 2 → 3 +0.6; ~1 h; it also answers the biggest Unity unknown: does PCA deliver frames on OpenXR + Meta Core 209)
- File: no code; `tools/quest/install.sh unity`, `tools/quest/run.sh unity --lang el --hud --secs 300`, `tools/quest/mirror.sh --record unity --audio`. Do the OVRManager `SETUP-TODO` items first (Passthrough Camera Access, hand tracking, scene/anchors).
- Symptom: no Unity log on any device; `PcaCameraSource` "untested", permission request TODO.
- Expected: `camera` event with non-zero intrinsics, `identify` `ok` on ≥ 4 of 5 tries, `frame` fps p50 ≥ 72 / p5 ≥ 60 over 5 min, ≤ 5 actions to the cube.
- Verify: `python3 -I tools/quest/summarize.py tools/quest/runs/<ts>-unity/agq.jsonl` gates "identify ≤ 4 s" and "fps.p5 ≥ 60" pass.

**U2. [R2, R5, R8] Merge round-1 guide/UI: glTFast GLB, walkthrough end pose, real ghost, glass window with a toolbar** (+3.2 pts: R2 1 → 2 +1.2, R5 1 → 2 +2.0; ~1 h to merge and build)
- File: `quest-unity/Assets/AG/Guide/HttpJobRepository.cs:47` (TODO GLB), `Guide/GhostRendererStub.cs` → real renderer, `UI/StepPanelStub.cs` → glass window + ornament with Back · Repeat · Next; `App/AppRoot.cs:68` already routes `Pressed`.
- Symptom: a 3 cm cube at the target; no parts; no buttons (`Pressed` only raised by the test hook `Press`), so Next is impossible unless perception fires.
- Expected: all 19 MALM part/hardware nodes placed from the walkthrough end; one ghost with fresnel rim, insertion path, turning arrow, label pill; a pinchable Next that logs `step` with `by:"button"`.
- Verify: batchmode `AG.Editor.Build.Apk` → `AG build: Succeeded`; an editor Play-mode screenshot of step 2 saved as `round-2/unity-editor-step2.png` (also gives R12 its E2 capture).

**U3. [R10, R2] EditMode tests for the core math, run in batchmode** (+2.2 pts: R10 2 → 3 +1.0, R2 2 → 3 +1.2 with the transform test; ~1 h)
- File: new `quest-unity/Assets/AG/Tests/Editor/AG.Tests.Editor.asmdef` + `FrameConvertTests.cs` (Blender `(x,y,z)` → `(-x,z,-y)`), `GuideTransformTests.cs` (every node vs `target_m` ≤ 1 mm for `malm-drawer` and `knagglig`), `TwoCornerTests.cs`, `AgLogTests.cs`.
- Symptom: 0 tests on main.
- Expected: ≥ 10 tests, 0 failures, `max_err_mm` ≤ 1.0 printed for both jobs.
- Verify: `$U -batchmode -nographics -projectPath <copy>/quest-unity -runTests -testPlatform EditMode -testResults round-2/unity-editmode.xml` → `failures="0"`, total ≥ 10.

**U4. [R3] Marker tracker on the printed 36h11 marker, replay-scored** (+6.4 pts: 0 → 2; ~3 h → 2.1 pts/h)
- File: `quest-unity/Assets/AG/Vision/MarkerObjectTracker.cs` (stub `Process`); the detector must read **AprilTag 36h11** (`tools/quest/marker/marker.json`) or the dual 36h11 + 41h12 strip from the tools branch; `jp.keijiro.apriltag` only decodes tagStandard41h12. Own PnP with fx, fy, cx, cy (the package assumes the principal point at the centre).
- Symptom: `Tracked = false` always; `AssemblyAnchor` fixed at 0.6 m ahead.
- Expected: on `marker-synthetic` replay, ≥ 95 % frames tracked, error ≤ 15 mm / 5° (anchor 2), `track` events with `state`, `method:"marker"`, `frame`.
- Verify: `tools/quest/install.sh unity --replay marker-synthetic; tools/quest/run.sh unity --replay --secs 40; .venv/bin/python tools/quest/replay/score_track.py tools/quest/runs/<ts>-unity` → p50 ≤ 15 mm, yaw ≤ 5° (or the same from an editor replay run if no headset).

**U5. [R6] Hand summaries to the socket at 10 Hz** (+3.2 pts: 0 → 2; ~1.5 h → 2.1 pts/h)
- File: new `quest-unity/Assets/AG/Vision/HandSummary.cs` (OVRHand / XR Hands index tip and palm, `hand_at_target` 5 cm, `still` 1 cm / 0.5 s, `raised`, port of `client/src/xr/handChecks.ts`), sent via `PerceptionSocket` (`type:"hands"`), wired in `AppRoot.Update`; OVRManager Hand Tracking Support = Controllers And Hands.
- Symptom: no hand code; hand tracking is a `SETUP-TODO`.
- Expected: `hands` messages at 9–11 Hz in the log while hands are in view; `hand_at_target` true when the index tip is within 5 cm of the ghost.
- Verify: on device, `grep -c '"type":"hands"'` over a 10 s window ≥ 90; in the editor with the XR simulator, a test hand at the target logs `hand_at_target:true`.

## 5. First device-run checklist (what must be captured so device-only criteria can rise above 2)

Same table, same light, the real MALM drawer with the printed 80 mm 36h11 marker placed per `tools/quest/marker/marker.json`. Run `tools/quest/doctor.sh` first (exit 0). Record **at the same time** the AGQ log (`run.sh`) and a mirror recording with audio (`mirror.sh --record <app> --audio`); screenshots with `capture.sh`. Write the APK hash and the commit in the run note; `start.build` must match.

### android (`com.agj.android`, after A2 is on main)

1. `tools/quest/install.sh android`; grant HEADSET_CAMERA, CAMERA, USE_SCENE at the prompt (note the number of prompts). `run.sh android --lang el --hud --secs 360` without `--job` (zero-setup path).
2. **R8:** time and count from launch to the first ghost: taps, pinches, prompts, typed characters. Log: `coach` stage timestamps.
3. **R1:** look at the MALM box or guide page; 5 tries (Repeat restarts identify). Need `identify` `ok` on ≥ 4 of 5, p50 < 4 s. Save one `camera_frame` / `camera` event (intrinsics, lens pose).
4. **Frames sanity (the risk):** in the first 10 s of guidance, screenshot: is the drawer ghost mirrored? Is the window in front of you at start? Is the fixed spot in front (not behind)? Note `vision.json` `lens_convention` used.
5. **R3:** with the marker visible (steps 1–4): hold still 3 s (jitter), slide the drawer 20 cm, then turn it 90°; tape-measure both. Need `track` ≥ 10 Hz, `state:"tracked"` ≥ 70 %, jitter ≤ 3 mm, ghost follows within 1 s in the capture.
6. **R5:** ruler offset of the ghost against the real holes at 3 actions (step 1 cam bolt, step 2 side panel, step 3 cam lock), each with a capture frame. Need ≤ 2 cm.
7. **R4:** do step 1 and step 2 for real; `perception` `step_complete` within 4 s; count false completes. "Where am I" at a known step: `locate.next_step` vs truth.
8. **R6 / R11:** hands at the target hole and at a wrong hole, 5 each; `hands` messages ~10 Hz; wrong-place warning count. Hold the wrong runner (step 6, DR on the left): spoken reason + red state.
9. **R7:** speech audible in EL, then relaunch with `--lang en`; note any line not spoken.
10. **R9:** 5 minutes of guidance with camera, tracking and socket on: `frame` events (render fps) p50 / p5; any stall > 100 ms (`adb logcat -d -s VrApi | grep FPS`).
11. **R12:** the RUBRIC-R12 §3 capture: coach flow from launch, step 2 place, step 3 fasten with turning arrow, step 4 turn, step 6 staged wrong part, completion, one 30° head turn while looking at the window; audio on; 3–6 min. Then fill all 36 items with timestamps.
12. Pull: `agq.jsonl`, `logcat.txt`, the mp4, screenshots into `eval/judge/round-2/android-*`.

### unity (`com.agj.unity`, the build on main or the merged round-1 build)

1. In the editor once: OVRManager *Enabled Passthrough Camera Access*, Hand Tracking Support = Controllers And Hands, Scene + Anchor support, Project Setup Tool > Fix All; rebuild with `AG.Editor.Build.Apk`; note the APK hash.
2. `tools/quest/install.sh unity`; `run.sh unity --lang el --hud --secs 360` without `--job`. Note every prompt (HEADSET_CAMERA is requested by the system or not at all: the code's request is a TODO).
3. **Camera (the risk):** the HUD camera line and the `camera` event: running, resolution, intrinsics non-zero. If 0 frames: stop and record `logcat` for `PassthroughCameraAccess` errors; R1/R4/R8 become device failures.
4. **R8 / R1:** launch to first ghost (cube on main): actions and seconds; identify 5 tries, `ok` count, p50 ms.
5. **R4:** confirm `PerceptionSocket` connects on IL2CPP (`perception` events, or the HUD last-event line); do step 1 for real, `step_complete` count and delay; note any false complete. On main this is the **only** way to advance (no buttons).
6. **R3 (if the merged tracker is in):** same move-and-turn script as Android, with the 36h11 marker; `track` rate, tracked %, jitter.
7. **R5 (if the merged renderer is in):** ruler offset at 3 actions with capture frames.
8. **R7:** EL and EN speech audible.
9. **R9:** 5 minutes: `frame` fps p50 / p5, `VrApi` FPS lines, stalls.
10. **R12:** the same RUBRIC-R12 §3 capture and 36-item fill.
11. Pull everything into `eval/judge/round-2/unity-*`.

## 6. Biggest risk per app

- **android:** frame conventions on the device. On main the renderer receives right-handed poses and raw GLB vertices while `vision/Frames.kt` states the Spatial SDK is left-handed (+Z forward), and `fixedTableSpot` / the start window assume −Z forward; plus live tracking has no head pose until `VisionModule` is wired. If any of these is wrong on the headset, the ghost is mirrored, behind the user, or never on the drawer, which takes R3, R5 and R12 down together. Only a device run (A1 step 4) settles it.
- **unity:** no tracking at all with the freeze at 18:00. The tracker is a stub and the chosen AprilTag package cannot read the 36h11 marker everyone printed; PCA camera delivery on OpenXR + Meta Core 209 is unproven. Without a replay-scored tracker Unity stays at R3 = 0 (16 points) whatever the UI work achieves.
