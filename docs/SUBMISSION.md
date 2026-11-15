# Assembly Guide Agent

Gen AI Summit Hackathon, Athens, 14–15 Nov 2026. Status as of Sun 15 Nov, 16:10 Athens. Demo video script: [DEMO_VIDEO.md](DEMO_VIDEO.md).

**What it does.** It turns a furniture guide into an animated 3D model, one action at a time, and then guides the real assembly through a Meta Quest 3. The headset app (`quest-android/`, native Kotlin) needs nothing printed or stuck on the furniture: it finds each board on the floor by its measured size, lights up the one to take and reads which face is up, puts a ghost of the next part on the real drawer front, checks the part in your hand, says so when it is the wrong one, and moves to the next action by itself. It speaks and writes Greek or English.

**The problem.** Paper guides are drawings without words. They cannot tell you which of two look-alike boards you are holding, which way round it goes, or where you are after a break.

**Today's demo.** The team's own drawer, which came with no guide. We measured it with a tape, took 15 phone photos of the parts, and built a guide for it today: job `drawer`, 6 steps, 17 actions, 5 boards, 4 cam bolts, 4 cam locks already seated in the sides, 4 white pegs (`jobs/drawer-src/`, rebuilt in about 20 s).

## What the headset showed today

Every row is from the app's own event log on the Quest 3 (`agq.jsonl` in `tools/quest/runs/<run>/`, one JSON event per line; the folders are git-ignored and stay on the team's machine). Times are Athens.

### This afternoon, 14:58 to 16:09

Runs: `20261115-145829-android` (14:58 to 15:07 on the head), `20261115-152019-android` (three sessions: 15:21, 15:34, 15:43) and `20261115-155506-android` (two sessions: 15:55:34 to 16:00:28, and from 16:00:43, read at 16:09 while it was still running).

| Shown | Log event and run | Numbers |
|---|---|---|
| Steps 2 to 5, and the back board of step 6, advance with no button press | `step … by: auto` and `coach_phase … done`, run `-145829-`, 14:58:36–15:02:47 | 13 `step` events, all `auto`. 12 actions ended: four cam bolts, both sides with two cam locks each, the bottom, the back. Ended by `placed` (`how: away` or `dwell`, from the hands) 11 times and by the server's `step_complete` once. It stopped at the first white peg |
| Each board checked in the hand | `held_verdict right`, run `-145829-` | Left side 0.965, right side 0.966, bottom 0.977, back 0.937, one cam bolt 0.80 |
| Each of the four cam bolts confirmed aloud | `perception … right_hardware`, run `-152019-`, 15:22:55–15:24:10 | Four confirmations, 0.877 to 0.968, each spoken ("Σωστή βίδα! Βίδωσέ τη στην τρύπα που φωτίζεται."). In the run before only the first bolt was confirmed; in the 15:55 run three of the four |
| A wrong item in the hand is reported | `held_verdict wrong`, expected `BOLT-C`, seen `PEG-W` | Three times: 15:23:49 (0.926, 4 of the last 5 frames), when by the team's account the wearer held up a white peg, and 15:57:29 (0.956) and 16:08:21 (0.912), where the log does not say what was held |
| "Λάθος κομμάτι. Πάρε τη βίδα έκκεντρου." ("Wrong piece. Take the cam bolt.") | `voice`, run `-155506-`, 15:57:29 | Spoken once, in the same second as the verdict |
| Boards on the floor found by size | `flat_parts`, run `-152019-`, 15:21:19–15:25:00 | 132 of 271 passes label a board, 56 label three at once (in the 16:00 session 248 of 340, 43 of them four at once). Bottom 427–442 × 339–348 mm (guide 447 × 350), sides 429–475 × 104–112 (450 × 110), back 328–338 × 94–104 (334 × 110), front 410–413 × 208–210 (400 × 200) |
| Which face of a board is up | `flat_parts.parts[].cue`, same passes | Side panels read as groove side up in 195 of 197 readings, the front in 8 of 8. The back was never decided (0 of 64). Left against right side is not decided, by design. The log holds the reading, not what the label showed; no logged take prompt speaks the face |
| The board to take is lit | `flat_parts.lit` | The left side at step 3: 12 passes in `-152019-`, 3 in `-145829-`, 6 in `-155506-` |
| The guide locks on the real front, no marker | `place … source: markerless`; `panel … trusted: true` | Locked at 14:58:42 (6 s after the first step), 15:21:36, 15:44:03, 15:56:24. Also at 16:01:00, 11 s after that session started. Trusted frames: 109 (14:58 run), 76 (15:21 session), 24 (15:55 session); median size error 6.2, 4.4 and 4.7 mm, largest 27.5 mm |
| A step ends by itself when its part already lies in place | `coach_phase … done`, `source: device`, `how: in_place`, run `-155506-`, 15:56:35 | Step 1, 7 s after it was opened, with the front already on the floor. Nine more times from 16:01:02 to 16:07:38, 5 to 11 s after each press on Back |
| The app says where a part was last seen | `voice`, runs `-152019-` (15:43 session) and `-155506-` | "Πάρε: Βίδα έκκεντρου. Την είδα τελευταία μπροστά αριστερά σου, κοντά σου." ("I last saw it ahead on your left, near you"): six such lines in all, for the front and for the cam bolt |
| The coach praises and names the next action in one line | `voice` | "Μπράβο! Πάμε στην επόμενη, 2 από 4. Πάρε: Βίδα έκκεντρου." after each bolt in all three runs |

