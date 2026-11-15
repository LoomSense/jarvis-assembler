# Assembly Guide Agent

Gen AI Summit Hackathon, Athens, 14–15 Nov 2026. Status as of Sun 15 Nov, 14:55 Athens. Demo video script: [DEMO_VIDEO.md](DEMO_VIDEO.md).

**What it does.** It turns a furniture guide into an animated 3D model, one action at a time, and then guides the real assembly through a Meta Quest 3. The headset app (`quest-android/`, native Kotlin) needs nothing printed or stuck on the furniture: it finds each board on the floor by its measured size, lights up the one to take, puts a ghost of the next part on the real drawer front, checks the part in your hand, and moves to the next action by itself. It speaks and writes Greek or English.

**The problem.** Paper guides are drawings without words. They cannot tell you which of two look-alike boards you are holding, which way round it goes, or where you are after a break.

**Today's demo.** The team's own drawer, which came with no guide. We measured it with a tape, took 15 phone photos of the parts, and built a guide for it today: job `drawer`, 6 steps, 17 actions, 5 boards, 4 cam bolts, 4 cam locks already seated in the sides, 4 white pegs (`jobs/drawer-src/`, rebuilt in about 20 s).

## What the headset showed today

Every row is from the app's own event log on the Quest 3 (`agq.jsonl` in `tools/quest/runs/<run>/`; the folders are git-ignored and stay on the team's machine). Runs on the custom guide: `20261115-140717-android`, `-141058-`, `-142733-`, `-143616-`. Times are Athens.

| Shown | Log event and run | Numbers |
|---|---|---|
| Every board found by its size, the one to take lit | `flat_parts`, run `-141058-`, 14:11:31–14:14:04 | Front read as 405 × 201 mm (guide 400 × 200), sides 431–435 × 108–110 (450 × 110), back 337 × 94–96 (334 long), bottom 446 × 341 (450 × 350). 25 passes with four or more boards labelled. `lit` named the front at step 1 and the left side panel at step 3. 24 of 256 passes had a rectangle it could not label, and a few list a board twice |
| The guide locked on the real front, no marker | `panel … trusted: true`, `place … source: markerless` | 71 trusted frames in run `-141058-`, 40 in `-142733-`. Placed by the on-device detector at 14:11:49, 14:28:06 and 14:36:27; in the two later runs 12 s and 10 s after the first step appeared, without the server's box placing it first. Measured size against the guide's 400 × 200 mm: median error 5.8 mm and 4.9 mm (p90 20.0 and 8.7, max 25.4) |
| The same, seen | `jobs/_media/stills/08-headset-capture-1350.jpg` | Headset capture at 13:50: the left side panel's ghost standing on the real front. That run (`-134623-`) used the earlier guide rebuilt with the same tape measurements |
| Which way round the front lies, from its groove | `panel.cue` | Decided on every trusted frame (71 of 71, 40 of 40): score 13–38 for the chosen way round against under 5 for the other. One 180° turn applied, at 14:28:06 |
| "Take" → "put it here" → next action, no button | `coach_phase`, run `-141058-`, 14:12:09–14:14:39 | Four cam bolts and the left side panel: by `right_part`, `part_moved`, `placed` (hands away) and `step_complete`. The step before them was a press on Next in that run and `step_complete` in run `-142733-` |
| The right part in the hand | `held_verdict right`, 13:21–14:29 | Nine verdicts from the server: front 0.86–0.92, cam bolt 0.76–0.86, side panel 0.88–0.89 (four of the nine on the custom guide) |
| Camera, speech, frame rate, hands | `track`, `voice`, `held` | 1280 × 960 frames with a pose each, Greek instructions spoken, 81–86 fps median (5th percentile 38–64), finger joints (`source: skeleton`), pinch detected |

## How it works

