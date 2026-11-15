# Assembly Guide Agent

Gen AI Summit Hackathon, Athens, 14–15 Nov 2026. Status as of Sun 15 Nov, 12:00 Athens. Demo video script: [DEMO_VIDEO.md](DEMO_VIDEO.md).

**What it is.** Give it a flat-pack furniture guide (PDF): an agent rebuilds the guide as an animated 3D model, one action at a time, and checks its own work against the guide's drawings. On a Meta Quest 3 a native app then loads that model, asks the server which step the real assembly is at from a camera frame, and shows and speaks the next action in Greek or English.

**Demoed headset app:** `TODO(decide after round 2)`: `quest-android/` (Kotlin, Meta Spatial SDK) or `quest-unity/` (Unity 6.3, Meta XR SDK). Both exist and both ran on the headset this morning; one line here names the one in the video.

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
| Step-complete accuracy | **83.7 % correct, 0 false "done"** at threshold 0.45: 55 calls, MALM drawer, clean renders, no hands (`jobs/_verify/`). Weak steps: **step 3** (cam locks) scores 0.44 and never fires; **step 7** (runner screws) can fire falsely after a turn (0.69 on a "before" frame). Model-written questions gave the same accuracy with one false "done" |
| Marker tracking, offline | Synthetic 300-frame replay, 300/300 tracked: Android median error 1.37 mm (`eval/judge/round-1/android-track.txt`), Unity median 0.83 mm (`quest-unity/README.md`, EditMode test) |
| Both native apps build, and place every part within 1 mm of the manifest target on MALM and KNAGGLIG | Android: 57 JVM tests, max error 0.0 mm (`4882a92`, `quest-android/README.md`). Unity: EditMode 15/15, max error 0.0000 mm (`7c62ad2`, `quest-unity/README.md`) |

**On the Quest 3 this morning** (job `malm-drawer`, Greek):

| | `quest-android` | `quest-unity` |
|---|---|---|
| Run | `20261115-111202-android`, `20261115-112320-android-live` | `20261115-114020-unity` (`logcat.txt`) |
| Guide loaded | 2.4 s from the server, 7 steps, 19 parts, max error 0.0 mm | 0.09 s logged, 7 steps, 19 nodes |
| Locate answered | 1.55 s → step 1, with the live headset camera running (1280×960) | 1.51 s → step 1, confidence 0.84 |
| Launch to first ghost | 11.0 s | 16.3 s |
| Live perception events | socket connected; one event received (`more_light`, 1.2 s) | one event received (`more_light`, 0.5 s) |
| Voice | Greek lines spoken (first 2.6 s, cached 0.1 s) | not logged |
| Frame rate | p50 89, p5 80.9 fps over 80 s | 69–72 fps over 25 s |
| **Marker tracking** | **never tracked** (0 of 702 samples); the ghost was placed at a fixed spot | **never tracked** (0 samples) |

## How the Gemini models are used

| Model ID | Use | Where |
|---|---|---|
| `gemini-decision-1` | Live frame checks (step complete, wrong hardware, wrong orientation), locating the step, and scoring each built step against the guide's drawing | `server/perception/decider.py`, `locate.py`, `pipeline/check/step_check.py` |
| `gemini-4-hydrogen` | Identifying the product, writing the per-step questions, answering questions during assembly, parts inventory, finding a part in the pile, voice commands | `server/identify.py`, `pipeline/perception/questions.py`, `pipeline/ask.py`, `pipeline/inventory.py`, `server/perception/pile.py`, `server/voice.py` |
| `gemini-3.8-flash-lite-tts` | The spoken instructions (`POST /api/tts`) | `server/routes/tts.py` |
| `gemini-3.8-live` | Voice session token (`POST /api/live/token`); a session opened from Python, not used by the headset apps | `server/live_token.py` |
| `gemini-4-carbon` | Called once in the model smoke test; not in the running pipeline | `eval/smoke_models.py` |

The plan and the part scripts are written by a build agent on the Claude Agent SDK (`claude-opus-5-5`, `docs/PLAN.md` D20); Gemini checks its output and does everything live.

## Architecture

```
guide.pdf ─► build agent ─► plan.json + CadQuery parts ─► fit checks ─► Blender ─► walkthrough.glb + manifest.json
                 ▲ fixes until checks pass      ▲ gemini-decision-1 scores each step against the guide's drawing
FastAPI server: jobs, feed (SSE), catalog, /api/identify, /locate, WS /ws/perception, /api/tts
        ▲ camera frames (768 px JPEG, up to 4 Hz), hand summaries        ▼ GLB, manifest, events, speech
Quest 3 native app (quest-android or quest-unity): passthrough, ghost, step window, marker tracker, voice
Browser: three.js walkthrough (client/), checked in desktop Chrome and an emulated iPhone, not on a real phone
```

## Honest limits

- **The ghost does not yet follow the real object on the headset.** Marker tracking is proven offline only; both device runs logged `tracked: false` throughout.
- No `step_complete`, wrong-part warning or identify has been logged on the headset. The accuracy number is from clean renders; a real camera, hands and room light are not measured.
- Steps 3 and 7 of the MALM drawer are weak (above), so the person always keeps Next and Back.
- The WebXR client (`client/src/xr/`) exists but is not the demo path: Quest Browser on our headset exposed no camera (`eval/judge/round-0/quest-camera-diag.json`), and WebXR gives no camera pose. It runs as a desktop preview.
- Device numbers are from one short run per app. Guides are IKEA's; PDFs and crops stay out of git except the test guides.

## Run it

```sh
make setup && make env        # then add GEMINI_API_KEY to .env
make dev                      # server on http://localhost:8000
make smoke                    # second terminal: build the golden guide end to end
tools/quest/doctor.sh && tools/quest/install.sh android   # or: unity
tools/quest/run.sh android --job malm-drawer --lang el    # logs to tools/quest/runs/
```

The server URL and demo key are read from `jobs/_host/url.txt` and `.env`; neither is in git. Details: `README.md`, `quest-android/README.md`, `quest-unity/README.md`.

## Team and credits

`TODO(names)`: A, backend and infra · B, agent and prompts · C, CAD and Blender · D, phone client and Quest · E, perception and story. Third-party code, models and guides: `CREDITS.md`. Track: Open Track, hardware Meta Quest 3 (`TODO(confirm on the form)`).

---

## Before submitting (delete this section)

- `TODO(decide after round 2)`: name the demoed app in the line at the top, and replace the device table with the round-2 numbers.
- `TODO(tracking)`: if a run folder shows `tracked: true`, rewrite the first honest limit and the last row of the device table with that run's numbers. If not, leave both as they are.
- `TODO(names)`: team names. `TODO(confirm on the form)`: track name and the form's required fields; they are not recorded in the repo.
- `TODO(model rules)`: the build agent is not a Gemini model (`docs/PLAN.md` D20 and D22 say to check the event's rules). Confirm before submitting.
- `TODO(credits)`: `CREDITS.md` still says `gemini-4-carbon` reads the guide; the code does not call it.
