# Round 2 · Quest 3 build-off: Unity (`quest-unity/`) vs Android (`quest-android/`), no printed marker

```
Round:         2
Date (Athens): 2026-11-15 12:45
Judge:         judge agent, rubric v1 + Weights v2 (6,6,16,9,10,8,7,8,8,5,5,12), R12 per RUBRIC-R12.md and XR_NATIVE_SPEC §8
Commits:       main moved twice while this round was judged (50b743c → acb0291 → 6d52be8, 20 commits).
               Scored: main 6d52be8.
                 android: full read at acb0291; the two later commits (da01638 panel detector, 6d52be8 wiring + depth
                          surface) read as diffs of MainActivity / WorldModule / Params; vision/PanelPose.kt not audited.
                 unity:   full read at b9ee907 (= acb0291 tree); the world layer that landed after (b56aa90 … b9f320f)
                          read as the AppRoot diff, WorldText.cs and the README section; World/*.cs not audited.
Server:        not exercised.
Device:        none this round. The round-2 builds have not been seen on the headset. Older-build logs under
               tools/quest/runs/ are quoted as "indicative" and do not lift any cap (RUBRIC §3: evidence must come
               from the scored commit).
Judge runs:    (scratchpad only, nothing written under the repo except this file)
                 android @ acb0291  ./gradlew --offline test            exit 0, 19 s, 98 tests per variant, 0 failed, 2 skipped
                 android @ 6d52be8  ./gradlew --offline testDebugUnitTest exit 0, 19 s, 123 tests, 0 failed, 4 skipped;
                                    with tools/markerless/sample staged beside the export: PanelPoseTest 24 tests, 1 skipped
                 unity   @ acb0291  Unity 6000.3.28f1 -runTests EditMode  29 tests: 27 passed, 2 inconclusive, 0 failed
                                    (the 29 World tests added later were not run)
               No APK build was re-run this round for either app.
Not mine:      at 12:26 the main checkout showed uncommitted quest-unity changes (ProjectSettings.asset,
               DevAgentSettings.asset, Resources/PerformanceTestRunInfo.json) written by a concurrent
               `AG.Editor.Build.Apk` run in the checkout; they were gone again by 12:50. Check `git status` before
               committing this file.
```

## 1. Scores

### android · total **39.8 / 100** (round 1: 36.0)

