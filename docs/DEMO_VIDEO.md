# Demo video script (2:45)

For the person editing the video on Sun 15 Nov, before the 17:30 submission. Voice-over is English. The app speaks and writes Greek, so each shot notes the Greek on screen and its English subtitle.

**Rule for every line:** say only what `docs/SUBMISSION.md` backs with a number or a file. Two shots (8 and 9) depend on what the headset does this afternoon; each names what to say if it does not happen. The fallback plan is at the end.

**Where the material is** (all git-ignored or large, in the main checkout, not in a worktree):

- `jobs/_media/`: `segment-build-el.mp4` (28.1 s, 1920×1080), `preview-malm-drawer-el.mp4` and `-en.mp4` (44.9 s), `preview-knagglig-el.mp4` (65.0 s), `segment-build-el-portrait.mp4`, `stills/01`–`06`. Its `README.md` has the step start times for re-cutting.
- `eval/judge/round-2/unity-editor-step1-cam-bolt.png`, `unity-editor-step2.png`: the Unity app's real renderer and window, captured in the editor (not on the headset).
- `eval/judge/round-0/webxr-preview-malm-action.png`: the WebXR desktop preview.
- `docs/design/xr-mockups/a`–`f`: design mockups. They are drawings of the intended look, not the app. If one is used, put "Design mockup" on screen.

**Do not use** `tools/quest/runs/20261115-112229-android-live/shot.png` or the `captures/` clip of the first Android run: the debug HUD in them shows the server address.

## Shots

