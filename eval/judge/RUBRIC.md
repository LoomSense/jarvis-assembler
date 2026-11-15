# Judge rubric: Quest 3 assembly guidance (WebXR baseline, Unity, Android/Kotlin)

Version 1 · Sun 15 Nov 2026 · used by `eval/judge/judge.md` every round.

The same rubric scores every client: the WebXR baseline (`client/src/xr/`), the Unity app (`quest-unity/`) and the Android/Kotlin app (`quest-android/`). The server (`server/`, `pipeline/`) is shared and is not scored on its own. A client earns credit only for what **it** does with the server: sends a usable frame, shows the answer, acts on it.

## 1. The ideal, and what is scored

The ideal product: the headset camera sees the object or its box → the product is identified → the guide is downloaded or built on the server → steps → **real-time 6-DoF tracking of the physical assembly** (markerless, every part, 60 to 90 Hz, a few mm) → which step the assembly is at → find the parts (highlight the right part in the pile) → instructions drawn on the real object (ghost at its final place, insertion and turning arrows, labels with codes, no text walls) → hands and intent (warn before the mistake) → voice in Greek and English, as a conversation → zero setup, no buttons → comfort (no head-locked UI, 72 fps or more).

## 2. Criteria and weights (sum 100)

| ID | Criterion | Weight | Kind |
|---|---|---|---|
| R1 | Identify the product from the headset camera | 7 | partly device |
| R2 | Guide load and transform correctness | 7 | partly device |
| R3 | Real-time 6-DoF tracking of the physical assembly | **20** | device only |
| R4 | Step and state detection | 10 | partly device |
| R5 | On-object guidance quality | 12 | device only |
| R6 | Hands and intent (warn before the mistake) | 8 | device only |
| R7 | Voice and conversation (EN/EL) | 7 | device only |
| R8 | Zero-setup flow | 8 | device only |
| R9 | Performance and comfort | 8 | device only |
| R10 | Engineering: build, tests, logs, replay | 7 | offline |
| R11 | Find the parts in the pile | 6 | device only |

R1 to R10 follow the suggested numbering; R11 was added because "find parts" is in the ideal and in no other row.

**Weighted total** = Σ (score / 5 × weight), one decimal. Maximum 100.

## 3. Evidence levels and caps

Every score names its evidence. Evidence counts only if it was produced from the commit being scored (log `start.build` or the APK/build hash must match).

| Level | What it is | Example |
|---|---|---|
| **E0 claim** | Text in a README, comment, commit message or chat. | "tracking works" |
| **E1 code** | The feature exists in code the judge read, wired into the app's main flow (not dead code, not behind a dev-only flag). | `register.ts` called from `xrMain.ts` |
| **E2 offline test** | A build, unit test, replay or emulator run the judge ran this round, with output saved under `eval/judge/round-N/`. | `score_track.py` on a recorded clip, `gradle test`, Unity EditMode tests, `npm run typecheck` |
| **E3 device log** | An AGQ log from a real Quest 3 (log `start.device` says Quest 3, `start.build` matches), pulled with `tools/quest/` and summarized by `summarize.py`. | `track` events at 30 Hz with `state: tracked` |
| **E4 device + visual** | E3 plus a headset capture (video or screenshots) of the same run that shows the claimed behaviour. | ghost stays on the drawer while it is turned |

**Caps.**
- **Device-only criteria** (R3, R5, R6, R7, R8, R9, R11): without E3 the score is **at most 2**, whatever the code does. Mark the score `2 (cap: unverified)` when the cap bites.
- **Partly-device criteria** (R1, R2, R4): without E3 the score is **at most 3**; server-side or replay numbers can carry it to 3, not further.
- **Anchor 5 on any device-only criterion** needs E4.
- **E0 alone scores 0.** A claim with no code is nothing.
- **Known device failure beats code.** If E3 shows the feature fails on the device (for example 0 cameras), score what the device actually does, even if the code is good.
- **Mocks, simulators and `sim:true` answers** are E2 at best and never count as device evidence.

**Unverified.** A criterion is `unverified` when its best evidence is E1 or E2 and the criterion is device only. Write it in the scorecard; it is the first thing to fix in the next round.

## 4. AGQ log fields the judge relies on

Logs are JSON lines, one object per line, with at least `t` (ms since start, monotonic), `ev` (event name) and the fields below. `docs/QUEST_TESTING.md` is the authority once it exists; if its names differ, map them in the round notes and keep the meaning.