| ID | Criterion | Wt | Score | Ev. | Points | Evidence | Cap / note |
|---|---|---|---|---|---|---|---|
| R1 | Identify | 6 | **2** | E1 + server E2 | 2.4 | Auto identify on the first LOOK frame, `MainActivity.kt` visionListener → `identify()`. No `identify` event in any device log (runs used a preset job). | partly device |
| R2 | Guide load and transforms | 6 | **3** | E2 | 3.6 | Judge run: `malm-drawer: 19 nodes, max_err_mm 0.0000`, `knagglig: 37 nodes, max_err_mm 0.0000` (GuideTest output). Round 1: 2. | cap 3 |
| R3 | 6-DoF tracking | 16 | **2** | E2 | 6.4 | No-marker path wired: table (`WorldModule.kt:298`) or fixed spot (`:300`), server box, on-device front-panel detector at 3 Hz (`MainActivity.kt:204`). Judge run of `fullDetectorMatchesThePythonReferenceOnTheSampleFrames`: Kotlin = Python on 6 frames; the 2 trusted frames are 3.7 mm / 0.31° and 3.3 mm / 0.60° from ground truth, 4 frames correctly untrusted. Thin: 2 trusted synthetic frames, x/y/yaw on the table plane only, enabled on the first step only (`MainActivity.kt:583`). The marker replay test was skipped (set not generated) and the marker is out of the demo. | cap 2 |
| R4 | Step and state detection | 9 | **2** | E1 + server E2 | 3.6 | Socket wired (`MainActivity.kt` `openSocket`, frames when `canSend()`), `step_complete` → `advance(wholeStep)`. Indicative (older build, run 112320): connected, 19 sent / 20 acked, `dropped.gated: 20`, 0 perception events, ack p50 60 ms. No replay through this client. | partly device |
| R5 | On-object guidance | 10 | **2** | E1 | 4.0 | Ghost, hard edges, path, turning arrow, hole rings, label pill, dashed wrong/lost, all through `GhostMat.create` (`SpatialGhostRenderer.kt:155-166`). Round-1 build drew an opaque orange ghost on the headset; the round-2 shader fix is unseen. | cap 2 |
| R6 | Hands and intent | 8 | **2** | E1 | 3.2 | `onHands → socket.sendHands`, `vision.handTarget` from the ghost box. Indicative: `perception_stats.hands: 50` on the older build. Round 1: 1. | cap 2 |
| R7 | Voice | 7 | **2** | E1 | 2.8 | `/api/tts` EL/EN + push-to-talk `/api/voice` (`VoiceController.kt`). Indicative: one `voice` event `lang: el`, 76 ms. Not heard on this build. | cap 2 |
| R8 | Zero-setup flow | 8 | **2** | E1 | 3.2 | One prompt set of four (camera, headset camera, scene, mic), zero taps to the first ghost in code. Not timed. | cap 2 |
| R9 | Performance and comfort | 8 | **2** | E1 | 3.2 | World-locked panels. Indicative (older build, 80 s): render fps p50 89 / p5 80.9. This build adds a 3 Hz detector, `/parts` every 2.5 s and depth probes: unmeasured. | cap 2 |
| R10 | Engineering | 5 | **3** | E2 | 3.0 | Tests reproduced (above). Not 4: marker replay skipped, no step-detection replay, and the three latest `-android` run folders hold no AGQ line at all (logcat captured at error level only). | |
| R11 | Find parts | 5 | **2** | E1 | 2.0 | `/parts` → world ray → `PickMarkers` ring + chevron (`MainActivity.kt` `pickMarkers.update`). Round 1: 1. | cap 2 |
| R12 | Visual and interaction quality | 12 | **1** | E1 | 2.4 | No emulator, editor or headset capture of the round-2 build exists. | cap 1 (E1); an E2 capture would allow 2 |
| | **Total** | 100 | | | **39.8** | | |

### unity · total **39.0 / 100** (round 1: 23.8)