| # | Time | On screen | Voice-over (English) | Greek on screen → subtitle | Source |
|---|---|---|---|---|---|
| 1 | 0:00–0:10 (10 s) | The real MALM drawer parts and the paper guide on a table | "This is a furniture guide. Drawings, no words. It cannot tell you which screw you are holding, or where you left off." | none | **Record** (H1) |
| 2 | 0:10–0:30 (20 s) | The guide's own drawing on the left, our 3D step on the right, steps 1 to 5 | "Assembly Guide Agent reads the PDF and rebuilds it. An agent writes the plan, then CAD code for every part, and fits them together. This drawer has 19 parts and 26 actions. It built on the first try in about ten minutes, and every fit check came out at 0.0 millimetres." | "Ο οδηγός, βήμα 2" → "The guide, step 2". "Βήμα 2 από 7" → "Step 2 of 7". "Θα χρειαστείς" → "You will need" | **Ready**: `segment-build-el.mp4`, the first 20 s, cut on a step change (step starts are in `jobs/_media/README.md`, divided by 1.6). Cover or crop the step title row (it is still in English) |
| 3 | 0:30–0:38 (8 s) | Two stills, 4 s each, slow zoom towards the arrow | "Every action names the hardware code printed in the guide and points at the hole it goes in." | The Greek action caption on each still | **Ready**: `stills/01-guide-vs-3d-dowel-el.png` (dowel 101345), `stills/02-guide-vs-3d-runner-screw-el.png` (screw 100365) |
| 4 | 0:38–0:50 (12 s) | A second product: 8 s of the KNAGGLIG walkthrough, then the finished box for 4 s | "It is not one hand-made model. A different guide, the KNAGGLIG box, was built from its PDF alone in 438 seconds, for about two dollars." | "Βήμα N από 8" → "Step N of 8" | **Ready**: `preview-knagglig-el.mp4` (pick the short-screw step, as in `stills/05-knagglig-screw-108560-el.png`), then `stills/06-knagglig-finished-el.png` |
| 5 | 0:50–1:00 (10 s) | Title card: "Built step" beside "Guide drawing", then the line "`gemini-decision-1` checks both" | "Gemini Decision 1 scores each built step against the guide's own drawing. Then the same model looks at the real thing." | none | **Make in the editor**; background `stills/01-guide-vs-3d-dowel-el.png` dimmed |
| 6 | 1:00–1:18 (18 s) | Headset view: the app starts in passthrough, the coach cards, then the first ghost. Speed the wait up to fit | "On a Quest 3, the native app loads the guide and sends one camera frame. About a second and a half later, the server answers which step the real drawer is at." | "Βρίσκω σε ποιο βήμα είσαι…" → "Finding your step…". "Μείνε ακίνητος για λίγο" → "Hold still for a moment" | **Record** (H2). Backed by the logs: locate 1.51 s and 1.55 s |
| 7 | 1:18–1:43 (25 s) | Headset view: the step window and the ghost of the drawer front; a pinch on Next; the cam bolt ghost with its turning arrow | No voice-over while the app speaks. Before it: "One action at a time, shown and spoken." | Spoken: "Βήμα 1, σελίδα 4. Ακούμπησε την πρόσοψη του συρταριού μπροστά σου." → "Step 1, page 4. Lay the drawer front in front of you." Then: "Πάρε μια βίδα έκκεντρου (118331) και βίδωσέ την εδώ. Βίδα 1 από 2." → "Take a cam bolt (118331) and screw it in here. Bolt 1 of 2." Button "Επόμενο" → "Next" | **Record** (H3). Stand-in if the take fails: `eval/judge/round-2/unity-editor-step1-cam-bolt.png`, captioned "Editor capture" |
| 8 | 1:43–2:03 (20 s) | Headset view: the printed marker on the drawer front; the hand slides and turns the drawer front; the ghost stays on it | "A printed marker on the drawer front tells the app where the drawer is, so the guide stays on the real part when you move it." | "Κλείδωσε." → "Locked on." | **Record** (H4), **only if the run log shows `tracked: true`**. Otherwise use the fallback plan |
| 9 | 2:03–2:18 (15 s) | Headset view: a pinch on "Where am I"; the window answers with the step | "Lost your place? Ask. The camera frame goes to Gemini Decision 1, which compares it with every step of the guide." | "Πού είμαι" → "Where am I" | **Record** (H5). If a real `step_complete` is caught on camera in H6, show that instead and say "It saw the step was done and moved on." Do not say it otherwise |
| 10 | 2:18–2:35 (17 s) | Numbers card, white on near-black: "83.7 % correct · 0 false 'done' · 55 calls, clean renders" / "Weak: step 3 (cam locks), step 7 (runner screws)" / "Tracking: under 2 mm median, offline replay" | "What we measured. On clean renders the step check is right 83.7 percent of the time, with no false 'done'. Two steps are weak, so Next and Back always stay in your hands. Real hands and room light are not measured yet." | none | **Make in the editor**. Numbers: `docs/HANDOFF.md` §1 |
| 11 | 2:35–2:45 (10 s) | The finished drawer, then the name and the stack: "gemini-decision-1 · gemini-4-hydrogen · Gemini TTS · Claude Agent SDK · CadQuery · Blender · Meta Quest 3" | "Assembly Guide Agent. A paper guide, rebuilt in 3D, and followed on the real thing." | "Γύρισε το συρτάρι στην κανονική του θέση." → "Turn the drawer the right way up." | **Ready**: `stills/04-malm-finished-el.png`. Card made in the editor. List only what the video shows |

Ready now: shots 2, 3, 4 and 11 (50 s). To make in the editor: shots 5 and 10 (27 s). To record: shot 1 and shots 6 to 9 (88 s).

## Headset shots to record (one 5-minute session)

Before putting the headset on:

- Launch the app chosen for the demo **without the debug HUD** (no `--hud`): the HUD shows the server address. Language Greek, job `malm-drawer`.
- Real MALM drawer parts on the table, good light (both morning runs got "more light" from the server), the printed marker strip on the drawer front.
- Record with the Quest's built-in recording: Meta button → Camera → Record video. Make a 5 s test first and check that the room shows behind the ghost and the app's voice is in the recording.
- Start the run log at the same time (`tools/quest/run.sh <app> --job malm-drawer --lang el --secs 300`), so shot 8 can be checked against `tracked: true`.

Checklist, in order, about 4 minutes of recording:

- [ ] **H1 (15 s).** Before launching the app: look slowly over the parts and the open paper guide. Hold still for 5 s.
- [ ] **H2 (40 s).** Launch. Keep the drawer front in view through the coach cards until the first ghost appears. Do not talk.
- [ ] **H3 (45 s).** Let the first instruction be spoken to the end. Pinch "Επόμενο" (Next) twice, slowly, until the cam bolt ghost with the turning arrow shows. Let that line be spoken to the end.
- [ ] **H4 (40 s).** With the marker in view: hold still 3 s, slide the drawer front about 20 cm, hold 3 s, turn it a quarter turn, hold 3 s. Skip if the ghost does not follow.
- [ ] **H5 (20 s).** Pinch "Πού είμαι" (Where am I) and wait for the answer.
- [ ] **H6 (60 s, optional).** Do step 1 for real: screw in both cam bolts with hands in view, then take the hands away and wait 5 s for the app to move on by itself.
- [ ] **H7 (20 s, fallback material).** Relaunch with the marker covered. During the "Μείνε ακίνητος για λίγο" card, touch the front-left and then the front-right bottom corner of the drawer front with the index fingertip and hold each for a second. The two-corner touch is in the code of both apps but has not been tried on the headset: keep the take only if the ghost visibly snaps to the corners.

Outside the headset, if someone is free: 5 s on a phone, over the shoulder, of the person wearing the headset with hands on the parts. Shot 1 can also be filmed this way.

## Fallback plan

Decide after watching the takes. Each level changes shots 8 and 9 only, unless it says otherwise. Never show the ghost following the object in a take where it did not.

**A. Marker tracking works on the headset** (the run folder in `tools/quest/runs/` shows `tracked: true`). Use the script as written. On the numbers card (shot 10) add the device figure from that run's `summary.md`.

**B. No marker tracking, but the two-corner touch works (H7).**
- Shot 8 becomes H7: the fingertip touches two corners and the ghost snaps onto the drawer front. Voice-over: "Touch two corners of the real part, and the guide snaps onto it. If you move the part, touch again."
- Shot 10: change the tracking line to "Marker tracking: under 2 mm median, offline replay; not yet on the headset" and add to the voice-over: "Tracking by marker is measured offline only."

**C. Neither works on the headset.** The app still places the ghost at a fixed spot in front of the person, as in this morning's run.
- Shots 6, 7 and 9 stay (launch, locate, the spoken steps, Where am I).
- Shot 8 becomes 20 s of the desktop preview, screen-recorded: `cd client && npm run dev`, then `http://localhost:5173/?mode=xr&job=malm-drawer` in desktop Chrome; orbit with the mouse, press `→` for the next action, keys `1`–`4` change the ghost state. Caption "Desktop preview". Voice-over: "The same guidance in a desktop preview. On the headset the guide is placed in front of you; following the real part is measured offline, not yet on the device."
- Shot 10 as in B.

**D. No usable headset recording at all.**
- Shot 1: film the parts and the guide with a phone.
- Shots 6 to 9 (78 s) become: 40 s of the desktop preview as in C; `eval/judge/round-2/unity-editor-step2.png` and `unity-editor-step1-cam-bolt.png`, 6 s each, captioned "Editor capture of the headset app"; then steps 4 to 7 of `preview-malm-drawer-el.mp4` (from 20.0 s to the end, 25 s).
- Voice-over for that block: "Both headset apps ran on a Quest 3 this morning: they loaded this guide and the server located the step in about a second and a half. We do not have a clean recording, so this is the same guidance in the desktop preview and in the editor."
- Shot 10 as in B. The mockups in `docs/design/xr-mockups/` may fill a gap only with "Design mockup" on screen.

## Before the cut is final

- `TODO(decide after round 2)`: which app is recorded (`quest-android` or `quest-unity`); record all headset shots with that one app.
- `TODO(tracking)`: fallback level A, B, C or D, from the session's run log.
- `TODO(length)`: the form's video limit is not recorded in the repo. `docs/PLAN.md` §11 planned a 1-minute video; if the limit is 60 s, keep shots 1 (5 s), 2 (first 12 s), 4 (6 s), 6 (12 s), 7 (12 s), 10 (8 s) and 11 (5 s).
