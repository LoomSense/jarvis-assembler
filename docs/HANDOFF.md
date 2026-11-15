# Handoff: where the project stands

Status as of **Sun 15 Nov 2026, 16:10 Athens**. For the team and their coding agents. Deadlines today (Athens): feature freeze 17:00, submission 17:30, code freeze 18:00. Every number below was read from today's headset logs (`tools/quest/runs/<run>/agq.jsonl`) or from the file named next to it, unless marked UNVERIFIED.

**In short:** the demo is the native Android app (`quest-android/`) on a Quest 3, with no printed marker, guiding the assembly of the team's real drawer from a custom guide (`jobs/drawer`, 6 steps, 17 actions, Greek). Since 14:20 the headset has shown: steps 2 to 6 advancing without the Next button, as far as the first white peg (14:58 run); each of the four cam bolts confirmed aloud, a white peg reported as the wrong hardware, and the boards on the floor read by size with the face that is up (15:21 session); the new line "Λάθος κομμάτι. Πάρε τη βίδα έκκεντρου." and step 1 ending by itself when the front already lies in place (15:55 run, the 15:54 build). Not seen on the headset: the on-device wrong-board judge (no `grab` event in any log), the on-device answer to "πού είναι η βίδα;" (no `where_is` event), the four pegs, the finish. Not reliable: whether a side panel is the right way round, a cam bolt against a peg in the hand, board recognition in direct sunlight.

---

## 1. Decisions made today

| Decision | Detail |
|---|---|
| Demo app | **`quest-android/`** (Kotlin, Meta Spatial SDK 0.15.0). `quest-unity/` is the backup: its round-2 build is installed on the headset but has not been opened there since the morning. The WebXR client is not the demo path. |
| No marker | The marker strip cannot be printed. Registration is markerless (§2). The marker code stays and still wins if a strip is seen. |
| Demo guide | **`drawer`**, a custom job for the real drawer on the table (it is not the product of the IKEA guide used earlier): front, two sides, bottom, back; 4 cam bolts, 4 cam locks already seated in the sides, 4 white plastic pegs. `malm-drawer` (rebuilt with tape-measured sizes) is the fallback. `jobs/` is git-ignored: the sources are in `jobs/drawer-src/` on the demo Mac (`build.py` rebuilds in about 20 s). |
| Demo flow | Launched with the guide preset (`job=drawer`, `lang=el`). Product identification is an optional extra shot. |
| Video | The headset shots are recorded with the headset's own recorder (Meta button, Camera, Record video). The headset cannot be screen-recorded from the Mac while it is worn. `docs/DEMO_VIDEO.md`. |
| `right_way` | The server's "right way round" praise is switched off for the demo with `PERCEPTION_TUNING`: it is not reliable (§5). |
| Build agent model | `claude-fable-5-2` through `AGJ_AGENT_MODEL` in `.env` for the demo; the code default and this morning's timed builds are `claude-opus-5-5`. Check the event's rules on non-Gemini models before submitting. |
| Judge | Round 2 (static review): Android 39.8, Unity 39.0 (round 1: 36.0 and 23.8). `eval/judge/round-2.md`. |

---

## 2. How the Android app puts the guide on the real part (no marker)

`world/AssemblyAnchor.kt` holds the pose (WORLD ← TABLE), `world/WorldModule.kt` orchestrates, `MainActivity.frameTick` draws the ghost from `vision.worldFromAssembly()`. `track.source` and `place.source` in the AGQ log say which source is live.

| Source | What it is | Where |
|---|---|---|
| `markerless` | **the one that places the ghost**: an on-device detector of the step's flat base part (the drawer front, 400 × 200 × 18 mm from the guide), 3 Hz, x, y and yaw; three trusted results in a row before it reports | `vision/PanelPose.kt`, `vision/StepFootprint.kt` (target per step from the guide's meshes) |
| which way round | the groove and holes of the base part, from its mesh, matched against the rectified picture; turns the pose by 180° after three agreeing results | `vision/RecessCue.kt` |
| surface height | a depth ray through the server's box of the base part (Meta environment raycast), median of three, frozen while the detector trusts frames; a steady gaze probe before that. The headset has no room scan, so there is no MRUK table. New at 16:00 (not yet seen on the headset): with no measured surface 3 s after the guide loads, the floor (y = 0) is tried | `WorldModule.placeBase`, `probeSurface`, `surfaceY` |
| `server_box` | the server's box for the base part: moves the ghost only when the detector has no target for the step | `WorldModule.onBase` |
| `fixed_spot` | a guess in front of the head until something better arrives | `world/AssemblyAnchor.kt` |
| marker, `manual` | printed strip (unused), `world.nudge` (no UI caller) | `vision/MarkerTracker.kt` |