| ID | Criterion | Wt | Score | Ev. | Points | Evidence | Cap / note |
|---|---|---|---|---|---|---|---|
| R1 | Identify | 6 | **2** | E1 + server E2 | 2.4 | `UI/CoachFlow.cs:109-143`, skipped when `job` is passed, which both device runs did. Indicative (build 6430526): PCA delivers 1280×960, fx 869, first frame age 71 ms: the round-1 camera risk is retired. | partly device |
| R2 | Guide load and transforms | 6 | **3** | E2 | 3.6 | Judge run: `max_err_mm=0.0000` for both jobs (`Tests/GuideLoadTests.cs`); captures show parts in place. Round 1: 1. | cap 3 |
| R3 | 6-DoF tracking | 16 | **1** | E1 | 3.2 | Without a marker: MRUK table or head fallback, then the server box moves the anchor (`World/WorldModule.cs`, wired at `App/AppRoot.cs:216-217`). No on-device detector, no replay number. The 41h12 marker tracker's replay test went inconclusive in the judge run. Round 1: 0. | code 1 |
| R4 | Step and state detection | 9 | **2** | E1 + server E2 | 3.6 | Indicative (6430526): `locate` 1.4–1.5 s, socket open + hello + ack RTT 212 ms on IL2CPP. | partly device |
| R5 | On-object guidance | 10 | **2** | E2 | 4.0 | `round-2/unity-editor-step2.png`, `unity-editor-step1-cam-bolt.png`: ghost, dotted path, turning arrow, ring, label with code. | cap 2 |
| R6 | Hands and intent | 8 | **2** | E1 | 3.2 | XR Hands → `HandsSummarizer` → `SendHands` at 10 Hz (`App/AppRoot.cs`). No `hands` event seen on device. Round 1: 0. | cap 2 |
| R7 | Voice | 7 | **2** | E1 | 2.8 | TTS sends text without `lang` (`Net/HttpServerApi.cs:50-51`); push-to-talk to `/api/voice`. | cap 2 |
| R8 | Zero-setup flow | 8 | **2** | E1 | 3.2 | Zero taps, but the Register card waits 12 s for a marker (`UI/CoachFlow.cs:166-169`, `UI/ICoach.cs:53`). Indicative: Guide stage 15.8 s after launch with a preset job. | cap 2 |
| R9 | Performance and comfort | 8 | **2** | E1 | 3.2 | Indicative (6430526, 7 samples): 69.0–72.0 fps, on the 72 line. Debug HUD is head-locked with `hud=true`. | cap 2 |
| R10 | Engineering | 5 | **3** | E2 | 3.0 | EditMode tests reproduced from a clean export; batchmode build method documented. Round 1: 2. | |
| R11 | Find parts | 5 | **2** | E1 | 2.0 | `_world.PickTargets += ShowPickTargets` (`App/AppRoot.cs:126`), wiring read only. Round 1: 1. | cap 2 |
| R12 | Visual and interaction quality | 12 | **2** | E2 | 4.8 | Eight editor captures of the app's own build. Of 23 items a still can show, 21 pass, 1 fails (item 14), 1 is unclear (item 18 in `unity-parts.png`). No "never" breach seen. | cap 2 |
| | **Total** | 100 | | | **39.0** | | |

Check: `python3 -c "w=[6,6,16,9,10,8,7,8,8,5,5,12]; print([round(sum(a/5*b for a,b in zip(s,w)),1) for s in ([2,3,2,2,2,2,2,2,2,3,2,1],[2,3,1,2,2,2,2,2,2,3,2,2])])"` → `[39.8, 39.0]`.

The 0.8 gap is inside the noise of this round. Android leads on R3 (+3.2, an on-device detector with a judge-run test), Unity leads on R12 (+2.4, because captures exist). Every device-only cell is still capped.

### What the Unity captures show (R12, E2)

| Capture | Pass | Problem |
|---|---|---|
| `unity-editor-step1-cam-bolt.png`, `unity-editor-step2.png` | Glass window, toolbar capsule, one primary button, ghost with edges, path, turning arrow, ring, pill with code and count | These two predate the mic button (toolbar shows "Πού είμαι") |
| `unity-wrong.png` | Red dashed outline, octagon ✕, "πάει εδώ" note | Status line reads "ΛΑΘΟς · ΑΥΤΟς ΕΙΝΑΙ Ο ΔΕΞΗς ΟΔΗΓΟς. ΑΡΙΣΤΕΡΑ ΜΠΑΙΝΕΙ Ο ΑΡΙΣΤΕ…": lower-case final sigma in capitals, and the spoken reason is squeezed into the caption line and cut |
| `unity-complete.png` (never opened by its author) | Calm green rings | Label "ΤΕΛΟς"; the only button says "Επόμενο ›"; camera caption cut ("κάμερα ζωντανά · …") |
| `unity-turn.png` | Two-pose outline with a curved arrow | Instruction ends mid-sentence with no ellipsis: "…με την πρόσοψη όρθια στην άκρη ενός" |
| `unity-parts.png` | Parts card with turntable, name, code | Instruction cut at "Βί…"; a whole drawer frame is drawn as white glass at ΒΗΜΑ 1 (staging or a real leak of later parts: check on device) |
| `unity-pick.png` | Ring + chevron cue | Three pills at once, two cut by the view edge; card name cut ("Αριστερό πλα…") |
| `unity-lost.png` | Amber diamond pill, ghost faint and dashed | Window sits over the table area |