### Earlier today, 13:21 to 14:38 (unchanged from the 14:55 edition of this page)

| Shown | Log event and run | Numbers |
|---|---|---|
| Every board found by its size, the one to take lit | `flat_parts`, run `-141058-`, 14:11:31–14:14:04 | Front read as 405 × 201 mm, sides 431–435 × 108–110, back 337 × 94–96, bottom 446 × 341. 25 passes with four or more boards labelled. `lit` named the front at step 1 and the left side panel at step 3 |
| The guide locked on the real front | `panel … trusted: true`, `place … source: markerless` | 71 trusted frames in run `-141058-`, 40 in `-142733-`; median size error 5.8 mm and 4.9 mm |
| The same, seen | `jobs/_media/stills/08-headset-capture-1350.jpg` | Headset capture at 13:50: the left side panel's ghost standing on the real front. That run (`-134623-`) used the earlier guide rebuilt with the same tape measurements |
| Which way round the front lies, from its groove | `panel.cue` | Decided on every trusted frame (71 of 71, 40 of 40). One 180° turn applied, at 14:28:06 |
| Camera, speech, frame rate, hands | `track`, `voice`, `held` | 1280 × 960 frames with a pose each, Greek instructions spoken, 81–86 fps median, finger joints (`source: skeleton`), pinch detected |

## How it works

```
guide PDF, or tape measurements and photos ─► plan.json ─► CadQuery parts ─► fit checks ─► Blender ─► walkthrough.glb + manifest.json
FastAPI server: jobs, catalog, /api/identify, /locate, /parts, WS /ws/perception, /api/tts        ▲ camera frames ▼ verdicts, boxes, speech
Quest 3 app: passthrough, ghost, step window, voice; on-device detectors at 3 Hz for the base board, every flat board, the groove
```

- **Build.** From a PDF, a build agent (Claude Agent SDK) writes the plan and the CadQuery script of every part, and fixes them until the fit checks pass: KNAGGLIG box, 37 parts and 41 actions, 438 s and $2.12 from the PDF alone; MALM desk drawer, 19 parts and 26 actions, every fit check 0.0 mm. For the custom drawer the plan came from our measurements (`make_plan.py`); the rest is the same pipeline.
- **Server perception (Gemini).** `gemini-decision-1`: live frame checks over a socket (step complete, right or wrong part, each bolt, wrong way round), locating the step, scoring each built step against the guide's drawing. `gemini-4-hydrogen`: boxes for the parts a step needs (`/parts`), product identification, the per-step questions, voice commands. `gemini-3.8-flash-lite-tts`: the spoken instructions. `gemini-3.8-live`: a voice session token, not used by the headset app. `gemini-4-carbon`: only in the model smoke test.
- **On the headset, no model call.** `vision/PanelPose.kt` finds the step's base board (x, y, yaw) from the guide's own mesh sizes, `vision/RecessCue.kt` reads which way round it lies, `world/FlatParts.kt` labels every board on the floor, `vision/BoardCue.kt` reads which face is up, `world/WhereIs.kt` remembers where each part was last seen. Hand joints come from the body-tracking API. The server's slower answers (3.4 to 17.1 s for `/parts` this afternoon) fill in the loose hardware.
- The build agent is not a Gemini model: `claude-fable-5-2` for the demo (`AGJ_AGENT_MODEL` in `.env`), code default `claude-opus-5-5`. Gemini checks its output and does everything live.

## Honest limits

