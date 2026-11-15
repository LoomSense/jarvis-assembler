# Handoff: where the project stands

Status as of **Sun 15 Nov 2026, 14:20 Athens**. For the team and their coding agents. Deadlines today (Athens): feature freeze 17:00, submission 17:30, code freeze 18:00. Numbers were measured today unless marked UNVERIFIED.

**In short:** the demo is the native Android app (`quest-android/`) on a Quest 3, with no printed marker, guiding the assembly of the team's real drawer from a custom guide (`jobs/drawer`). On the headset it now recognises every board on the floor by its size, locks the guide onto the real drawer front within a few millimetres, knows which way round the front lies from its groove, and moves from "take this" to "put it here" to the next action without a button press. Not yet seen on the headset: the build of 14:19 (new floor UI, installed bolts no longer offered as parts to take). Still weak: telling that a part is the wrong way round (server only), and a wrong small part in the hand.

---

## 1. Decisions made today

| Decision | Detail |
|---|---|
| Demo app | **`quest-android/`** (Kotlin, Meta Spatial SDK 0.15.0). `quest-unity/` is the backup: its round-2 build is installed on the headset but has not been opened there since the morning. The WebXR client is not the demo path. |
| No marker | The marker strip cannot be printed. Registration is markerless (§2). The marker code stays and still wins if a strip is seen. |
| Demo guide | **`drawer`**, a custom job for the real drawer on the table (it is not the product of the IKEA guide used earlier): front, two sides, bottom, back; 4 cam bolts, 4 cam locks already seated in the sides, 4 white plastic pegs. `malm-drawer` (rebuilt with tape-measured sizes) is the fallback. `jobs/` is git-ignored: the sources are in `jobs/drawer-src/` on the demo Mac (`build.py` rebuilds in about 20 s). |
| Demo flow | Launched with the guide preset (`job=drawer`, `lang=el`). Product identification is an optional extra shot. |
| Build agent model | `claude-fable-5-2` through `AGJ_AGENT_MODEL` in `.env` for the demo; the code default and this morning's timed builds are `claude-opus-5-5`. Check the event's rules on non-Gemini models before submitting. |
| Judge | Round 2 (static review): Android 39.8, Unity 39.0 (round 1: 36.0 and 23.8). `eval/judge/round-2.md`. |

---

## 2. How the Android app puts the guide on the real part (no marker)

`world/AssemblyAnchor.kt` holds the pose (WORLD ← TABLE), `world/WorldModule.kt` orchestrates, `MainActivity.frameTick` draws the ghost from `vision.worldFromAssembly()`. `track.source` and `place.source` in the AGQ log say which source is live.

| Source | What it is | Where |
|---|---|---|
| `markerless` | **the one that places the ghost**: an on-device detector of the step's flat base part (the drawer front, 400 × 200 × 18 mm from the guide), 3 Hz, x, y and yaw; three trusted results in a row before it reports | `vision/PanelPose.kt`, `vision/StepFootprint.kt` (target per step from the guide's meshes) |
| which way round | the groove and holes of the base part, from its mesh, matched against the rectified picture; turns the pose by 180° after three agreeing results | `vision/RecessCue.kt` |
| surface height | a depth ray through the server's box of the base part (Meta environment raycast), median of three, frozen while the detector trusts frames; a steady gaze probe before that. The headset has no room scan, so there is no MRUK table | `WorldModule.placeBase`, `probeSurface`, `surfaceY` |
| `server_box` | the server's box for the base part: moves the ghost only when the detector has no target for the step | `WorldModule.onBase` |
| `fixed_spot` | a guess in front of the head until something better arrives | `world/AssemblyAnchor.kt` |
| marker, `manual` | printed strip (unused), `world.nudge` (no UI caller) | `vision/MarkerTracker.kt` |