## 2. README claims the code contradicts

**android**
1. "No jitter": moves only on answers "more than 10 cm" apart "for at least 4 s", checked "every 10 s". Code: `disagreeM = 0.05f, disagreeMs = 0L` (`world/WorldModule.kt:100`), `FOLLOW_EVERY_MS = 2500L` (`:384`). Two agreeing answers more than 5 cm away move the ghost, polled every 2.5 s.
2. Status header: "Never run on a headset yet" and "round 2 vision/net/world on branch". Device runs exist and the world module is on main.
3. "70 JVM tests (1 skipped)" in the round-2 bullet against "98" two paragraphs up. Judge: 98 at acb0291, 123 at 6d52be8.
4. "Not done: the outline needs the mesh hook passed to WorldModule". It is passed (`MainActivity.kt` WorldModule constructor).
5. WorldModule KDoc: "menu / voice: world.realign()". The only caller is `whereAmI()` (`MainActivity.kt:801`), whose button is hidden whenever the mic button exists (`ui/Panels.kt:431`).

**unity**
1. Touch fallback listed as wired: `TwoCornerTracker` is created and ticked but `BeginCapture` is never called, so it can never capture (`Vision/TwoCornerTracker.cs:51`).
2. "Latin + Greek pre-baked" font: `Inter SDF.asset` has empty glyph tables and clears dynamic data on build; glyphs are rasterised at runtime from the bundled TTF. Greek should render, with a first-use hitch.
3. "28 passed, 1 inconclusive": judge run gives 27 passed, 2 inconclusive (`MarkerJson_StripSpec`, `MarkerTracker_ReplayAccuracy`), so the 0.83 mm marker figure is not reproduced.
4. Status section: "Not run on a headset yet", hand tracking and PCA as manual setup. `OculusProjectConfig.asset` already has them and two device runs exist.
5. Grab bar listed as a window piece: drawn only, no move interaction (`UI/StepPanel.cs:122-124`).
6. Placement "fixed": the assembly anchor is re-placed, the coach cards are still placed once on the first frame with a camera (`App/AppRoot.cs` `PlaceInFront`).
7. No RECORD_AUDIO in the permission list or in `Assets/Plugins/Android/AndroidManifest.xml`; it relies on Unity adding it. Not checked in an APK.

## 3. Ranked defects, first-time user, headset on, no marker

Points are zero until a device run exists; each fix names what it unblocks.

### android

**A1. [R12 item 1/4, spec §2.3] The step window lands about 45–52° to the right of the work**
- File: `ui/WindowFollow.kt:36-37` (`side = objectHalfWidth + 0.10 + halfWidth`), called at `MainActivity.kt:878` with 0.22; side panel `MainActivity.kt:915`.
- Symptom (from the numbers): lateral offset 0.58 m for an object 0.45–0.55 m ahead puts the window centre at 46–52° and its far edge past 65°; the parts card sits beyond 70°. Looking at the ghost, the text is outside the readable field.
- Expected: window centre within 30° of the object direction, 0.75–0.95 m away, 10–20° below eye level.
- Smallest fix: after computing `p`, clamp its bearing from the object direction to 30° at 0.82 m (`head + rotY(dirToObject, −30°) × 0.82`), keep the existing height clamp.
- Verify: a `WindowFollowTest` case (object 0.45 m ahead → bearing ≤ 30°); on device AGQ `panels` / `window` `pos_m` and a capture with ghost and window in one frame.

**A2. [R5, R12 item 19] A wrong ghost shader paints everything, and the fallback cannot see it**
- File: `guide/GhostMaterial.kt:61-69` (fallback only when `SceneMaterial.custom` throws), `MainActivity.kt:216` (override).
- Symptom: round 1 drew an opaque orange ghost without throwing. The same material path now feeds 14 roles, including the table outline (`world/PlaceOutline.kt:82`) and pick markers (`world/PickMarkers.kt:124-125`).
- Expected: translucent azure with brighter edges; `ghost_material` `kind` logged.
- Smallest fix: look first (check list 1). If it is not glass, make unlit the default at `MainActivity.kt:216` and keep `--es ghost_material custom` as the opt-in.
- Verify: AGQ `ghost_material`, one screenshot.

