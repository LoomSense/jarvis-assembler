# Assembly Guide Agent

Gen AI Summit Hackathon, Athens, 14–15 Nov 2026. Status as of Sun 15 Nov, 16:10 Athens, corrected at 16:35 against the logs, which ended at 16:31 when read. Demo video script: [DEMO_VIDEO.md](DEMO_VIDEO.md).

**What it does.** It turns a furniture guide into an animated 3D model, one action at a time, and then guides the real assembly through a Meta Quest 3. The headset app (`quest-android/`, native Kotlin) needs nothing printed or stuck on the furniture: it finds each board on the floor by its measured size, lights up the one to take and reads which face is up, puts a ghost of the next part on the real drawer front, checks the part in your hand, says so when it is the wrong one, and moves to the next action by itself. It speaks and writes Greek or English.

**The problem.** Paper guides are drawings without words. They cannot tell you which of two look-alike boards you are holding, which way round it goes, or where you are after a break.

**Today's demo.** The team's own drawer, which came with no guide. We measured it with a tape, took 15 phone photos of the parts, and built a guide for it today: job `drawer`, 6 steps, 17 actions, 5 boards, 4 cam bolts, 4 cam locks already seated in the sides, 4 white pegs (`jobs/drawer-src/`, rebuilt in about 20 s).

## What the headset showed today

Every row is from the app's own event log on the Quest 3 (`agq.jsonl` in `tools/quest/runs/<run>/`, one JSON event per line; the folders are git-ignored and stay on the team's machine). Times are Athens.

### This afternoon, 14:58 to 16:31

Runs: `20261115-145829-android` (14:58 to 15:07 on the head), `20261115-152019-android` (three sessions: 15:21, 15:34, 15:43) and `20261115-155506-android` (six sessions when read at 16:33: 15:55:34 to 16:00:28, 16:00:43 to 16:10:10, then starts at 16:10:22, 16:10:47, 16:17:20 and 16:21:36, the last reaching the finish at 16:28).

| Shown | Log event and run | Numbers |
|---|---|---|
| Steps 2 to 5, and the back board of step 6, advance without the Next button | `step … by: auto` and `coach_phase … done`, run `-145829-`, 14:58:36–15:02:47 | 13 `step` events, all `auto`: no press on Next or Back. In that span the wearer pressed re-align twice and "check this part" twice, and asked two voice questions. 12 actions ended: four cam bolts, both sides with two cam locks each, the bottom, the back. Ended by `placed` (`how: away` or `dwell`, from the hands) 11 times and by the server's `step_complete` once. It stopped at the first white peg |
| Each board checked in the hand | `held_verdict right`, run `-145829-` | Left side 0.965, right side 0.966, bottom 0.977, back 0.937, one cam bolt 0.80 |
| Each of the four cam bolts confirmed aloud | `perception … right_hardware`, run `-152019-`, 15:22:55–15:24:10 | Four confirmations, 0.877 to 0.968, each spoken ("Σωστή βίδα! Βίδωσέ τη στην τρύπα που φωτίζεται."). In the run before only the first bolt was confirmed; in the 15:55 run three of the four |
| A wrong item in the hand is reported | `held_verdict wrong`, expected `BOLT-C`, seen `PEG-W` | Five times: 15:23:49 (0.926, 4 of the last 5 frames), when by the team's account the wearer held up a white peg, and 15:57:29 (0.956), 16:08:21 (0.912), 16:13:44 (0.946) and 16:23:32 (0.952), where the log does not say what was held |
| "Λάθος κομμάτι. Πάρε τη βίδα έκκεντρου." ("Wrong piece. Take the cam bolt.") | `voice`, run `-155506-`, 15:57:29, 16:08:21, 16:13:44, 16:23:32 | Spoken four times, each in the same second as its verdict |
| Boards on the floor found by size | `flat_parts`, run `-152019-`, 15:21:19–15:25:00 | 132 of 271 passes label a board, 56 label three at once (in the whole 16:00 session 274 of 393, 43 of them four at once). Bottom 427–442 × 339–348 mm (guide 447 × 350), sides 429–475 × 104–112 (450 × 110), back 328–338 × 94–104 (334 × 110), front 410–413 × 208–210 (400 × 200) |
| Which face of a board is up | `flat_parts.parts[].cue`, same passes | Side panels read as groove side up in 195 of 197 readings, the front in 8 of 8. The back was never decided (0 of 64). Left against right side is not decided, by design. The log holds the reading, not what the label showed. The take prompt spoke the face three times, for the front only: "Το βλέπω με το αυλάκι προς τα πάνω." ("I see it with the groove up"; run `-155506-`, 16:07:36, 16:11:41, 16:13:10). No prompt for a side panel names a face |
| The board to take is lit | `flat_parts.lit` | The left side at step 3: 12 passes in `-152019-`, 3 in `-145829-`, 6 in `-155506-` |
| The guide locks on the real front, no marker | `place … source: markerless`; `panel … trusted: true` | Locked at 14:58:42 (6 s after the first step), 15:21:36, 15:44:03, 15:56:24. Also at 16:01:00, 10 s after that session's first step (17 s after it started). Trusted frames: 109 (14:58 run), 76 (15:21 session), 24 (15:55 session); median size error 6.2, 4.4 and 4.7 mm, largest 27.5 mm |
| A step ends by itself when its part already lies in place | `coach_phase … done`, `source: device`, `how: in_place`, run `-155506-` | Step 1, with the front already on the floor. From a session that starts at step 1: twice, 12 s and 14 s after the step opened (16:11:17, 16:21:59). After a press on Back: ten times from 15:56:35 to 16:07:38, 5 to 11 s after the press; that case was switched off at 16:10 |
| The app judges on the device which board the hand lifted | `grab`, run `-155506-` | Three times, each `verdict: right`: the drawer front at 16:11:56 and 16:17:45 (moved 0.11 and 0.10 m), the back at 16:26:18. After the first the app said "Σωστό κομμάτι! Ακούμπησε την πρόσοψη του συρταριού μπροστά σου." A wrong board caught this way has not been seen |
| The app reaches the finish, with four presses on Next | `step`, `coach_phase … done`, `finish`, run `-155506-`, session from 16:21:36 | Step 1 to "Το συρτάρι είναι έτοιμο." ("The drawer is ready."), 16:21:46 to 16:28:02. Next was pressed four times, at the cam locks, and Back twice; every other step change was `auto`. All four peg actions ended by the hands: the first two after the peg was confirmed (0.933, 0.908), the last two after "check this part" answered "unsure" |
| The app says where a part was last seen | `voice`, runs `-152019-` (15:43 session) and `-155506-` | "Πάρε: Βίδα έκκεντρου. Την είδα τελευταία μπροστά αριστερά σου, κοντά σου." ("I last saw it ahead on your left, near you"): 15 such lines up to 16:31 (4 in the 15:43 session, 11 in run `-155506-`), for the front and for the cam bolt |
| The coach praises and names the next action in one line | `voice` | "Μπράβο! Πάμε στην επόμενη, 2 από 4. Πάρε: Βίδα έκκεντρου." after each bolt in all three runs |

