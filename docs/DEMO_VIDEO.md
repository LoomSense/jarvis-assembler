# Demo video script (2:45)

For the person editing the video on Sun 15 Nov, before the 17:30 submission. Voice-over is English. The app speaks and writes Greek, so each shot notes the Greek on screen and its English subtitle. The headset app in every shot is `quest-android`; `quest-unity` is the backup and is not shown. There is no printed marker.

**Rule for every line:** say only what `docs/SUBMISSION.md` backs with a number or a file. Two shots (8 and 9) depend on what the headset does this afternoon; each names what to say if it does not happen. The fallback plan is at the end.

**A rough cut exists.** `jobs/_media/cut/demo-rough.mp4` (2:47) and `demo-60s.mp4` (1:00) are built from this script with `build.sh`, with the English voice-over mixed in and a placeholder card for each headset shot. Drop the recordings into `jobs/_media/cut/headset/` as `H1.mp4` to `H5.mp4` and run `./build.sh` again. Its `README.txt` has the details.

**Where the material is** (all git-ignored or large, in the main checkout, not in a worktree):

- `jobs/_media/`: `segment-build-el.mp4` (28.1 s, 1920×1080), `preview-malm-drawer-el.mp4` and `-en.mp4` (44.9 s), `preview-knagglig-el.mp4` (65.0 s), `segment-build-el-portrait.mp4`, `stills/01`–`06`. Its `README.md` has the step start times for re-cutting.
- `jobs/_media/cut/`: the rough cuts, the shot lists (`shots.txt`, `shots-60s.txt`), the voice-over lines (`vo-lines.txt`, `vo-lines-60s.txt`) and one wav per line in `vo/`.
- `eval/judge/round-2/unity-editor-step1-cam-bolt.png`, `unity-editor-step2.png`: the backup app's real renderer and window, captured in the editor (not on the headset, and not the demo app).
- `eval/judge/round-0/webxr-preview-malm-action.png`: the WebXR desktop preview.
- `docs/design/xr-mockups/a`–`f`: design mockups. They are drawings of the intended look, not the app. If one is used, put "Design mockup" on screen.

**Do not use** `tools/quest/runs/20261115-112229-android-live/shot.png` or the `captures/` clip of the first Android run: the debug HUD in them shows the server address. **Do not use** `stills/01-guide-vs-3d-dowel-el.png`: its Greek caption tells you to screw in a wooden dowel (the previews were rendered before the instruction-text fix, `e32eadd`).

## Shots