**A3. [R3, R8] The ghost is placed where the head pointed when the guide loaded, and nothing on screen can move it**
- File: `world/WorldModule.kt:298-300` (placed once from `vision.heads.newest()`), `MainActivity.kt:797-801` (`realign` only from `whereAmI`), `ui/Panels.kt:431` (that button hidden when the mic exists), `world.nudge` has no caller, `MainActivity.kt:583` (detector on the first step only).
- Symptom: card 1 tells the user to look at the box; the anchor lands 0.45 m in front of that gaze. If the server box and the detector do not fire, it stays there.
- Expected: the outline appears on the table where the user is looking when guidance starts; one visible action re-places it.
- Smallest fix: `world.anchor.reset()` at `CoachFlow.Stage.GUIDE` (`MainActivity.kt:402`) so placement uses the head pose at that moment, and the same call in `repeat()` (`:792`) while the base step is on.
- Verify: AGQ `place` with `reason: initial` after `coach to: GUIDE`, and again after Ξανά.

**A4. [R8] The one instruction of the no-marker flow is a clipped caption and is never spoken**
- File: `MainActivity.kt:584` (coach line → status text), `ui/Panels.kt:379-393` (caption, `maxLines = 1`, clip), `MainActivity.kt:591` (voice says the step line only).
- Symptom: «Βάλε το κομμάτι «Πρόσοψη συρταριού» μέσα στο μπλε περίγραμμα.» is about 62 characters in a slot that fits about 40 at 12 mm.
- Smallest fix: when `world.coachLine` is non-null, put it in `body` (two lines at 17.5 mm) and say it before the step line.
- Verify: AGQ `voice` `text` starts with the line; capture of step 1.

**A5. [R8] Marker and touch leftovers on the Register card**
- File: `ui/Strings.kt:29` "Look at the drawer front and its marker." and `:81` «Κοίτα την πρόσοψη του συρταριού και το σημάδι της.», shown at `MainActivity.kt:399` for up to 5 s when no pose exists yet; `armTouch()` at `:400` arms a two-corner touch that no card explains.
- Smallest fix: reword to "Look at the table in front of you." / «Κοίτα το τραπέζι μπροστά σου.» and delete the `armTouch()` call.
- Verify: AGQ `coach to: REGISTER` then `to: GUIDE` under 1 s with a table; no `touch_start` event.

**A6. [R7] Voice lines cut each other and mic failures are silent**
- File: `ui/Voice.kt:27,109-111` (every `say` interrupts), `MainActivity.kt:733` ("Το βήμα N έγινε. Επόμενο: …") then `:591` 1.4 s later; `ui/VoiceController.kt:68-71` (denied permission: re-request, no message), `:79` + `ui/VoiceCommands.kt:90` (presses ignored for up to 25 s while "thinking"), `MainActivity.kt:672` (errors outside GUIDE dropped, the "voiceFailed" status never clears).
- Smallest fix: say only "Το βήμα N έγινε." and wait for `!voice.speaking` (3 s limit) before `showStep`; show `voiceFailed` when permission is missing; read timeout 8 s; clear the status after 3 s like `heard`.
- Verify: AGQ `voice` events in order without overlap; `error where: voice_cmd` within 8 s with the server stopped.

Also noted: a denied camera or scene permission leaves card 1 up for ever with only a log line (`MainActivity.kt:277`); Next shows "ΣΩΣΤΑ", a green ghost and the success chime although nothing was checked (`:728`); the body can lose its last words when the drawing column is shown (two lines of about 31 characters). Greek strings are complete (`ui/Strings.kt`, `WorldStrings`), capitals drop the tonos and map the final sigma.