| `ev` | Fields used by the judge |
|---|---|
| `start` | `app` (`webxr`, `unity`, `android`), `build` (commit or APK hash), `device`, `os`, `camera_mode` (`live` or `replay`) |
| `job_loaded` | `job`, `ms` (request to first guidance-ready frame), `parts`, `steps`, `max_err_mm` (largest difference between a manifest `target_m` and the loaded node, if the app checks it) |
| `identify` | `ms`, `ok`, `product`, `job`, `confidence` |
| `locate` | `ms`, `next_step`, `truth_step` (replay only), `confidence` |
| `track` | `state` (`tracked`, `lost`, `predicted`), `method` (`marker`, `model`, `touch`, `anchor`), `pos` [m], `rot` [quat], `reproj_px`, `ms` (latency) |
| `perception` | `seq`, `event`, `step`, `latency_ms` |
| `step` | `n`, `action`, `by` (`button`, `dwell`, `voice`, `perception`, `locate`) |
| `frame` | `fps` or `dt_ms` per frame (or a 1 s summary with `p5`, `p50`, `count`) |
| `error` | `where`, `code`, `message` |

## 5. Criteria with anchors

Scores are whole numbers 0 to 5. Read the anchors from 5 down; give the highest one whose every condition is met.

### R1 Identify the product (7)

Pipeline: headset camera frame → `POST /api/identify` → job opened (or `catalog/open` and build).

| Score | Observable condition |
|---|---|
| 0 | No identify call from the app. |
| 1 | Identify is in code but the device cannot supply a frame (no camera, denied, black frames), or it only runs in a mock. |
| 2 | Identify works from a replay or desktop camera (E2): correct product on at least 4 of 5 test frames (`eval/judge/frames/identify/`, or the MALM guide page and KNAGGLIG render). |
| 3 | E2 numbers from a frame captured by the app's own camera path (replay of a Quest frame): ≥ 4 of 5 correct, p50 under 5 s. |
| 4 | E3: on device, `identify.ok` on ≥ 4 of 5 tries with the real box or object, p50 under 4 s from trigger to job open, no button press needed to trigger (gaze or auto-capture). |
| 5 | E4: as 4, ≥ 9 of 10, p50 under 3 s, and an unknown product leads to a guide fetch or build without the user typing anything. |

### R2 Guide load and transform correctness (7)

Manifest + GLB → parts at their final transforms (end of the `walkthrough` clip), Blender Z-up to Y-up (and Unity left-handed) handled.

| Score | Observable condition |
|---|---|
| 0 | Guide does not load. |
| 1 | Loads, but parts are wrong (hidden 1e-4 scale left on, wrong axes, mirrored). |
| 2 | Loads and looks right in a preview or editor (E1/E2 screenshot), no automated check. |
| 3 | An automated test (E2) compares every placed node with the manifest (`target_m` after axis conversion, and the walkthrough end pose): max error ≤ 1 mm for `malm-drawer` and `knagglig`; or E3 load time measured. |
| 4 | E3: `job_loaded.ms` ≤ 5 s on device for `malm-drawer` over the demo host, plus the 3 test. |
| 5 | As 4 for all three test jobs (`malm-drawer`, `knagglig`, `lack`), and a guide built during the session loads without restarting the app. |

### R3 Real-time 6-DoF tracking (20)

The ghost must stay on the real object when it is moved or turned. Markerless counts more than a marker; a marker counts more than a touch.

Metrics (from `track` events and `score_track.py` on replay with known poses):
- **tracked fraction**: `state == tracked` events / all `track` events during guidance
- **rate**: `track` events per second while tracked
- **error**: position mm / rotation deg vs ground truth (replay) or vs a measured reference (device: tape-measured move)
- **jitter**: standard deviation of `pos` over 3 s with the object still, mm
- **recovery**: time from occlusion end or object move to `tracked` again