- **Which way round a side panel goes cannot be told reliably.** The model does not separate the rail side from the groove side on real frames, so the app does not say it. Only the wrong end of the front was told reliably, and that is not wired into the app. No "turn it round" event (`flip_needed`) was logged on the headset. The question exists on the server and has fired on one phone photo only (`server/perception/README.md`).
- **Small hardware in the hand is sometimes confused.** A cam bolt and a white peg are a few pixels at arm's length, often hidden by the fingers. One of the four bolts was not confirmed in the 15:55 run. On the desktop, a bolt held during the peg step was read as the peg in 3 of 4 real frames.
- **Finding the parts is slow.** `/parts` took 3.4 to 17.1 s per answer (226 answers up to 16:00, median 4.8 to 6.6 s per session). Ten answers up to 16:09 boxed 5 cam bolts where 4 exist, so a white peg gets a bolt marker now and then.
- **Board recognition on the device fails in direct sunlight on the grey carpet.** In the 14:58 run 67 of 456 passes labelled any board, never more than two at once, and never the bottom. It worked out of the sun at 15:21.
- **Two new pieces have not been seen on the headset.** The on-device judge of which board the hand lifted logged no `grab` event in any run, although boards were lifted in each. The on-device answer to "πού είναι η βίδα;" logged no `where_is` event: the three times the question was asked were before that build, and the server's model answered in 6.6 to 10.8 s with where the bolt goes, not where it lies. Both have JVM tests on the desktop, not re-run for this page.
- **The four pegs and the finish have not run.** The furthest logged point is step 6, action 1 (the first peg, 15:02:47). The sessions after that stop at step 3 or earlier.
- **Actions end by where the hands are, not by a check that the part is seated.** At 14:58 a side panel counted as placed 5 s after it was confirmed and the back after 2 s. Builds after 15:13 wait 7 s for a board: the left side then took 10 s (15:24) and 45 s (15:59).
- **The headset cannot be screen-recorded from the Mac while it is worn.** The video uses the headset's own recorder.
- **One drawer, one room, one afternoon.** Boards need a 3 cm gap and a floor darker than they are; two touching boards read as one. The numbers above are the detector's size error, not a measured position error of the ghost.
- The guide took 28 s and 42 s to lock in two sessions, after presses on re-align. A fix went in at 16:00; in the session after it the guide locked in 11 s.
- The step-check accuracy we measured is on renders only: 50 of 55 correct with no false "done" on the MALM drawer (`server/perception/README.md`).
- The Android JVM tests and the server tests were not re-run for this page. The WebXR client is a desktop preview, not the demo path. `quest-unity/` is the backup app and is not shown.

## Run it

```sh
make setup && make env        # then add GEMINI_API_KEY to .env
make dev                      # server on http://localhost:8000
make smoke                    # second terminal: build the golden guide end to end
.venv/bin/python jobs/drawer-src/build.py                  # the custom drawer guide (its sources are on the demo Mac)
tools/quest/doctor.sh && tools/quest/install.sh android    # the demo app
tools/quest/run.sh android --job drawer --lang el --secs 600 --keep-running    # logs to tools/quest/runs/
```

The server URL and demo key are read from `jobs/_host/url.txt` and `.env`; neither is in git. Details: `README.md`, `docs/HANDOFF.md`, `quest-android/README.md`.

## Team and credits

`TODO(names)`: A, backend and infra · B, agent and prompts · C, CAD and Blender · D, phone client and Quest · E, perception and story. Third-party code, models and guides: `CREDITS.md`. Track: Open Track, hardware Meta Quest 3 (`TODO(confirm on the form)`).

---

## Before submitting (delete this section)

- `TODO(names)`: team names, here and on the video's end card (`TEAM NAMES` in `jobs/_media/cut/shots.txt` and `shots-60s.txt`). Waits for the team.
- `TODO(confirm on the form)`: the track name and the form's required fields are not recorded in the repo. Waits for someone to open the form.
- `TODO(length)`: the form's video length limit is not recorded in the repo. A 2:42 cut and a 60 s cut are both built; pick one when the limit is known.
- `TODO(model rules)`: the build agent is not a Gemini model (`claude-fable-5-2` for the demo, code default `claude-opus-5-5`; `docs/PLAN.md` D20 and D22 say to check the event's rules). Confirm before submitting, and say it in the form if asked.
- `TODO(video)`: the headset shots are placeholders until one recording session is done with the headset's own recorder: `docs/DEMO_VIDEO.md`, "Recording session". Voice-over line 10 and its card still say steps four to six are unproven: change them as written there. Then listen to the synthesized voice-over once; nobody has.
- `TODO(pegs and finish)`: if a run reaches the last peg or the finish, replace the sixth limit with that run's `coach_phase` events. If a run logs a `grab` or a `where_is` event, move that limit up into the table.
- `TODO(16:00 session)`: the second session of run `-155506-` (from 16:00:43) was read at 16:09 while it was still running. Read its log once more before a claim rests on it.
- The evidence is in git-ignored folders on the demo Mac (`tools/quest/runs/`, `jobs/_media/`). Keep that Mac's copy until judging is over.