### unity

**U1. [R8] The Register card asks for a marker and stalls 12 s; the outline hint is wiped when the guide starts**
- File: `UI/CoachFlow.cs:166-169` ("Look at the marker on the drawer front." / «Κοίτα το σημάδι στην πρόσοψη.», title spoken), `UI/ICoach.cs:53` (`RegisterTimeoutS = 12f`); `App/AppRoot.cs:218-222` sets the outline pill once on a latch, `UI/CoachFlow.cs:320` clears every pill on each action.
- Symptom: indicative log shows Register → Guide at 15.8 s. The pill «Βάλε το κομμάτι στο μπλε περίγραμμα» can only show during the cards and never during step 1.
- Smallest fix: `RegisterTimeoutS = 0` when no marker has been seen, reword the card with `WorldText.PlaceInOutline`, and reset `_outlineHint = false` in AppRoot when the coach shows a new action (or stop `ShowCurrent` clearing a pill it did not set).
- Verify: AGQ `coach stage: Register` → `Guide` under 2 s; capture of step 1 with the pill.

**U2. [R3, R12 item 1] Without scene data the ghost floats and the window overlaps it**
- File: `App/AppRoot.cs:175` (0.55 m ahead, 0.22 m below the eyes), README world layer (head fallback 0.35 m below), `App/AppRoot.cs:182` (`MaxSideDeg = 24`), `UI/StepPanel.cs:627-637`.
- Symptom (from the numbers): a table is about 0.45 m below seated eyes, so the ghost hangs 10–25 cm above it; the 0.52 m window pulled to 24° has its inner edge about 5° from the object's centre line, behind the ghost, with the toolbar in the hand corridor.
- Smallest fix: 0.45 m below in both places; `MaxSideDeg` 32.
- Verify: AGQ `place` `source`, `table_y`, `head_m`; a capture with ghost and window.

**U3. [R12] An opaque 10 cm grey cube is in the shipped scene**
- File: `Scenes/Main.unity:601` (`TestCube`, active), created by `Editor/SetupProject.cs:220-224`; nothing destroys it.
- Smallest fix: delete it from the scene and from SetupProject, or `Destroy(GameObject.Find("TestCube"))` in `AppRoot.Awake`.
- Verify: look 1 m ahead of the start point at 1.4 m height.

**U4. [R12 item 14, 11] Greek capitals keep the final sigma, and long lines are cut**
- File: `Guide/GuideVisuals.cs:326-334` (`UpperNoTonos`); `UI/CoachFlow.cs:306` (the sentence goes in the auto-sized title slot).
- Symptom: "ΤΕΛΟς", "ΛΑΘΟς", "ΑΥΤΟς" in the captures; the turn instruction loses its end with no ellipsis.
- Smallest fix: `.Replace('ς', 'Σ')` after `ToUpperInvariant()`; put the wrong reason in the body line, not the caption; allow the title three lines at its minimum size.
- Verify: re-shoot `unity-wrong.png`, `unity-complete.png`, `unity-turn.png`.