Settings that came from real frames (all in `MainActivity` where `PanelPoseTracker` is built, each with a launch extra): lightness only (`panel_chroma_w` 0: the front is wood-coloured, the colour penalty of the render-tuned detector lost it), size gate 30 / 25 mm (`panel_tol_long_mm`, `panel_tol_short_mm`), closing 7 px (`panel_close_px`: seen from close, the groove cut the front's blob in two and it read 412 × 170 mm).

Other pieces on `main`:
- **Every board by its size** (`world/FlatParts.kt`, `world/FlatPartOutline.kt`): one pass finds all bright rectangles on the surface, labels them against the guide's boards (within 45 / 30 mm, clearly the best match), and outlines the one to take now.
- **Loose hardware** (`world/PartsFinder.kt`, `world/PartTracks.kt`, `vision/PartFlowTracker.kt`): the server's boxes (`/parts`, `mode: "step"`, 5–20 s per answer on the demo link), followed in the picture between answers; anything lying on the assembly is left out.
- **Two phases per action** (`ui/ActionCoach.kt`): "take" → "put it here" → done, moved by the server's verdict at the hand, the part leaving its place, a hand at the target then away, or the step's `step_complete`; Next and voice remain.
- **Verdict at the hand** (`ui/HeldVerdict.kt`, `ui/HeldPartGuide.kt`): right / wrong / unsure from the perception socket, and a manual "check this part" that sends one 1280 px frame.
- **Hands**: finger joints through the body-tracking API (`vision/Hands.kt`, `HandSetup.kt`, `Held.kt`).
- **Floor-work UI** (`ui/WindowFollow.kt`, `ui/Panels.kt`): looking down steeply, the step window sits just beyond the work facing the eyes; labels read upright in the view; one large instruction and one primary button.
- **Camera watchdog**: the camera is reopened when frames stop (taking the headset off closed it for good).

---

## 3. What the headset has shown (Android, `tools/quest/runs/*-android/`)

| Seen working | Evidence |
|---|---|
| Passthrough, live camera 1280 × 960 with a pose per frame, guide load about 2.5 s, Greek speech, 75–88 fps | `camera`, `camera_pose`, `job_loaded`, `frame` |
| Hand joints | `hands_source: skeleton, joints 70`; `held` with pinch / grip |
| Boards recognised by size, all labelled correctly | `flat_parts`, 14:11: front 404 × 200, sides 433 × 109, back 335 × 94, bottom 436 × 340 mm; the front lit when it was the part to take |
| Ghost locked on the real front | `panel … trusted: true`, size error 0–6 mm; `place … source: markerless`; headset capture at 13:50 with the side panel's ghost standing on the real front |
| Which way round | `panel.cue`: 28–29 for the chosen orientation against 0.5–2 for the turned one, decided on every trusted frame |
| Actions advance without Next | `coach_phase`, 14:13–14:15: four cam bolts and the first side, by `right_part`, `part_moved`, `placed`, `step_complete` |
| Right part in the hand | `held_verdict right` for the front, a cam bolt and a side (p 0.76–0.94) |

| Problem seen on the headset | State |
|---|---|
| Pick marker on bolts already screwed in | Fixed in the 14:19 build, not yet seen |
| Server box put the ghost on another board for 30 s before the detector locked | Fixed in the 14:19 build, not yet seen |
| Re-align at step 3 lifted the ghost 22 cm | Fixed in the 14:19 build, not yet seen |
| Step window out of view and labels sideways when looking down at the floor | New placement in the 14:19 build, not yet seen |
| A wrong board picked up is not caught; coaching is slow and says little | In progress: on-device grab check against the recognised boards, praise and "next" lines, speech prefetched (`worktree-agent-af370f73aa0b0685a`) |
| Wrong way round for sides, bottom, back | Server only (`flip_needed` from the step's `orientation_ok` question); measured for the front only, where it will rarely fire |
| `/parts` answers take 5–20 s | Unchanged; the on-device recognition covers the boards |
| Launch blocked by a system dialog while the headset is not worn | The user dismisses it; the launch is cached and proceeds |

Not tried on the headset at all: steps 4–6 of `drawer`, a wrong small part in the hand, the finish.

---

## 4. Build, install, run, read the log (Android)

```sh
cd quest-android && ./gradlew test assembleDebug && cd ..      # 286 JVM tests on main
tools/quest/install.sh android                                 # installs the debug APK, grants permissions
tools/quest/run.sh android --job drawer --lang el --secs 600 --keep-running [--dump]
#   --dump records camera frames with poses on the headset; tools/markerless/pull.sh fetches them,
#   .venv/bin/python tools/markerless/eval_real.py <run>/dump runs the reference detector on them
#   --hud adds the debug panel (it shows the server address: never record with it)
#   --no-launch attaches to a running app; the run folder is tools/quest/runs/<time>-android/
```

Events to read in `agq.jsonl`: `flat_parts` (`parts[]` sizes, `needed`, `lit`, `unknown[]`), `panel` (`trusted`, `reason`, `size_err_mm`, `cue`), `place` (`source`, `reason`), `surface`, `coach_phase` (`phase`, `why`), `held_verdict`, `parts`, `camera_restart`. A headset view without wearing it: `scrcpy --no-window --no-audio --no-playback --record f.mp4 --time-limit 4`, then crop the left eye with ffmpeg (`adb exec-out screencap` returns an empty image on Quest); restart the log capture afterwards, scrcpy can restart adb.

Practical: boards on the floor need a gap of 3 cm or more (two touching boards read as one); a dark floor under light boards; keep the headset on a wall charger when it is not worn.

---

## 5. Server

Hosted from a Mac with `deploy/mac_host.sh`; the public URL is in `jobs/_host/url.txt` and every route needs `?key=<DEMO_KEY>` or the `X-Demo-Key` header. 331 tests pass. Killing the uvicorn process restarts it behind the same tunnel in a few seconds (`.env` is read at start).

Added today (details and examples in `server/perception/README.md`):
- `POST /api/perception/{job}/parts` with `"mode": "step"`: every item a step needs, every instance, counts.
- `POST /api/perception/{job}/panel`: the front-panel detector for clients without OpenCV.
- Step-complete with a close-up reference for hardware the reference sheet hides (`python -m pipeline.perception.details jobs/<job>`): on 55 MALM render pairs 46 → 50 correct, false "done" 1 → 0.
- Tuning from real headset frames (`PERCEPTION_TUNING`): `poor_at` 0.55 (was 0.3; 77 % of answered headset frames had been discarded, now 33 %), `more_light` only when the discarded frames are dim, `show_part` names a code only on clear evidence.
- `perception_note` in a manifest: one sentence on the real colours, given to the model (white reference renders made it take the grey bottom board for the wood-coloured front).
- `PERCEPTION_KEEP_FRAMES=<count>`: keeps real frames of a session under `jobs/_host/frames/` (git-ignored; set to 600 in the demo `.env`).
- A job folder is a job only when it has `guide.pdf`.

Pipeline fix today: every placed piece is now visible in its per-step clip (`pipeline/blender/assemble.py`); before, each screw after the first stayed hidden in the web preview. Open: `pipeline/blender/render_parts.py` ignores a part's `color`.

### Guide data conventions (for any client)
- GLB: metres, Y-up. Root `assembly`; parts `part_<id>_<n>`, hardware `hw_<code>_<n>` (codes are strings, may contain a hyphen). **Parts rest hidden (scale 1e-4)**; final transforms exist only at the end of the `walkthrough` clip.
- Manifest `target_m`, `from_dir`, `assemblyRotationDeg` are in Blender's Z-up frame: `(x, y, z)_blender → (x, z, −y)` for glTF. `from_dir` points from the target towards where the part comes from.

---

## 6. Submission pack

- `docs/SUBMISSION.md`, `docs/DEMO_VIDEO.md`: written at 13:00 for the Android demo without a marker; they still describe the earlier guide and say tracking is unconfirmed on the headset. To update before submitting: the custom guide, the device evidence of §3, and the open items in their "Before submitting" block (team names, the form's track and video length, the model rule).
- Rough cuts with an English voice-over, outside git: `jobs/_media/cut/demo-rough.mp4` (2:47) and `demo-60s.mp4` (1:00). Five headset shots are placeholders; drop recordings as `jobs/_media/cut/headset/H1.mp4` … `H5.mp4` and run `./build.sh` there.

## 7. Backup app (Unity)

`quest-unity/` (Unity 6.3 LTS, Meta XR SDK 209): passthrough fixed on the device this morning; round 2 added the UI, voice, camera preview and a world layer with the same server calls. Known defects: `eval/judge/round-2.md`.