| Score | Observable condition |
|---|---|
| 0 | No registration: the model floats at a fixed or hand-dragged place. |
| 1 | Manual registration (two-corner touch, drag) plus anchors. The ghost does not follow a moved object; the user must register again. |
| 2 | Automatic pose from the camera exists and passes replay (E2): `score_track.py` error ≤ 15 mm / 5°, or level 1 with device evidence that the touch registration lands within 10 mm. |
| 3 | E3, **marker** tracking: rate ≥ 10 Hz, tracked fraction ≥ 70 %, jitter ≤ 3 mm, error ≤ 10 mm / 3°, ghost follows a 20 cm move and a 90° turn within 1 s. |
| 4 | E3, marker at ≥ 30 Hz with tracked ≥ 90 %, error ≤ 5 mm / 2°, jitter ≤ 1.5 mm, recovery ≤ 1 s; **or** markerless meeting the level-3 numbers. |
| 5 | E4, **markerless** (model-based on the GLB of the parts placed so far): ≥ 60 Hz pose (camera rate plus prediction is fine if logged), tracked ≥ 90 %, error ≤ 5 mm / 2°, every placed part checked against its pose, survives the step's turns. |

### R4 Step and state detection (10)

Which step the assembly is at (`locate`), and when a step is complete (`step_complete` over the perception socket), from frames the client sends.

Metrics: accuracy vs labels (`eval/phase2/p2.py score`, `jobs/_verify/bench_sc.py`), **false completes** (step_complete on a frame where the step is not done), detection delay.

| Score | Observable condition |
|---|---|
| 0 | No step detection; only buttons. |
| 1 | Socket or locate wired in code, but the client cannot send frames on the device, or no measurement. |
| 2 | Server-side accuracy measured on clean renders ≥ 80 % with 0 false completes, but no frames from this client's camera path. |
| 3 | E2 replay through the client's own frame path (its JPEG encoder and socket code): ≥ 85 % accuracy, 0 false completes, `locate.next_step == truth_step` on ≥ 5 of 7 MALM frames. |
| 4 | E3: on device with a real drawer, ≥ 85 % on the labelled clips (`p2.py score` G4 gates pass), 0 false completes, step_complete within 4 s of the step being done. |
| 5 | E4: ≥ 95 %, 0 false completes, weak steps (MALM 3 cam locks, 7 runner screws) both detected, delay ≤ 2.5 s. |

### R5 On-object guidance quality (12)

Ghost of the next part at its final place on the real object, insertion arrows, turning arrows, hole rings, labels with part names and hardware codes; BRAND.md §5 XR rules (ghost colours and pulse ≤ 1 Hz, no flashing > 2 Hz, text sizes ≥ 0.7°, at most 2 lines of instruction, status by shape and word, not colour alone).

| Score | Observable condition |
|---|---|
| 0 | No ghost. |
| 1 | Ghost or text only, not placed on the object, or text walls (more than 2 instruction lines, paragraphs on panels). |
| 2 | Ghost at the final place, arrows, labels with codes, BRAND rules followed in code (E1/E2), not shown on a real object. |
| 3 | E3/E4 on device: ghost within 2 cm of the real target on at least 3 actions, arrows and labels visible, BRAND rules hold. |
| 4 | E4: within 1 cm on every action of one full MALM step, insertion and turning arrows match the action type, codes shown for every hardware action, no rule breaks. |
| 5 | E4: within 5 mm throughout a full guide, the ghost stays registered while the object is moved (needs R3 ≥ 3), occlusion by real hands (depth) so the ghost does not draw over fingers. |

### R6 Hands and intent (8)

Hand tracking at the target, still, raised (`type:"hands"` to the socket), wrong-place warning before the part is put down.

| Score | Observable condition |
|---|---|
| 0 | No hand tracking. |
| 1 | Hands rendered or used for pinching only. |
| 2 | Hand checks in code (at target, still, wrong place) and hand summaries sent (E1/E2). |
| 3 | E3: `hands` messages at ~10 Hz in the log, `hand_at_target` true when a hand is at the ghost on ≥ 4 of 5 tries, a wrong-place warning fires on ≥ 3 of 5 staged mistakes. |
| 4 | E4: warning **before** the part is down on ≥ 4 of 5 staged mistakes (wrong hole, wrong board), ≤ 1 false warning per step, combined with the camera's held-part answer. |
| 5 | E4: as 4 plus wrong hardware in hand and wrong orientation warned before placement, ≤ 1 false warning per full guide. |

### R7 Voice and conversation (7)

| Score | Observable condition |
|---|---|
| 0 | Silent. |
| 1 | Speaks fixed lines (TTS) in one language only. |
| 2 | TTS in EN and EL (Gemini TTS via `/api/tts`, fallback voice), instructions and perception events spoken; no voice input, or input only in code. |
| 3 | E3: speech heard on device in both languages; voice commands (next, back, repeat, where am I) work on ≥ 4 of 5 tries. |
| 4 | E3: a conversation: free questions answered (Gemini Live or `/api/jobs/{id}/ask`), grounded in the current step, in the user's language, p50 reply onset ≤ 2 s. |
| 5 | E4: as 4, barge-in (interrupt stops speech), perception events injected into the conversation without talking over the user, session survives 15 min. |