| # | Time | On screen | Voice-over (English) | Greek on screen → subtitle | Source |
|---|---|---|---|---|---|
| 1 | 0:00–0:10 (10 s) | The real MALM drawer parts and the paper guide on a table | "This is a furniture guide. Drawings, no words. It cannot tell you which screw you are holding, or where you left off." | none | **Record** (H1) |
| 2 | 0:10–0:30 (20 s) | The guide's own drawing on the left, our 3D step on the right, steps 1 to 5 | "Assembly Guide Agent reads the PDF and rebuilds it. An agent writes the plan, then CAD code for every part. This drawer has 19 parts and 26 actions. It built on the first try in about ten minutes, with every fit check at 0.0 millimetres." | "Ο οδηγός, βήμα 2" → "The guide, step 2". "Βήμα 2 από 7" → "Step 2 of 7". "Θα χρειαστείς" → "You will need" | **Ready**: `segment-build-el.mp4`, the first 20 s, cut on a step change (step starts are in `jobs/_media/README.md`, divided by 1.6). Cover or crop the step title row (it is still in English). Also cover the Greek action caption while it says "screw it in" for the dowel, the cam lock and the plastic pin (6.85–8.70, 10.32–12.20 and 17.02–18.90 s of the segment): same stale text as the dowel still |
| 3 | 0:30–0:38 (8 s) | Two stills, 4 s each, slow zoom towards the arrow | "Every action names the hardware code printed in the guide and points at the hole it goes in." | The Greek action caption on each still | **Ready**: `stills/03-malm-cam-bolt-callout-el.png` (cam bolt 118331), `stills/02-guide-vs-3d-runner-screw-el.png` (screw 100365) |
| 4 | 0:38–0:50 (12 s) | A second product: 8 s of the KNAGGLIG walkthrough, then the finished box for 4 s | "It is not one hand-made model. A different guide, the KNAGGLIG box, was built from its PDF alone in 438 seconds, for about two dollars." | "Βήμα N από 8" → "Step N of 8" | **Ready**: `preview-knagglig-el.mp4` (pick the short-screw step, as in `stills/05-knagglig-screw-108560-el.png`), then `stills/06-knagglig-finished-el.png` |
| 5 | 0:50–1:00 (10 s) | Title card: "Built step" beside "Guide drawing", then the line "`gemini-decision-1` checks both" | "Gemini Decision 1 scores each built step against the guide's own drawing. Then the same model looks at the real thing." | none | **Made** (card in `shots.txt`); background `stills/02-guide-vs-3d-runner-screw-el.png` dimmed |
| 6 | 1:00–1:18 (18 s) | Headset view: the app starts in passthrough, the coach cards, then the first ghost. Speed the wait up to fit | "On a Quest 3, the native app loads the guide and sends one camera frame. About a second and a half later, the server answers which step the real drawer is at." | "Βρίσκω σε ποιο βήμα είσαι…" → "Finding your step…". "Μείνε ακίνητος για λίγο" → "Hold still for a moment" | **Record** (H2). Backed by the logs: locate 1.15 to 1.58 s on five Android launches today |
| 7 | 1:18–1:43 (25 s) | Headset view: the step window and the ghost of the drawer front; a pinch on Next; the cam bolt ghost with its turning arrow | No voice-over while the app speaks. Before it: "One action at a time, shown and spoken." | Spoken: "Βήμα 1, σελίδα 4. Ακούμπησε την πρόσοψη του συρταριού μπροστά σου." → "Step 1, page 4. Lay the drawer front in front of you." Then: "Πάρε μια βίδα έκκεντρου (118331) και βίδωσέ την εδώ. Βίδα 1 από 2." → "Take a cam bolt (118331) and screw it in here. Bolt 1 of 2." Button "Επόμενο" → "Next" | **Record** (H3). Stand-in if the take fails: `eval/judge/round-2/unity-editor-step1-cam-bolt.png`, captioned "Editor capture" (it is the backup app, so say so) |
| 8 | 1:43–2:03 (20 s) | Headset view, no marker anywhere: the hand slides and turns the drawer front; the guide follows it | `TODO(tracking: confirm on headset)` "There is no printed marker. The app finds the white drawer front in the camera picture, three times a second. Slide it or turn it, and the guide follows." **Until a run log shows it, the line is:** "There is no printed marker. The guide is placed on the work surface, and the server's camera answer moves it onto the drawer front. Following the part as it moves is measured on renders only, not yet on the headset." | Only while the guide is not following: "Βάλε το κομμάτι … μέσα στο μπλε περίγραμμα." → "Put the part inside the blue outline." | **Record** (H4), **only if the run log shows it working** (`panel` events with `trusted: true` and `place` events with `source: markerless`). Otherwise use the fallback plan |
| 9 | 2:03–2:18 (15 s) | Headset view: a pinch on "Where am I"; the window answers with the step | "Lost your place? Ask. The camera frame goes to Gemini Decision 1, which compares it with every step of the guide." | "Πού είμαι;" → "Where am I?" | **Record** (H5). If a real `step_complete` is caught on camera in H6, show that instead and say "It saw the step was done and moved on." Do not say it otherwise |
| 10 | 2:18–2:35 (17 s) | Numbers card, white on near-black, headed "What we measured · renders only, no real camera frames": "50 of 55 correct · 0 false 'done'" / "Step check on the MALM drawer (before: 46 of 55 and 1 false 'done')" / "Still marginal: step 3 (cam locks), 0.46 against a 0.45 line" / "Parts for a step, with boxes and counts: 5.1 s median" / "Following the drawer front without a marker: 4.6 mm median on renders, not yet on the headset" | "What we measured. On renders of this drawer, the step check is right in 50 of 55 cases, with no false 'done'. One step is still marginal, so Next and Back stay in your hands. Real camera frames are not measured yet." | none | **Made** (card in `shots.txt`). Numbers: `docs/SUBMISSION.md`, from `server/perception/README.md` and `tools/markerless/FRONT_PANEL.md` |
| 11 | 2:35–2:45 (10 s) | The finished drawer, then the name and the stack: "gemini-decision-1 · gemini-4-hydrogen · Gemini TTS · Claude Agent SDK · CadQuery · Blender · Meta Quest 3" | "Assembly Guide Agent. A paper guide, rebuilt in 3D, and followed on the real thing." | "Γύρισε το συρτάρι στην κανονική του θέση." → "Turn the drawer the right way up." | **Ready**: `stills/04-malm-finished-el.png`. Card made in the editor. List only what the video shows |