```
guide PDF, or tape measurements and photos ─► plan.json ─► CadQuery parts ─► fit checks ─► Blender ─► walkthrough.glb + manifest.json
FastAPI server: jobs, catalog, /api/identify, /locate, /parts, WS /ws/perception, /api/tts        ▲ camera frames ▼ verdicts, boxes, speech
Quest 3 app: passthrough, ghost, step window, voice; on-device detectors at 3 Hz for the base board, every flat board, the groove
```

- **Build.** From a PDF, a build agent (Claude Agent SDK) writes the plan and the CadQuery script of every part, and fixes them until the fit checks pass: KNAGGLIG box, 37 parts and 41 actions, 438 s and $2.12 from the PDF alone; MALM desk drawer, 19 parts and 26 actions, every fit check 0.0 mm. For the custom drawer the plan came from our measurements (`make_plan.py`); the rest is the same pipeline.
- **Server perception (Gemini).** `gemini-decision-1`: live frame checks over a socket (step complete, right or wrong part, wrong way round), locating the step, scoring each built step against the guide's drawing. `gemini-4-hydrogen`: boxes for the parts a step needs (`/parts`), product identification, the per-step questions, voice commands. `gemini-3.8-flash-lite-tts`: the spoken instructions. `gemini-3.8-live`: a voice session token, not used by the headset app. `gemini-4-carbon`: only in the model smoke test.
- **On the headset, no model call.** `vision/PanelPose.kt` finds the step's base board (x, y, yaw) from the guide's own mesh sizes, `vision/RecessCue.kt` reads which way round it lies, `world/FlatParts.kt` labels every board on the floor, hand joints come from the body-tracking API. The server's slower answers (5–20 s for `/parts`) fill in the loose hardware.
- The build agent is not a Gemini model: `claude-fable-5-2` for the demo (`AGJ_AGENT_MODEL` in `.env`), code default `claude-opus-5-5`. Gemini checks its output and does everything live.

## Honest limits

- **Wrong way round and a wrong small part are unproven on real frames.** Both come from the server only. No `flip_needed` was logged on the headset. A `wrong_part` verdict was spoken twice (14:09:44 and 14:10:17, a side panel where a cam bolt was expected, 0.84 and 0.85), but the log does not say what was in the hand, so we do not claim it. A wrong board picked up is not caught on the device yet.
- **Steps 4 to 6 of the custom guide have not run on the headset** (right side, bottom, back and pegs), nor the finish. The furthest logged point is step 3, action 1 (the first cam lock, shown at 14:14:39, not done). The two runs after the 14:19 build stop at steps 2 and 1.
- **One drawer, one room, one afternoon.** Boards need a 3 cm gap and a floor darker than they are; two touching boards read as one. The numbers above are the detector's size error, not a measured position error of the ghost: for that there is the capture and the wearer's eyes.
- Trusted frames can be up to 25 mm off in size (the gate is 30 / 25 mm). A re-align at step 3 put the guide 22 cm above the floor (14:14:38); the 14:19 build changes this and the logs since do not reach that step.
- Run `-142733-` logged no tracked hand for its three minutes (`hands_status`); hands were back in run `-143616-`.
- The step-check accuracy we measured is on renders only: 50 of 55 correct with no false "done" on the MALM drawer (`server/perception/README.md`).
- 286 Android JVM tests and 331 server tests pass according to `docs/HANDOFF.md`; they were not re-run for this page. The WebXR client is a desktop preview, not the demo path. `quest-unity/` is the backup app and is not shown.

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
- `TODO(video)`: the five headset shots are placeholders until one recording session is done: `docs/DEMO_VIDEO.md`, "Recording session". Then listen to the synthesized voice-over once; nobody has.
- `TODO(steps 4–6)`: if a run after 14:40 goes past step 3, replace the second limit with that run's `coach_phase` events. If the recorded take does not show the guide following the front, use the "nofollow" voice-over line (`docs/DEMO_VIDEO.md`, fallback).
- The evidence is in git-ignored folders on the demo Mac (`tools/quest/runs/`, `jobs/_media/`). Keep that Mac's copy until judging is over.