### R8 Zero-setup flow (8)

Count from app launch (headset on, app icon or URL already open) to the first ghost on the real object: taps/pinches, permission prompts, typed text, seconds.

| Score | Observable condition |
|---|---|
| 0 | Needs a laptop, a typed URL with parameters, or a manual job id each session. |
| 1 | Works only with a pre-set job or URL parameters; or the identify path is broken on device so the job must be chosen by hand. |
| 2 | Flow exists end to end in code (scan → identify → guide → locate → register → guidance), ≥ 5 user actions, not timed on device. |
| 3 | E3: ≤ 5 user actions and ≤ 90 s to the first ghost, permissions asked once (first run only). |
| 4 | E3: ≤ 2 user actions (launch plus at most one confirm) and ≤ 45 s; Next not needed (step advance by perception or dwell on ≥ 80 % of actions). |
| 5 | E4: zero taps after launch: look at the box, the guide comes up on the object, steps advance by themselves; ≤ 30 s. |

### R9 Performance and comfort (8)

| Score | Observable condition |
|---|---|
| 0 | Crashes or stalls in the main flow. |
| 1 | Head-locked UI, or flashing above 2 Hz, or fps known to be below 60. |
| 2 | World-anchored UI and BRAND motion rules in code; no fps measured on device. |
| 3 | E3: fps p50 ≥ 72 and p5 ≥ 60 over a 5-minute guided session, no head-locked UI, no frame stalls > 100 ms more than once a minute. |
| 4 | E3: p5 ≥ 72 (no dropped frames under load: camera, tracking and socket running), memory flat over 15 minutes. |
| 5 | E4: 90 Hz display mode with p5 ≥ 88, 30 minutes without thermal throttling (fps stays, or the logged thermal state stays normal), panels within 0.6 to 0.9 m and below eye level. |

### R10 Engineering (7)

| Score | Observable condition |
|---|---|
| 0 | Does not build from a clean checkout. |
| 1 | Builds with manual steps not written down. |
| 2 | Builds from documented commands (typecheck or compile passes), some logging, no tests of the app's own logic. |
| 3 | Clean build reproduced by the judge, unit tests for the core math (axis conversion, registration, pose filter) pass, AGQ logging of the §4 events. |
| 4 | As 3 plus a **replay camera mode** (recorded frames in, same code path as the live camera) that `score_track.py` and the step-detection replay run headless; no secrets in the app or in git (`make secrets` clean). |
| 5 | As 4 plus one command builds, installs and runs a replay check (CI-like), and the HUD shows tracking state, fps and last server latency on device. |

### R11 Find the parts in the pile (6)

| Score | Observable condition |
|---|---|
| 0 | Nothing about which part to take. |
| 1 | Name or code of the next part shown (text, or a 3D copy on a card), nothing in the real pile. |
| 2 | Uses the camera to say whether the held part is right (`part_in_hand`), shown on the ghost or by the hand (E1/E2). |
| 3 | E3: the held-part answer right on ≥ 4 of 5 tries on device; or the inventory photo (`/api/jobs/{id}/inventory`) used to list what is missing. |
| 4 | E4: the right part is **highlighted in the pile** (a world-space marker on the real part) on ≥ 3 of 5 tries. |
| 5 | E4: highlighted on ≥ 9 of 10, including look-alike hardware, and it updates as parts are taken. |

## 6. Scoring rules of thumb

- Strict, not generous: when a condition is not shown, it is not met.
- One number per criterion, whole points. Write the evidence level next to it (`3 · E3`).
- Never raise a score for effort or code volume; raise it for measured behaviour.
- A regression from the previous round is noted with the old score.

## Weights v2 (with R12, from Sun 11:00)

R12 *Visual & interaction quality (Apple-grade)* is added; anchors, evidence caps and checklist in `RUBRIC-R12.md` and `docs/design/XR_NATIVE_SPEC.md` §8. Weights now: R1 6 · R2 6 · R3 16 · R4 9 · R5 10 · R6 8 · R7 7 · R8 8 · R9 8 · R10 5 · R11 5 · R12 12 (sum 100). This supersedes the weights above.