In the rough cut now: shots 2, 3, 4, 5, 10 and 11 (77 s) and the voice-over. To record: shot 1 and shots 6 to 9 (88 s).

## Voice-over

The voice is the server's own (`POST /api/tts`, the voice the app speaks with), English, one wav per line in `jobs/_media/cut/vo/`. `python3 vo.py` in the cut folder synthesizes changed lines, checks each against its slot and mixes `voiceover.m4a`; a line that does not fit stops the mix, and the wording is shortened, not the shot. The line for shot 2 was shortened that way, and three lines of the 60 s cut; the table above has the wording as spoken. Shot 8 is mixed with the "until a run log shows it" line; the other line is ready as `vo/voiceover-tracked.m4a`.

Nobody has listened to it yet. Measured: every line ends at least 0.9 s before its slot does in the full cut and 0.29 s in the 60 s cut, no clipped samples, peaks at −2.3 and −1.5 dB in the two files, and the server's speech route wrote every line back word for word ("Knagglig" came back as "Naglig"). The 60 s cut uses shorter lines (`vo-lines-60s.txt`): they only shorten these sentences. Listen once before submitting.

## Headset shots to record (one 5-minute session)

The headset's battery ran out at about 12:45 and it is charging until about 13:30; nothing below has been recorded yet.

Before putting the headset on:

- Launch the Android app **without the debug HUD** (no `--hud`): the HUD shows the server address. Language Greek, job `malm-drawer`.
- Real MALM drawer parts on the table, good light (most runs today got "more light" from the server). No marker strip.
- For shot 8 the drawer front lies flat on the work surface, alone, on a table darker than the panel, with nothing else white of that size next to it. The detector trusts only a clean 380 × 130 mm white rectangle seen from above: a hand over it, a pale table or a grazing view all read as "not trusted" (`tools/markerless/FRONT_PANEL.md`).
- Record with the Quest's built-in recording: Meta button → Camera → Record video. Make a 5 s test first and check that the room shows behind the ghost and the app's voice is in the recording.
- Start the run log at the same time (`tools/quest/run.sh android --job malm-drawer --lang el --secs 300`), so shot 8 can be checked against the log.
- Stay on step 1 (the cam bolts). The job's text for the dowel, the cam lock and the plastic pin (steps 2, 3 and 5) still says "screw it in": see `docs/SUBMISSION.md`, "Before submitting".

Checklist, in order, about 4 minutes of recording:

