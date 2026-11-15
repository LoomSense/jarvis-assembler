# Handoff: where the project stands

Status as of **Sun 15 Nov 2026, 16:10 Athens**, corrected at 16:35 against the headset logs, which ended at 16:31 when read. For the team and their coding agents. Deadlines today (Athens): feature freeze 17:00, submission 17:30, code freeze 18:00. Every number below was read from today's headset logs (`tools/quest/runs/<run>/agq.jsonl`) or from the file named next to it, unless marked UNVERIFIED.

**In short:** the demo is the native Android app (`quest-android/`) on a Quest 3, with no printed marker, guiding the assembly of the team's real drawer from a custom guide (`jobs/drawer`, 6 steps, 17 actions, Greek). Since 14:20 the headset has shown: steps 2 to 6 advancing without the Next button, as far as the first white peg (14:58 run); each of the four cam bolts confirmed aloud, a white peg reported as the wrong hardware, and the boards on the floor read by size with the face that is up (15:21 session); the new line "Λάθος κομμάτι. Πάρε τη βίδα έκκεντρου." and step 1 ending by itself when the front already lies in place (15:55 run folder); the on-device judge of the lifted board saying "right" three times (`grab`, 16:11 to 16:26); one session from step 1 to the finish (16:21:46 to 16:28:02), with four presses on Next at the cam locks and the last two of the four pegs not confirmed. Not seen on the headset: a run to the finish with no press on Next, a wrong board caught by the on-device judge, a "turn it round" warning (`flip_needed`), the on-device answer to "πού είναι η βίδα;" (no `where_is` event). Not reliable: whether a side panel is the right way round, a cam bolt against a peg in the hand, board recognition in direct sunlight.

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
| surface height | a depth ray through the server's box of the base part (Meta environment raycast), median of three, frozen while the detector trusts frames; a steady gaze probe before that. The headset has no room scan, so there is no MRUK table. New at 16:00: with no measured surface 3 s after the guide loads, the floor (y = 0) is tried. Seen on the headset in all five sessions from 16:00:43 to 16:21:36 (`surface … source: floor_guess`) | `WorldModule.placeBase`, `probeSurface`, `surfaceY` |
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
- **Which board the hand lifted** (`ui/GrabJudge.kt`): judged on the device against the recognised boards, logs `grab` and feeds the same right / wrong path as the server's verdict. Seen on the headset three times, each `verdict: right`: the front at 16:11:56 and 16:17:45, the back at 16:26:18 (§3). A wrong board caught this way has **not been seen**.
- **"Λάθος κομμάτι. Πάρε …"** for any wrong item, board or hardware, with the name of the item to take. Spoken four times on the headset, §3.
- **Where each part was last seen** (`world/WhereIs.kt`): the take prompt adds "Την είδα τελευταία μπροστά αριστερά σου, κοντά σου." when the part is not in view, and "πού είναι η βίδα;" is answered on the device (`where_is` event). The added sentence was spoken on the headset; the answer to the question has **not been seen** there.
- **Hardware already seated has no "take" phase** (manifest `hardware[].preinstalled`: the four cam locks). A tool-turned fastener counts the hand at a screwdriver's length.
- **Colours**: "put it here" cues are green (ghost, path, hole ring, arrow, floor outline), "take this" stays azure. A part found far from the wearer gets a "bring it closer" hint.
- **Rules changed after each headset run**: a board held at its place waits 9 s before "placed" (7 s in the 15:13 build, 9 s since 15:33; not at all after a wrong verdict); a grip left over from the last bolt does not take the next one; server answers that come and go do not end "take" for hardware; no praise for a cam lock while its instruction is read; a wrong verdict restarts the placing; a front that already lies in place ends step 1 by itself (since 16:10 not after Back, Repeat or a voice command); the next action's parts are asked for at once.

---

## 3. What the headset has shown (Android, `tools/quest/runs/*-android/`)

Runs since 14:20: `20261115-145829-android` (14:58 to 15:07 on the head), `20261115-152019-android` (three sessions, each starts with a `start` event: 15:21, 15:34, 15:43) and `20261115-155506-android` (six sessions when read at 16:33: 15:55:34 to 16:00:28, the 15:54 build; 16:00:43 to 16:10:10, which ended at step 2, action 3; then starts at 16:10:22, 16:10:47, 16:17:20 and 16:21:36, the last reaching the finish at 16:28). Counts for "the 16:00 session" were read at 16:09 unless they say "whole". The earlier rows are from the runs of 14:07 to 14:38.

