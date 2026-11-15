# Assembly Guide Agent

Gen AI Summit Hackathon, Athens, 14–15 Nov 2026. Status as of Sun 15 Nov, 12:50 Athens. Demo video script: [DEMO_VIDEO.md](DEMO_VIDEO.md).

**What it is.** Give it a flat-pack furniture guide (PDF): an agent rebuilds the guide as an animated 3D model, one action at a time, and checks its own work against the guide's drawings. On a Meta Quest 3 a native app then loads that model, asks the server which step the real assembly is at from a camera frame, and shows and speaks the next action in Greek or English. Nothing is printed or stuck on the furniture: the guide is placed on the work surface and the server's camera answer moves it onto the real part.

**Demoed headset app:** `quest-android/` (Kotlin, Meta Spatial SDK). `quest-unity/` (Unity 6.3, Meta XR SDK) is the backup: it ran on the headset this morning and is not shown in the video.

**The problem.** Paper assembly guides are drawings without words. They are precise, but they cannot tell you which of two look-alike screws you are holding, or where you are after a break. People lose time and make mistakes that mean taking the piece apart again.

## What works today, with evidence

Nothing below is claimed without a measurement, a file or a commit. Run folders in `tools/quest/runs/` are git-ignored and stay on the team's machine.

| What | Evidence |
|---|---|
| PDF → plan → CadQuery parts → fit checks → animated GLB and manifest, one action at a time | KNAGGLIG box: 37 parts, 41 actions. MALM desk drawer: 19 parts, 26 actions. All fit checks 0.0 mm (`docs/HANDOFF.md` §1, `pipeline/`) |
| The build agent does this from the PDF alone | KNAGGLIG: 438 s, $2.12, first build passes, plan scorer 1.00 / 1.00 / 1.00. LACK side table: 121 s, $1.03. MALM drawer: first try, about 10 min. MARKUS office chair: 12 min, $2.94 (`docs/PROGRESS.md` §4 and §7; one run each) |
| Server: jobs, build feed, catalog, voice, perception socket | 225 tests pass (`docs/HANDOFF.md` §1) |
| Identify the product from one frame | MALM guide page → "MALM desk", 0.99, 3–4 s; a KNAGGLIG render with no text is matched by picture (`server/identify.py`) |
| Locate the step from one frame | MALM renders: after step 1 → next 2, after step 5 → next 6, finished → done; about 1 s (`server/perception/locate.py`) |
| Live checks over a socket | Real key: p50 447 ms, p95 822 ms per frame; `step_complete` 2.5 s after the first frame (`server/perception/`) |
| Step-complete accuracy | **50 of 55 correct, 0 false "done"** at threshold 0.45, up from 46 of 55 and 1 false "done" this morning (on the 49-pair step-by-frame grid inside those calls: 83.7 % → 89.8 %). The same 55 MALM drawer pairs, **renders only**: no hands, no real camera frames (`server/perception/README.md`, commit `9425350`; first run in `jobs/_verify/`). What changed: hardware that the reference picture hides gets a close-up as a third image. **Step 3** (cam locks) is still marginal: 0.46 when done against the 0.45 line, 0.42 on the frame before it. Step 7 (runner screws) no longer fires on its "before" frame (0.34; 0.60 when done) |
| The parts a step needs, from one frame | `POST /api/perception/{job}/parts` with `mode: "step"`: every item of the step with a box per instance and counts ("3 of 4 in view"). 6 real calls on MALM renders pasted on a plain background, **not photos**: 4.5 to 6.6 s, median 5.1 s, counts right in 6 of 6; one wrong answer, a side panel boxed as the back panel of the same outline (`server/perception/README.md`, commit `bf86cb5`) |
| The drawer front on the table without a marker, offline | x, y and yaw of the white 380 × 130 mm panel from one frame (`server/perception/panel.py`; Kotlin port `quest-android/app/src/main/java/com/agj/android/vision/PanelPose.kt`). **Renders only**, degraded on purpose: 71 of 90 panel-alone frames trusted (79 %), position error p50 4.6 mm (p90 10.8, max 17.0); 0 of 270 later-assembly frames and 0 of 12 hand-over-panel frames trusted, so no grossly wrong pose was trusted (`tools/markerless/FRONT_PANEL.md`) |
| Both native apps build, and place every part within 1 mm of the manifest target on MALM and KNAGGLIG | Android: 57 JVM tests, max error 0.0 mm (`4882a92`, `quest-android/README.md`). Unity: EditMode 15/15, max error 0.0000 mm (`7c62ad2`, `quest-unity/README.md`) |
| Printed-marker tracker (in both apps, **not used in the demo**: there is no printed strip) | Offline only. Synthetic 300-frame replay, 300/300 tracked: Android median error 1.37 mm (`eval/judge/round-1/android-track.txt`), Unity median 0.83 mm (`quest-unity/README.md`, EditMode test) |