- [ ] **H1 (15 s).** Before launching the app: look slowly over the parts and the open paper guide. Hold still for 5 s.
- [ ] **H2 (40 s).** Launch. Keep the drawer front in view through the coach cards until the first ghost appears. Do not talk.
- [ ] **H3 (45 s).** Let the first instruction be spoken to the end. Press "Επόμενο" (Next) twice, slowly, until the cam bolt ghost with the turning arrow shows. Let that line be spoken to the end.
- [ ] **H4 (40 s).** Look down at the drawer front, hands off it: hold still 3 s, slide it about 20 cm, hands off, hold 3 s, turn it a quarter turn, hands off, hold 3 s. If the guide does not follow, keep recording until the guide jumps onto the part once (the server's answer, every few seconds): that take is fallback level B.
- [ ] **H5 (20 s).** Press "Πού είμαι;" (Where am I?) and wait for the answer.
- [ ] **H6 (60 s, optional).** Do step 1 for real: screw in both cam bolts with hands in view, then take the hands away and wait 5 s for the app to move on by itself.

The app's hand tracking failed to start in every run today (a fix is in progress). The Next button was pressed once in a logged run; if a pinch does not reach the buttons, try the controller. The two-corner touch needs fingertips, so it is out of the plan.

Outside the headset, if someone is free: 5 s on a phone, over the shoulder, of the person wearing the headset with hands on the parts. Shot 1 can also be filmed this way.

## Fallback plan

Decide after watching the takes and reading the run log (`tools/quest/runs/<run>/agq.jsonl`). Each level changes shots 8 and 10 only, unless it says otherwise. Never show the guide following the object in a take where it did not, and never play the "the guide follows" line over such a take.

**A. The guide follows the part on the headset** (`panel` events with `trusted: true`, `place` events with `source: markerless`, and the take shows it). Shot 8 as written, with the first voice-over line: in `jobs/_media/cut/`, `cp vo/voiceover-tracked.m4a voiceover.m4a`, then `./build.sh`. On the numbers card (shot 10) replace the last line with the device figure from that run. Then resolve `TODO(tracking: confirm on headset)` in `docs/SUBMISSION.md` and `README.md`.

**B. It does not follow, but the server's answer moves the guide onto the part in the take** (`place` with `source: server_box`; logged twice today, at confidence 0.96 and 0.98).
- Shot 8 is that take: the guide appears on the work surface, then jumps onto the real drawer front. Swap the level B line in at the bottom of `shots.txt`.
- Voice-over: the "until a run log shows it" line. It is the one already mixed in.
- Shot 10 stays as it is ("not yet on the headset").

**C. Neither shows in a take.** The app still places the guide at a fixed spot in front of the person.
- Shots 6, 7 and 9 stay (launch, locate, the spoken steps, Where am I).
- Shot 8 becomes 20 s of the desktop preview, screen-recorded to `jobs/_media/cut/headset/H8.mp4`: `cd client && npm run dev`, then `http://localhost:5173/?mode=xr&job=malm-drawer` in desktop Chrome; orbit with the mouse, press `→` for the next action, keys `1`–`4` change the ghost state. Caption "Desktop preview" (the level C line in `shots.txt`). The voice-over already mixed in fits: it claims no following.
- Shot 10 stays as it is.

**D. No usable headset recording at all.**
- Shot 1: film the parts and the guide with a phone.
- Shots 6 to 9 (78 s) become: 40 s of the desktop preview as in C; `eval/judge/round-2/unity-editor-step2.png` and `unity-editor-step1-cam-bolt.png`, 6 s each, captioned "Editor capture of the backup headset app"; then steps 4 to 7 of `preview-malm-drawer-el.mp4` (from 20.0 s to the end, 25 s; cover the caption during the plastic pin action).
- Voice-over for that block (not synthesized yet: add it to `vo-lines.txt` and run `python3 vo.py`): "The headset app ran on a Quest 3 today: it loaded this guide, and the server located the step in about a second and a half. We do not have a clean recording, so this is the same guidance in the desktop preview and in the editor."
- Shot 10 stays as it is. The mockups in `docs/design/xr-mockups/` may fill a gap only with "Design mockup" on screen.

## Before the cut is final

All open items are listed once, in `docs/SUBMISSION.md` under "Before submitting". The two that change the video:

- `TODO(tracking: confirm on headset)`: fallback level A, B, C or D, from the session's run log.
- `TODO(length)`: the form's video limit is not recorded in the repo. `docs/PLAN.md` §11 planned a 1-minute video; if the limit is 60 s, use `demo-60s.mp4`: shots 1 (5 s with the title), 2 (first 12 s), 4 (6 s), 6 (12 s), 7 (12 s), 10 (8 s) and 11 (5 s).