Settings that came from real frames (all in `MainActivity` where `PanelPoseTracker` is built, each with a launch extra): lightness only (`panel_chroma_w` 0: the front is wood-coloured, the colour penalty of the render-tuned detector lost it), size gate 30 / 25 mm (`panel_tol_long_mm`, `panel_tol_short_mm`), closing 7 px (`panel_close_px`: seen from close, the groove cut the front's blob in two and it read 412 × 170 mm).

Other pieces on `main`:
- **Every board by its size** (`world/FlatParts.kt`, `world/FlatPartOutline.kt`): one pass finds all bright rectangles on the surface, labels them against the guide's boards (within 45 / 30 mm, clearly the best match), and outlines the one to take now.
- **Loose hardware** (`world/PartsFinder.kt`, `world/PartTracks.kt`, `vision/PartFlowTracker.kt`): the server's boxes (`/parts`, `mode: "step"`), followed in the picture between answers; anything lying on the assembly is left out.
- **Two phases per action** (`ui/ActionCoach.kt`): "take" → "put it here" → done, moved by the server's verdict at the hand, the part leaving its place, a hand at the target then away, or the step's `step_complete`; Next and voice remain.
- **Verdict at the hand** (`ui/HeldVerdict.kt`, `ui/HeldPartGuide.kt`): right / wrong / unsure from the perception socket, and a manual "check this part" that sends one 1280 px frame.
- **Hands**: finger joints through the body-tracking API (`vision/Hands.kt`, `HandSetup.kt`, `Held.kt`).
- **Floor-work UI** (`ui/WindowFollow.kt`, `ui/Panels.kt`): looking down steeply, the step window sits just beyond the work facing the eyes; labels read upright in the view; one large instruction and one primary button.
- **Camera watchdog**: the camera is reopened when frames stop (taking the headset off closed it for good).

New on `main` since 14:20:
- **Coach voice** (`ui/LiveLines.kt`): one spoken line praises and names the next action ("Μπράβο! Πάμε στην επόμενη, 2 από 4. Πάρε: Βίδα έκκεντρου."), Greek and English, fetched in advance. Seen on the headset: `voice.ms` reads 22 to 84 for these lines, against 2,020 for a first line that was not fetched in advance (14:58:39).
- **Which face of each board is up** (`vision/BoardCue.kt`): rail, groove or holes, scored in the rectangle pass, shown on the pick label and spoken with the take prompt. Left side against right side is left undecided on purpose: the real frames do not prove it. Seen in the log (`flat_parts.parts[].cue`), §3.
- **Which board the hand lifted** (`ui/GrabJudge.kt`): judged on the device against the recognised boards, logs `grab` and feeds the same right / wrong path as the server's verdict. **Not seen on the headset**: no `grab` event in any log.
- **"Λάθος κομμάτι. Πάρε …"** for any wrong item, board or hardware, with the name of the item to take. Seen once, §3.
- **Where each part was last seen** (`world/WhereIs.kt`): the take prompt adds "Την είδα τελευταία μπροστά αριστερά σου, κοντά σου." when the part is not in view, and "πού είναι η βίδα;" is answered on the device (`where_is` event). The added sentence was spoken on the headset; the answer to the question has **not been seen** there.
- **Hardware already seated has no "take" phase** (manifest `hardware[].preinstalled`: the four cam locks). A tool-turned fastener counts the hand at a screwdriver's length.
- **Colours**: "put it here" cues are green (ghost, path, hole ring, arrow, floor outline), "take this" stays azure. A part found far from the wearer gets a "bring it closer" hint.
- **Rules changed after each headset run**: a board held at its place waits 7 s before "placed" (not at all after a wrong verdict); a grip left over from the last bolt does not take the next one; server answers that come and go do not end "take" for hardware; no praise for a cam lock while its instruction is read; a wrong verdict restarts the placing; a front that already lies in place ends step 1 by itself; the next action's parts are asked for at once.

---

## 3. What the headset has shown (Android, `tools/quest/runs/*-android/`)

Runs since 14:20: `20261115-145829-android` (14:58 to 15:07 on the head), `20261115-152019-android` (three sessions, each starts with a `start` event: 15:21, 15:34, 15:43) and `20261115-155506-android` (two sessions: 15:55:34 to 16:00:28, the 15:54 build; and from 16:00:43, read at 16:09 while it was still running). The earlier rows are from the runs of 14:07 to 14:38.

| Seen working | Evidence (run, time, event) |
|---|---|
| Steps 2 to 6 advance without the Next button | `-145829-`, 14:58:36 to 15:02:47: 13 `step` events, all `by: auto`; 12 actions ended (`coach_phase … done`: 11 `placed`, 1 `step_complete`). It stopped at step 6, action 1, the first white peg |
| Each board confirmed in the hand | `-145829-`, `held_verdict right`: cam bolt 0.80 (14:58:59), left side 0.965 (15:00:45), right side 0.966 (15:01:24), bottom 0.977 (15:02:13), back 0.937 (15:02:44) |
| All four cam bolts confirmed aloud | `-152019-`, `perception … right_hardware` at 15:22:55, 15:23:14, 15:23:33 and 15:24:10 (p 0.944, 0.945, 0.968, 0.877), each spoken as "Σωστή βίδα! Βίδωσέ τη στην τρύπα που φωτίζεται." In `-145829-` only the first bolt was confirmed; in `-155506-` three of the four (15:56:56, 15:57:12, 15:57:50) |
| A wrong item in the hand is reported | `held_verdict wrong`, expected `BOLT-C`, seen `PEG-W`: `-152019-` 15:23:49 (p 0.926, 4 of the last 5 frames; by the team's account the wearer held up a white peg), `-155506-` 15:57:29 (p 0.956) and 16:08:21 (p 0.912); for the last two the log does not say what was in the hand |
| "Λάθος κομμάτι. Πάρε τη βίδα έκκεντρου." | `-155506-`, `voice` at 15:57:29, in the same second as the verdict. At 15:23:49 the older sentence was spoken, 4.4 s late |
| Boards on the floor by size | `-152019-`, `flat_parts`, 15:21:19 to 15:25:00: 132 of 271 passes label a board, 56 passes label three. Bottom 427–442 × 339–348 mm (guide 447 × 350), sides 429–475 × 104–112 (450 × 110), back 328–338 × 94–104 (334 × 110), front 410–413 × 208–210 (400 × 200, 8 readings) |
| The face that is up | `-152019-`, `flat_parts.parts[].cue`: the side panels read as groove side up in 195 of 197 readings, the front in 8 of 8, the back never decided (0 of 64). Left against right is not decided. No logged take prompt speaks the face, and the log cannot show the label |
| The board to take is lit | `flat_parts.lit`: the left side at step 3 in 12 passes of `-152019-`, 3 of `-145829-`, 6 of `-155506-` |
| Step 1 ends by itself when the front already lies in place | `-155506-`, 15:56:35: `coach_phase … done`, `why: placed`, `source: device`, `how: in_place`, 7 s after step 1 was opened. Nine more times in the 16:00 session (16:01:02 to 16:07:38), 5 to 11 s after each press on Back |
| Where a part was last seen, spoken with the take prompt | `voice`: "Την είδα τελευταία …" four times in the 15:43 session and twice in `-155506-` (15:56:40, 15:57:49, with `pick_prompt … source: memory`, 0.49 and 0.30 m) |
| A cam lock is not praised while its instruction is read | `-155506-`: step 3, action 1 shown at 15:59:43 and still open at 16:00:28. In `-145829-` the same action ended after 5 s |
| Ghost locked on the real front | `place … source: markerless` at 14:58:42 (6 s after the first step), 15:21:36, 15:44:03, 15:56:24. Trusted `panel` frames: 109 (14:58 run), 76 (15:21 session), 24 (15:55 session); median size error 6.2, 4.4 and 4.7 mm, largest 27.5 mm |
| The camera comes back after the headset was off | `-152019-`, 15:34 session: 40 `camera_restart` from 15:38:09 to 15:42:52, guide locked again at 15:42:57 |
| The perception socket reconnects after a server restart | `-152019-`: closed at 15:39:05 and 15:47:59 (HTTP 502), `hello` again 3 and 4 s later |
| Earlier (still true): passthrough, live camera 1280 × 960 with a pose per frame, Greek speech, hand joints, which way round the front lies | `camera`, `camera_pose`, `voice`, `hands_source: skeleton, joints 70`, `panel.cue`; guide load 2.1 to 4.1 s in the five sessions from 14:58 to 16:00 (`job_loaded.ms`) |

| Problem seen on the headset | State |
|---|---|
| Boards on the floor not recognised in direct sunlight on the grey carpet | Open. `-145829-`: 67 of 456 passes label any board, never more than two at once, the bottom never. 15:55 session: 41 of 252 passes (the log does not say why). Works out of the sun: 15:21 session, and 248 of 340 passes in the 16:00 session, 43 of them with four boards at once |
| The on-device wrong-board judge has not fired | Open. No `grab` event in any of the three logs (read up to 16:09), although boards were lifted in the 14:58 run and the 15:21 and 15:55 sessions. Untested on the headset |
| "Πού είναι η βίδα;" answered on the device | Not seen. No `where_is` event. The three questions asked (14:59:45, 14:59:57, 15:22:42) were before that build: the server's model answered in 6.6 to 10.8 s with where the bolt goes, not where it lies |
| A cam bolt and a white peg in the hand are confused | Open, and not a matter of thresholds (§5). At step 6, where a peg is expected, the item in the hand gave "unsure" (15:06:50). One bolt of four was not confirmed in `-155506-` |
| Whether a side panel is the right way round (rail and groove side) | Not reliable from the model (measured on real frames by the perception owner), so it is not said. Only the wrong end of the front was told reliably, and that is not wired into the app. No `flip_needed` in any log |
| `/parts` answers take 3.4 to 17.1 s (median 4.8 to 6.6 s per session, 226 answers up to 16:00; the 16:00 session is in the same range) | Unchanged in effect. Panels and hardware are now asked in parallel |
| `/parts` boxes more cam bolts than exist | Open. 5 boxes for the 4 bolts in ten answers up to 16:09 (two each in the 14:58 run and the 15:21 and 15:55 sessions, four in the 16:00 session), so a white peg gets a bolt marker now and then |
| Actions end by where the hands are, not by a check that the part is seated | Partly fixed. In `-145829-` a side panel counted as placed 5 s after it was confirmed and the back after 2 s. Since the 15:13 build a board held at its place waits 7 s |
| The guide took 28 s and 42 s to lock, after presses on re-align | `-152019-` 15:21 and `-155506-` (where 27 `flat_parts` passes say "no table"). A fix was committed at 16:00 (the floor is tried after 3 s). In the session from 16:00:43 the guide locked at 16:01:00, 11 s after it started |
| Step 1 did not end with the front already on the floor; bolt markers came late | 15:43 session. Fixed in the 15:54 build: step 1 seen ending by itself (above); the markers were not timed again |
| Pick marker on bolts already screwed in; server box put the ghost on another board; re-align lifted the ghost 22 cm; step window out of view on the floor | Fixed in the 14:19 build. No repeat in the three runs since; not checked one by one |
| Launch blocked by a system dialog while the headset is not worn | The user dismisses it; the launch is cached and proceeds |

Not tried on the headset at all: the four pegs to the end (actions 2 to 4 of step 6), the finish, a board the wrong way round.

---

## 4. Build, install, run, read the log (Android)

```sh
cd quest-android && ./gradlew test assembleDebug && cd ..      # 286 JVM tests at 14:20; more were added, not counted again
tools/quest/install.sh android                                 # installs the debug APK, grants permissions
tools/quest/run.sh android --job drawer --lang el --secs 600 --keep-running [--dump]
#   --dump records camera frames with poses on the headset; tools/markerless/pull.sh fetches them,
#   .venv/bin/python tools/markerless/eval_real.py <run>/dump runs the reference detector on them
#   --hud adds the debug panel (it shows the server address: never record with it)
#   --no-launch attaches to a running app; the run folder is tools/quest/runs/<time>-android/
```

Events to read in `agq.jsonl`: `coach_phase` (`phase`, `why`, `source`, `how`), `step` (`by: auto` or `button`), `voice` (the sentence spoken), `perception` (`event`, `p`, `votes`), `held_verdict` (`verdict`, `expected`, `seen`), `flat_parts` (`parts[]` sizes and `cue`, `needed`, `lit`, `unknown[]`, `reason`), `parts` (`ms`, `found[].boxes`), `pick_markers`, `panel` (`trusted`, `size_err_mm`, `cue`), `place` (`source`, `reason`), `grab`, `where_is`, `camera_restart`. One folder can hold several sessions: split on `start`. The `start` event holds the server address: do not paste it anywhere.

A headset view without wearing it: `scrcpy --no-window --no-audio --no-playback --record f.mp4 --time-limit 4`, then crop the left eye with ffmpeg (`adb exec-out screencap` returns an empty image on Quest); restart the log capture afterwards, scrcpy can restart adb. While the headset is worn this does not work: use the headset's own recorder and `tools/quest/pull_recordings.sh`.

Practical: boards on the floor need a gap of 3 cm or more (two touching boards read as one); a dark floor under light boards, out of direct sunlight; keep the headset on a wall charger when it is not worn.

---

## 5. Server

Hosted from a Mac with `deploy/mac_host.sh`; the public URL is in `jobs/_host/url.txt` and every route needs `?key=<DEMO_KEY>` or the `X-Demo-Key` header. 331 tests passed at 14:20; more were added, not counted again. Killing the uvicorn process restarts it behind the same tunnel in a few seconds (`.env` is read at start).

New since 14:20 (measurements in `server/perception/README.md`, "Wrong part and wrong way round on the real drawer"):
- **A wrong part is recognised.** The held-part question now offers every part of a later step and every hardware code of the guide (`all_choices`); the answers for parts of other steps are summed (`wrong_part_sum_min` 0.65, `wrong_part_no` 0.3). Desktop, on 50 real kept frames: 15 vote for a wrong part and each of them shows one. Headset: the two `wrong_hardware` verdicts of §3.
- **Each cam bolt is confirmed** (`reconfirm`): the confirmation re-arms on a new action index, or after the item left the hand for 4 s. Headset: four of four at 15:22 to 15:24.
- **`wrong_way` question per step**, with a reason and a sentence for each mistake (`flip_needed` with `reason`). Desktop only: it fired on one phone photo of the front lying on its face (0.74 and 0.76 against a line of 0.7). No picture of a wrongly placed side, bottom or back exists, so those have never fired.
- **`right_way` event** (the same question's `ok`): on in the code, off for the demo. The one real frame of a side panel standing with its rail inwards scored `ok` 0.76 against a line of 0.8.
- **`/parts` step mode** asks panels and hardware in two parallel requests and sends a slow request a second time.
- **Greek sentences name the part** from the manifest (`names.el`) when the job folder has no `plan.json`.
- Tried and left off: `hardware_colour` (material and colour before the code: no help on 22 real bolt frames, the item is hidden in the fingers), fast verdicts, a colour note in the parts request.
- Fix at 15:03: the Live token set the voice twice, so the server did not start.

Added earlier today (details and examples in the same README):
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

- `docs/SUBMISSION.md` and `docs/DEMO_VIDEO.md` were brought up to date at 16:10 with the runs of §3. Still open, listed once in SUBMISSION under "Before submitting": team names, the form's track and video length, the model rule, the recording itself.
- Video: no headset recording has been pulled yet. Record with the headset's own recorder, then `tools/quest/pull_recordings.sh pull`, `… map H<n> raw/<file> <in> <out>` per shot, and `./build.sh` in `jobs/_media/cut/` (outside git). The cuts there (`demo-rough.mp4`, `demo-60s.mp4`) still carry placeholder cards for the headset shots, and their voice-over line 10 is out of date (`docs/DEMO_VIDEO.md`).
- The evidence (`tools/quest/runs/`, `jobs/_media/`) is git-ignored and only on the demo Mac.

## 7. Backup app (Unity)

`quest-unity/` (Unity 6.3 LTS, Meta XR SDK 209): passthrough fixed on the device this morning; round 2 added the UI, voice, camera preview and a world layer with the same server calls. Known defects: `eval/judge/round-2.md`.