**On the Quest 3 today, the demo app** (`quest-android`, job `malm-drawer`, Greek). Five launches from 12:10 to 12:37: runs `20261115-121052-android`, `-121244-`, `-121737-`, `-122616-` (continued in `-123054-`) and `-123728-`; the last two ran for about ten minutes each. Events are in each run's `agq.jsonl`. The headset's battery ran out at about 12:45 (the last logged event is at 12:46) and it is charging until about 13:30, so there is no device evidence after that yet.

| | Logged |
|---|---|
| Camera | live, 1280×960, with the camera pose on the frames sent to the server (`camera_frame`, `parts … cam_pose: true`) |
| Guide loaded | 1.8 to 2.3 s from the server, 7 steps, 19 parts, max error 0.0 mm (`job_loaded`) |
| Locate answered | 1.15 to 1.58 s → step 1, on all five launches (`locate`) |
| First part of the guide shown | 4.9 to 5.6 s after the app starts (`ghost_show`) |
| Perception socket | connected, vision live. Ack round trip: median 110 to 124 ms over the two long runs (one-second windows from 56 to 545 ms; up to 730 ms just after connecting). The only event received so far is `more_light` (24 times, 0.46 to 0.92 s) |
| Voice | the Greek instruction is spoken on every launch (0.14 to 0.22 s, cached), and the cam bolt line after a press on Next (run `-121244-`) |
| Frame rate | p50 75 to 88 fps. p5 83 to 85 fps over the two long runs; 37 to 50 fps over the first minute after a launch (four runs) |
| Work surface | no room scan on this headset (`scene: error_no_rooms_found`), so the guide starts at a fixed spot in front of the wearer and takes its height from the depth raycast. The first height came 9 and 10 minutes after launch in the two long runs, and it moved between −0.03 and 0.56 m within 30 s in run `-123054-` |
| The server moves the guide onto the real part | logged twice: the server's box for the drawer front (confidence 0.96, run `-121244-`; 0.98, run `-123054-`) moved the guide 0.33 m and 0.54 m to where that box meets the work surface (`place … source: server_box`); the wearer saw it land on the part the first time. The call takes 2.9 to 11.0 s on the headset (median 4.2 s, 194 calls) and reported the drawer front in 10 of them; the logs do not say in how many frames it was in view |
| The guide follows the part when it moves | **not yet**: see the first honest limit |
| Hand tracking | failed to start on every launch (`hands_source … joints: -1`); being fixed. The Next button was pressed once (run `-121244-`, `step … by: button`) |

The backup app (`quest-unity`) ran this morning (run `20261115-114020-unity`): guide loaded, locate answered in 1.51 s → step 1 at confidence 0.84, first part shown 16.3 s after launch, 69 to 72 fps over 25 s.

## How the Gemini models are used

| Model ID | Use | Where |
|---|---|---|
| `gemini-decision-1` | Live frame checks (step complete, wrong hardware, wrong orientation), locating the step, and scoring each built step against the guide's drawing | `server/perception/decider.py`, `locate.py`, `pipeline/check/step_check.py` |
| `gemini-4-hydrogen` | Identifying the product, writing the per-step questions, answering questions during assembly, parts inventory, finding a part (or every part of a step) in the camera frame, voice commands | `server/identify.py`, `pipeline/perception/questions.py`, `pipeline/ask.py`, `pipeline/inventory.py`, `server/perception/pile.py`, `server/voice.py` |
| `gemini-3.8-flash-lite-tts` | The spoken instructions (`POST /api/tts`) | `server/routes/tts.py` |
| `gemini-3.8-live` | Voice session token (`POST /api/live/token`); a session opened from Python, not used by the headset apps | `server/live_token.py` |
| `gemini-4-carbon` | Called once in the model smoke test; not in the running pipeline | `eval/smoke_models.py` |

The plan and the part scripts are written by a build agent on the Claude Agent SDK (`docs/PLAN.md` D20; the demo runs it on `claude-fable-5-2` through `AGJ_AGENT_MODEL`, the code default and the timed builds above are `claude-opus-5-5`); Gemini checks its output and does everything live.

## Architecture