### Earlier today, 13:21 to 14:38 (shortened from the 14:55 edition of this page: two rows and some numbers were left out)

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

- **Which way round a side panel goes cannot be told reliably.** The model does not separate the rail side from the groove side on real frames, so the app does not say it. Only a side panel on the wrong end of the front was told reliably. That check is wired on the server since 16:04 and on by default, and the app already handles its event. On the desktop it fired once on real headset frames kept at 15:24, and the wider question fired on one phone photo (`server/perception/README.md`). On the headset no "turn it round" event (`flip_needed`) was logged up to 16:31. At 15:24 the wearer held the left side panel the wrong way round and at the wrong end: nothing was said, and the panel counted as placed.
- **Small hardware in the hand is sometimes confused.** A cam bolt and a white peg are a few pixels at arm's length, often hidden by the fingers. One of the four bolts was not confirmed in the 15:55 run. On the desktop, a bolt held during the peg step was read as the peg in 3 of 4 real frames.
- **Finding the parts is slow.** `/parts` took 3.4 to 17.1 s per answer (226 answers up to 16:00, median 4.8 to 6.6 s per session). Ten answers up to 16:09 boxed 5 cam bolts where 4 exist, so a white peg gets a bolt marker now and then.
- **Board recognition on the device fails in direct sunlight on the grey carpet.** In the 14:58 run 67 of 456 passes labelled any board, never more than two at once, and never the bottom. It worked out of the sun at 15:21.
- **Two new pieces are not proven on the headset.** The on-device judge of which board the hand lifted has only said "right" (three `grab` events from 16:11:56, none before, although boards were lifted in each earlier run): a wrong board caught on the device has not been seen. The on-device answer to "πού είναι η βίδα;" logged no `where_is` event: the three voice questions (14:59:45, 14:59:57, 15:22:42) were before that build, and none was asked since. The server's model answered them in 6.6 to 10.8 s: the two "where is the bolt" questions with where the bolt goes, not where it lies, and "Δεν βρίσκω την βίδα." ("I cannot find the bolt") with what the bolt is and that four are in the box. Both pieces have JVM tests on the desktop, not re-run for this page.
- **The finish was reached once, and not without the Next button.** The session from 16:21:36 went from step 1 to the finish (`finish` event at 16:27:59, "Το συρτάρι είναι έτοιμο." spoken at 16:28:02). Next was pressed four times, at the cam locks, and Back twice. All four peg actions ended by the hands: the first two after the peg was confirmed (`right_hardware`, 0.933 and 0.908), the last two after "check this part" answered "unsure" (0.50 and 0.58). The log does not show that each part was really seated. The run with no press on Next (14:58) stopped at the first peg (15:02:47), and the sessions from 15:21 to 16:17 stop at step 3 or earlier.
- **Actions end by where the hands are, not by a check that the part is seated.** At 14:58 a side panel counted as placed 5 s after it was confirmed and the back after 2 s. The 15:13 build waits 7 s for a board and builds since 15:33 wait 9 s: the left side then took 11 s (15:24, 7 s wait) and 45 s (15:59, 9 s wait).
- **The headset cannot be screen-recorded from the Mac while it is worn.** The video uses the headset's own recorder.
- **One drawer, one room, one afternoon.** Boards need a 3 cm gap and a floor darker than they are; two touching boards read as one. The numbers above are the detector's size error, not a measured position error of the ghost.
- The guide took 28 s and 42 s to lock in two sessions, after presses on re-align. A fix went in at 16:00; in the session after it the guide locked 10 s after the first step (17 s after the session started).
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
- `TODO(pegs and finish)`: the 16:21 session reached the finish with four presses on Next; it is in the table and in the sixth limit. If a run reaches the finish with no press on Next, replace the sixth limit with its `coach_phase` events. `grab` is in the table now (three "right" verdicts). If a run logs a `grab` with `verdict: wrong` or a `where_is` event, move that out of the fifth limit into the table.
- `TODO(log may still grow)`: run `-155506-` held six sessions and ended at 16:31:37 when read at 16:33. The 16:00 session ended at 16:10:10 at step 2, action 3; its other counts above were read at 16:09 except where they say "whole". Read the log once more before a claim rests on it.
- The evidence is in git-ignored folders on the demo Mac (`tools/quest/runs/`, `jobs/_media/`). Keep that Mac's copy until judging is over.