| Seen working | Evidence (run, time, event) |
|---|---|
| Steps 2 to 6 advance without the Next button | `-145829-`, 14:58:36 to 15:02:47: 13 `step` events, all `by: auto`; 12 actions ended (`coach_phase … done`: 11 `placed`, 1 `step_complete`). It stopped at step 6, action 1, the first white peg. No press on Next or Back; the wearer did press re-align twice and "check this part" twice, and asked two voice questions |
| One session from step 1 to the finish | `-155506-`, 16:21:46 to 16:28:02: all six steps, a `finish` event and "Το συρτάρι είναι έτοιμο." Next was pressed four times, at the cam locks (1 to 12 s after each opened), and Back twice; every other step change is `by: auto`. All four peg actions ended by the hands (16:27:00 to 16:27:59): the first two after `right_hardware` (0.933, 0.908), the last two after "check this part" answered `unsure` (0.50, 0.58). The log does not show that each part was really seated |
| Each board confirmed in the hand | `-145829-`, `held_verdict right`: cam bolt 0.80 (14:58:59), left side 0.965 (15:00:45), right side 0.966 (15:01:24), bottom 0.977 (15:02:13), back 0.937 (15:02:44) |
| All four cam bolts confirmed aloud | `-152019-`, `perception … right_hardware` at 15:22:55, 15:23:14, 15:23:33 and 15:24:10 (p 0.944, 0.945, 0.968, 0.877), each spoken as "Σωστή βίδα! Βίδωσέ τη στην τρύπα που φωτίζεται." In `-145829-` only the first bolt was confirmed; in `-155506-` three of the four (15:56:56, 15:57:12, 15:57:50) |
| A wrong item in the hand is reported | `held_verdict wrong`, expected `BOLT-C`, seen `PEG-W`: `-152019-` 15:23:49 (p 0.926, 4 of the last 5 frames; by the team's account the wearer held up a white peg), `-155506-` 15:57:29 (p 0.956), 16:08:21 (0.912), 16:13:44 (0.946) and 16:23:32 (0.952): five in all; for the last four the log does not say what was in the hand |
| "Λάθος κομμάτι. Πάρε τη βίδα έκκεντρου." | `-155506-`, `voice` at 15:57:29, 16:08:21, 16:13:44 and 16:23:32, each in the same second as its verdict. At 15:23:49 the older sentence was spoken, 4.4 s late |
| Boards on the floor by size | `-152019-`, `flat_parts`, 15:21:19 to 15:25:00: 132 of 271 passes label a board, 56 passes label three. Bottom 427–442 × 339–348 mm (guide 447 × 350), sides 429–475 × 104–112 (450 × 110), back 328–338 × 94–104 (334 × 110), front 410–413 × 208–210 (400 × 200, 8 readings) |
| The face that is up | `-152019-`, `flat_parts.parts[].cue`: the side panels read as groove side up in 195 of 197 readings, the front in 8 of 8, the back never decided (0 of 64). Left against right is not decided. The take prompt spoke the face three times, for the front only: "Πάρε: Πρόσοψη του συρταριού, μπροστά σου. Το βλέπω με το αυλάκι προς τα πάνω." (`-155506-`, 16:07:36, 16:11:41, 16:13:10). No prompt for a side panel names a face, and the log cannot show the label |
| The board to take is lit | `flat_parts.lit`: the left side at step 3 in 12 passes of `-152019-`, 3 of `-145829-`, 6 of `-155506-` |
| Step 1 ends by itself when the front already lies in place | `-155506-`: `coach_phase … done`, `why: placed`, `source: device`, `how: in_place`. From a session that starts at step 1: twice, 12 s and 14 s after the step opened (16:11:17, 16:21:59). After a press on Back: ten times from 15:56:35 to 16:07:38, 5 to 11 s after the press; that case was switched off at 16:10 (commit f1b65f1) |
| Where a part was last seen, spoken with the take prompt | `voice`: "Την είδα τελευταία …" 15 times up to 16:31: four in the 15:43 session and 11 in `-155506-` (the first two at 15:56:40 and 15:57:49, with `pick_prompt … source: memory`, 0.49 and 0.30 m) |
| A cam lock is not praised while its instruction is read | `-155506-`: step 3, action 1 shown at 15:59:43 and still open at 16:00:28. In `-145829-` the same action ended after 4.3 s |
| Ghost locked on the real front | `place … source: markerless` at 14:58:42 (6 s after the first step), 15:21:36, 15:44:03, 15:56:24. Trusted `panel` frames: 109 (14:58 run), 76 (15:21 session), 24 (15:55 session); median size error 6.2, 4.4 and 4.7 mm, largest 27.5 mm |
| The camera comes back after the headset was off | `-152019-`, 15:34 session: 40 `camera_restart` from 15:38:09 to 15:42:52, guide locked again at 15:42:57 |
| The perception socket reconnects after a server restart | `-152019-`: closed at 15:39:05 and 15:47:59 (HTTP 502), `hello` again 3 and 4 s later |
| Earlier (still true): passthrough, live camera 1280 × 960 with a pose per frame, Greek speech, hand joints, which way round the front lies | `camera`, `camera_pose`, `voice`, `hands_source: skeleton, joints 70`, `panel.cue`; guide load 2.1 to 4.2 s in eight of the ten sessions since 14:58, and 6.7 s and 8.9 s in the two sessions of 16:10 (`job_loaded.ms`) |

| Problem seen on the headset | State |
|---|---|
| Boards on the floor not recognised in direct sunlight on the grey carpet | Open. `-145829-`: 67 of 456 passes label any board, never more than two at once, the bottom never. 15:55 session: 41 of 252 passes (the log does not say why). Works out of the sun: 15:21 session, and 274 of 393 passes in the whole 16:00 session, 43 of them with four boards at once |
| The on-device board judge has not caught a wrong board | Open. It has fired three times, each `grab … verdict: right`: the front at 16:11:56 and 16:17:45 (moved 0.11 and 0.10 m) and the back at 16:26:18. The first was followed by "Σωστό κομμάτι! Ακούμπησε την πρόσοψη του συρταριού μπροστά σου." (`coach_phase … why: right_part, source: device, how: grab_rect`). No `grab` event before 16:11, although boards were lifted in the 14:58 run and the 15:21 and 15:55 sessions. No `grab` with `verdict: wrong` in any log |
| "Πού είναι η βίδα;" answered on the device | Not seen. No `where_is` event. The three voice questions asked (14:59:45, 14:59:57, 15:22:42) were before that build, and none has been asked since. The server's model answered in 6.6 to 10.8 s: the two "πού είναι η βίδα" questions with where the bolt goes, not where it lies, and "Δεν βρίσκω την βίδα." (14:59:57) with what the bolt is and that four are in the box |
| A cam bolt and a white peg in the hand are confused | Open, and not a matter of thresholds (§5). At step 6, where a peg is expected, the item in the hand gave "unsure" (15:06:50). One bolt of four was not confirmed in `-155506-` |
| Whether a side panel is the right way round (rail and groove side) | Not reliable from the model (measured on real frames by the perception owner), so it is not said. Only a side panel on the wrong end of the front was told reliably. That check is wired on the server and on by default since 16:04 (step 3 of the drawer; the app already handles `flip_needed`; not checked: whether the running demo server has it). Desktop: it fired once on the 21 real headset frames kept at 15:24. Headset: not proven, no `flip_needed` in any log up to 16:31. At 15:24 the wearer held the left side panel at the front the wrong way round and at the wrong end (2 and 3 of those 21 frames): nothing was said, and the panel counted as placed at 15:24:40 |
| `/parts` answers take 3.4 to 17.1 s (median 4.8 to 6.6 s per session, 226 answers up to 16:00; the 16:00 session is in the same range) | Unchanged in effect. Panels and hardware are now asked in parallel |
| `/parts` boxes more cam bolts than exist | Open. 5 boxes for the 4 bolts in ten answers up to 16:09 (two each in the 14:58 run and the 15:21 and 15:55 sessions, four in the 16:00 session), so a white peg gets a bolt marker now and then |
| Actions end by where the hands are, not by a check that the part is seated | Partly fixed. In `-145829-` a side panel counted as placed 5 s after it was confirmed and the back after 2 s. A board held at its place waits 7 s in the 15:13 build and 9 s since 15:33 (commit 530e594) |
| The guide took 28 s and 42 s to lock, after presses on re-align | `-152019-` 15:21 and `-155506-` (where 27 `flat_parts` passes say "no table"). A fix was committed at 16:00 (the floor is tried after 3 s). In the session from 16:00:43 the floor guess came at 16:00:49 and the guide locked at 16:01:00: 10 s after the first step, 17 s after the session started |
| Step 1 did not end with the front already on the floor; bolt markers came late | 15:43 session. Fixed in the 15:54 build: step 1 seen ending by itself (above); the markers were not timed again |
| Pick marker on bolts already screwed in; server box put the ghost on another board; re-align lifted the ghost 22 cm; step window out of view on the floor | Fixed in the 14:19 build. No repeat in the three runs since; not checked one by one |
| Launch blocked by a system dialog while the headset is not worn | The user dismisses it; the launch is cached and proceeds |

Not seen on the headset: a run to the finish with no press on Next (the 14:58 run had none and stopped at the first peg; the 16:21 session reached the finish with four), and the last two pegs confirmed in the hand. A board the wrong way round was tried once, at 15:24 (the side panel row above): nothing was said.

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
- **A wrong part is recognised.** The held-part question now offers every part of a later step and every hardware code of the guide (`all_choices`); the answers for parts of other steps are summed (`wrong_part_sum_min` 0.65, `wrong_part_no` 0.3). Desktop, on 50 real kept frames: 15 vote for a wrong part and each of them shows one. Headset: the five `wrong_hardware` verdicts of §3.
- **Each cam bolt is confirmed** (`reconfirm`): the confirmation re-arms on a new action index, or after the item left the hand for 4 s. Headset: four of four at 15:22 to 15:24.
- **`wrong_way` question per step**, with a reason and a sentence for each mistake (`flip_needed` with `reason`). Desktop only: it fired on one phone photo of the front lying on its face (0.74 and 0.76 against a line of 0.7). Real frames of a wrongly placed side panel exist: 21 kept from the headset at 15:24, 2 with the panel the wrong way round and 3 at the wrong end. Since 16:04 a slower "careful look" (`careful_look`, on by default) confirms one mistake only, the side panel on the wrong end of the front at step 3: sent through a real session on the desktop, those 21 frames gave one look and one `flip_needed` `side_wrong_end`. Not proven through the headset. No picture of a wrongly placed bottom or back exists, so those have never fired.
- **`right_way` event** (the same question's `ok`): on in the code, off for the demo. The one real frame of a side panel standing with its rail inwards scored `ok` 0.76 against a line of 0.8.
- **`/parts` step mode** asks panels and hardware in two parallel requests and sends a slow request a second time.
- **Greek sentences name the part** from the manifest (`names.el`) when the job folder has no `plan.json`.
- Tried and left off: `hardware_colour` (material and colour before the code: no help on 22 real bolt frames, the item is hidden in the fingers), a colour note in the parts request. Fast verdicts were off until 16:20: the demo `.env` now sets `fast` on and `moving_hz` 2.5 (2.5 decisions a second while the hands move) in `PERCEPTION_TUNING`, with `right_way` still off. Not checked: whether the running server was restarted with it.
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

- `docs/SUBMISSION.md` and `docs/DEMO_VIDEO.md` were brought up to date at 16:10 with the runs of §3 and corrected at 16:35. Still open, listed once in SUBMISSION under "Before submitting": team names, the form's track and video length, the model rule, the recording itself.
- Video: three headset recordings are in `jobs/_media/cut/headset/raw/` (61 s, 191 s and 153 s, pulled between 15:36 and 15:58). None is mapped to a shot, and by their file names they were recorded at 14:35, 14:43 and 14:55, before the 14:58 run, so they show older builds. For the cut, record with the headset's own recorder, then `tools/quest/pull_recordings.sh pull`, `… map H<n> raw/<file> <in> <out>` per shot, and `./build.sh` in `jobs/_media/cut/` (outside git). The cuts there (`demo-rough.mp4`, `demo-60s.mp4`) still carry placeholder cards for the headset shots, and their voice-over line 10 is out of date (`docs/DEMO_VIDEO.md`).
- The evidence (`tools/quest/runs/`, `jobs/_media/`) is git-ignored and only on the demo Mac.

## 7. Backup app (Unity)

`quest-unity/` (Unity 6.3 LTS, Meta XR SDK 209): passthrough fixed on the device this morning; round 2 added the UI, voice, camera preview and a world layer with the same server calls. Known defects: `eval/judge/round-2.md`.