**U5. [R5, R4] State that is never reset**
- File: `Guide/GhostRenderer.cs:107` against `:142,161,165` (rim stays green or amber after the first feedback); `UI/CoachFlow.cs:244-248` (wrong outline and ✕ stay after `right_part`); `:242, 286-290` (`AdvanceLater(650)` has no guard: a duplicate `step_complete` or Next inside 650 ms skips a step); `:384-388` (Back at the first action jumps to the last action of step 1); `:402` (the completion card's only button reads "Επόμενο ›" and ends the flow with nothing visible).
- Smallest fix: `_ghostMat.SetColor("_RimColor", Tokens.Accent)` in `ShowAction`; `ShowFeedback(None)` on `right_part`; capture `_stepIndex` before the delay and advance only if unchanged; return early on Back at 0/0; label the last button "Τέλος".
- Verify: AGQ `step` events never skip a number after one `perception event: step_complete`.

**U6. [R7] The first mic press does nothing, and coach lines queue ahead of step 1**
- File: `UI/VoiceCommands.cs:199-205` (asks permission, returns false, no message), `App/AppRoot.cs` mic handler (logs `error voice.mic` only), `UI/VoicePlayer.cs:22-36` (unbounded queue in completion order), `Net/HttpServerApi.cs:50-51` (no `lang`).
- Smallest fix: request the mic with the camera permission at start; status pill when `Start()` returns false; speak the step line with interrupt; send `lang`.
- Verify: AGQ `voice_listen` on the first press after the prompt; `voice` `{intent}`.

Also noted: the camera preview ornament is on by default (`UI/StepPanel.Ornaments.cs:34`), live video at the window's outer edge; the pinch ray is invisible until it hits the window (`App/PinchRayInput.cs:97-99`) and no `button` event exists in any device log; `more_light` showed as a raw event id when the server sent no text (`UI/CoachFlow.cs:270`); `Rescan` is not in the toolbar, so `_world.RequestRealign()` is reachable only from the keyboard. Every UI string found has both EL and EN.

## 4. What to check first on the headset (two minutes each)

### android
1. Is the first ghost see-through azure with brighter edges, not orange, opaque or magenta? AGQ `ghost_material` `kind`. If wrong, relaunch with `--es ghost_material unlit` and compare.
2. Is the blue outline flat on the real table in front of you, not floating, not behind you? AGQ `scene`, `table_found` or `table_missing` `reason`, `surface` `source: depth`, `place` `source` and `pos_m`.
3. Put the real drawer front 10 cm from the outline: does the ghost move onto it within about 10 s and then stay still? AGQ `place` `source: markerless` or `server_box`, `place_candidate` `applied`, `size_ok`.
4. Looking at the ghost, can you read the step window without turning your head more than about 30°, and is the instruction complete? AGQ `panels` `step_m`, `window`.
5. Pinch Επόμενο, then hold Μίλα and say "επόμενο": tick sound, Greek speech not cut off? AGQ `step` `by: button`, `mic`, `voice_cmd` `intent: next`, `step` `by: voice`, `voice`.

### unity
1. Is anything opaque or magenta: a grey cube about 1 m from where you started, the window, the ghost, the outline lines in both eyes? No event; `start` confirms the build.
2. Is the first card straight ahead, about 0.8 m away and a little below eye level, not far right or low? No pose event; `coach` `stage` shows progress.
3. How long does «Κοίτα το σημάδι» stay, and does the ghost then lie on the table with an outline, clear of the window? AGQ `coach stage: Register` → `Guide` gap, `scene` `{state, tables}`, `place` `source: mruk_table | head_fallback | server_box`.
4. Can you see a ray and press Επόμενο with a pinch? AGQ `button` `{id}`, `step` `by: button`.
5. Press Μίλα twice (prompt, then record) and say "επόμενο"; do capitals read "ΤΕΛΟΣ / ΛΑΘΟΣ"? AGQ `voice_listen`, `voice` `{intent}`, `error where: voice.mic`, `hands` `{hz}`, `frame` `fps` ≥ 72.

## 5. Which app to demo

Android, with low confidence. The totals are level (39.8 vs 39.0), so the choice rests on the no-marker flow: Android is the only app with an on-device front-panel detector wired into the guide (two trusted sample frames within 3.7 mm in the judge's run), it gets a table height from the depth probe without a room scan, and its older build held 81–89 fps on the headset. Unity has the better-evidenced look (eight editor captures, camera and socket seen on device) but registers only by table or head placement plus the server's box, still asks for a marker for 12 s, and ran at 69–72 fps. The single result that would change this: the Android round-2 build on the headset not showing a translucent azure ghost lying on the real table in front of the user (AGQ `ghost_material`, `place`), even with `--es ghost_material unlit`. In that case demo Unity after U1–U3.