```
guide.pdf ─► build agent ─► plan.json + CadQuery parts ─► fit checks ─► Blender ─► walkthrough.glb + manifest.json
                 ▲ fixes until checks pass      ▲ gemini-decision-1 scores each step against the guide's drawing
FastAPI server: jobs, feed (SSE), catalog, /api/identify, /locate, /parts, WS /ws/perception, /api/tts
        ▲ camera frames (768 px JPEG, up to 4 Hz), hand summaries        ▼ GLB, manifest, events, speech
Quest 3 native app (quest-android; quest-unity is the backup): passthrough, ghost, step window, voice.
  No marker: placed on the work surface, moved onto the part by the server's box, drawer-front detector on the device
Browser: three.js walkthrough (client/), checked in desktop Chrome and an emulated iPhone, not on a real phone
```

## Honest limits

- `TODO(tracking: confirm on headset)` **The guide does not yet follow the real part on the headset.** It is placed on the work surface, and the server's camera answer moves it onto the drawer front (logged twice, table above). The on-device detector that should then follow the part at 3 Hz is measured on renders only; in today's runs it trusted no frame (954 samples: 886 had no work-surface height yet, 40 looked at a picture and rejected it, the rest were not looking down or had no camera pose). *If a headset run shows it working, this line becomes:* "On the headset the guide follows the drawer front when it is slid or turned (on-device detector, 3 Hz; run and numbers)."
- No `step_complete`, wrong-part warning or identify has been logged on the headset. The accuracy numbers are from renders; a real camera, hands and room light are not measured.
- Step 3 of the MALM drawer is marginal (above), so the person always keeps Next and Back.
- The app's hand tracking did not start in today's runs, so nothing that needs fingertips (hand summaries for the live checks, touching a corner) has run on the headset.
- The WebXR client (`client/src/xr/`) exists but is not the demo path: Quest Browser on our headset exposed no camera (`eval/judge/round-0/quest-camera-diag.json`), and WebXR gives no camera pose. It runs as a desktop preview.
- Device numbers are from five launches of one app on one afternoon. Guides are IKEA's; PDFs and crops stay out of git except the test guides.

## Run it

```sh
make setup && make env        # then add GEMINI_API_KEY to .env
make dev                      # server on http://localhost:8000
make smoke                    # second terminal: build the golden guide end to end
tools/quest/doctor.sh && tools/quest/install.sh android   # the demo app; unity is the backup
tools/quest/run.sh android --job malm-drawer --lang el    # logs to tools/quest/runs/
```

The server URL and demo key are read from `jobs/_host/url.txt` and `.env`; neither is in git. Details: `README.md`, `quest-android/README.md`, `quest-unity/README.md`.

## Team and credits

`TODO(names)`: A, backend and infra · B, agent and prompts · C, CAD and Blender · D, phone client and Quest · E, perception and story. Third-party code, models and guides: `CREDITS.md`. Track: Open Track, hardware Meta Quest 3 (`TODO(confirm on the form)`).

---

## Before submitting (delete this section)

- `TODO(tracking: confirm on headset)`: waits for the headset (charging until about 13:30) and then for a run whose `agq.jsonl` shows `panel` events with `trusted: true` and `place` events with `source: markerless` while the drawer front is slid and turned. If it does, replace the marked line in "Honest limits" and the row "The guide follows the part" with the claim and that run's numbers, and do the same in `README.md` (status paragraph) and `docs/DEMO_VIDEO.md` (shot 8, level A). If it does not, delete only the marker and the sentence in italics.
- `TODO(video)`: waits for the headset recordings H1 to H5 (`docs/DEMO_VIDEO.md`). Both cuts are built with the voice-over and placeholder cards in `jobs/_media/cut/`; its `README.txt` says where the clips go and what to change for each tracking outcome. The voice is synthesized and was checked by measurement only: listen to it once.
- `TODO(job text)`: the demo job `malm-drawer` was built at 09:08, before the instruction-text fix (`e32eadd`, 09:42). Its dowel, cam lock and plastic pin actions (steps 2, 3 and 5) still say "screw it in here. Screw 1 of 2" in both languages; step 1 (cam bolts) is right. Either rebuild the job's text before recording past step 1, or record step 1 only, as the script does. The video hides those captions.
- `TODO(names)`: team names, here and on the video's end card. `TODO(confirm on the form)`: track name and the form's required fields; they are not recorded in the repo.
- `TODO(model rules)`: the build agent is not a Gemini model (`docs/PLAN.md` D20 and D22 say to check the event's rules). Confirm before submitting.
- `TODO(length)`: the form's video limit is not recorded in the repo; a 2:47 cut and a 60 s cut both exist.
