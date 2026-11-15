# Demo video script (2:42)

For the person recording and editing the video on Sun 15 Nov, before the 17:30 submission. Rewritten at 14:55 for the custom drawer guide (`drawer`) and for what the app does now. Voice-over is English. The app speaks and writes Greek, so each headset shot notes the Greek and its English subtitle. The headset app in every shot is `quest-android`. There is no printed marker.

**Rule for every line:** say only what `docs/SUBMISSION.md` backs with a log event or a file.

**The cut is one command away from final.** `jobs/_media/cut/demo-rough.mp4` (2:42) and `demo-60s.mp4` (1:00) are built with the voice-over and a placeholder card for each of the five headset shots. One recording session of about five minutes fills them: [Recording session](#recording-session-one-take-about-5-minutes).

**Where the material is** (git-ignored, in the main checkout, not in a worktree):

- `jobs/_media/cut/`: both cuts, the shot lists (`shots.txt`, `shots-60s.txt`), the voice-over lines (`vo-lines.txt`, `vo-lines-60s.txt`), one wav per line in `vo/`, `build.sh`, and its `README.txt`.
- `jobs/_media/`: `segment-build-el.mp4`, `preview-knagglig-el.mp4`, `stills/`. New today: `stills/07-drawer-parts-photo.jpg` (phone photo of the real parts) and `stills/08-headset-capture-1350.jpg` (headset capture: the side panel's ghost on the real front, from the 13:46 run on the earlier guide).
- `jobs/drawer/renders/`: `step_01.png` to `step_06.png` and `cover.png` of the custom guide.

**Do not use** a recording made with the debug HUD (`--hud`): it shows the server address. Watch every headset clip once for an address or a key before it goes in. **Do not use** `stills/01-guide-vs-3d-dowel-el.png` (stale caption).

## Shots

| # | Time | On screen | Voice-over (English) | Greek → subtitle | Source |
|---|---|---|---|---|---|
| 1 | 0:03–0:13 (10 s) | The real drawer parts laid out on the floor | "Flat-pack furniture: boards, a bag of hardware, and a paper guide that cannot tell you which part you are holding, or where you left off." | none | **Record** (H1) |
| 2 | 0:13–0:32 (19 s) | A paper guide's drawing on the left, our 3D step on the right | "Assembly Guide Agent reads the PDF and rebuilds it. An agent writes the plan, then CAD code for every part. This drawer has 19 parts and 26 actions. It built on the first try in about ten minutes, with every fit check at 0.0 millimetres." | "Ο οδηγός, βήμα N" → "The guide, step N" | Ready: `segment-build-el.mp4` (the IKEA drawer guide) |
| 3 | 0:32–0:40 (8 s) | The phone photo of our parts, then step 2 of the custom guide | "Our own drawer had no guide. So we measured it and built one: six steps, seventeen actions." | none | Ready: `stills/07`, `jobs/drawer/renders/step_02.png` |
| 4 | 0:40–0:52 (12 s) | A second product, the KNAGGLIG box | "It is not one hand-made model. A different guide, the Knagglig box, was built from its PDF alone in 438 seconds, for about two dollars." | "Βήμα N από 8" → "Step N of 8" | Ready |
| 5 | 0:52–1:02 (10 s) | Card: "Guide drawing · Built step", "`gemini-decision-1` checks both" | "Gemini Decision 1 scores each built step against the guide's own drawing. Then the same model looks at the real thing." | none | Made (card) |
| 6 | 1:02–1:20 (18 s) | Headset: launch, the lit outline on the real front, the ghost snaps onto it (sped up to fit) | "On a Quest 3, with no printed marker. The headset finds each board on the floor by its measured size, lights up the one to take, and snaps the guide onto the real drawer front." | "Πάρε: Πρόσοψη του συρταριού" → "Take: drawer front" | **Record** (H2). Logs: `flat_parts`, `place … markerless` |
| 7 | 1:20–1:45 (25 s) | Headset: one cam bolt, from the take marker to the next bolt | "Take a cam bolt. The app checks it is the right part, shows the hole, and moves on by itself." Then the app speaks | "Σωστό. Βίδα έκκεντρου." → "Right. Cam bolt." "Βάλ' το εδώ" → "Put it here". "Βίδα 1 από 4" → "Bolt 1 of 4" | **Record** (H3). Logs: `held_verdict right`, `coach_phase` |
| 8 | 1:45–2:00 (15 s) | Headset: the real front is slid and turned, the guide follows | "Slide the front, or turn it, and the guide follows. The detector runs on the headset, three times a second, and reads which way round the board lies from its groove." | none | **Record** (H4). Only if the take shows it; else the fallback |
| 9 | 2:00–2:15 (15 s) | Headset: the outline on the right board, the side panel's ghost standing on the front | "Next, a side panel. Its outline lights up on the right board, and its ghost stands where it goes on the real front." | "Αριστερό πλαϊνό" → "Left side panel" | **Record** (H5) |
| 9b | optional (10 s) | Headset: the wrong board in the hand, the app says so | none (the app speaks) | "Λάθος εξάρτημα" → "Wrong part" | **Record** (H6), only if the run log has `held_verdict … wrong` at that moment. Commented line in `shots.txt` |
| 10 | 2:15–2:32 (17 s) | Card "What the headset logged today": the numbers of `docs/SUBMISSION.md`, and what is not proven | "Logged on the headset today: boards found by size, the guide locked on the real front, four cam bolts and a side panel coached with no button press. Not proven yet: a part the wrong way round, a wrong small part, and steps four to six." | none | Made (card) |
| 11 | 2:32–2:42 (10 s) | The finished drawer as the guide builds it, then the name and the stack | "Assembly Guide Agent. A paper guide, rebuilt in 3D, and followed on the real thing." | none | Ready: `jobs/drawer/renders/cover.png` |

The 60 s cut (`shots-60s.txt`): title 2 s, shots 1 (3 s), 2 (8 s), 3 (4 s), 6 (10 s), 7 (11 s), 8 (8 s), 9 (4 s), 10 (6 s), 11 (4 s), with shorter lines (`vo-lines-60s.txt`). It uses the first seconds of the same mapped clips, so start each mapped span on the action.

## Voice-over

The voice is the server's own (`POST /api/tts`), one wav per line in `jobs/_media/cut/vo/`. `python3 vo.py` there synthesizes changed lines, checks each against its slot and mixes; a line that does not fit stops the mix and the wording is shortened, not the shot. Synthesized again today: lines 1, 3, 6, 7, 8, 9 and 10 of the full cut and all nine lines of the 60 s cut. Measured: no line overruns; the tightest ends 0.78 s before its slot in the full cut (line 1) and 0.36 s in the 60 s cut (line 2); no clipped samples. Nobody has listened to it: listen once before submitting.

## Recording session (one take, about 5 minutes)

Record with the Quest's built-in recording (Meta button → Camera → Record video), one recording from H1 to the end. Two people: the wearer, and one at the Mac.

Before the headset goes on:

- The five boards flat on a floor darker than they are, 3 cm or more between them, the front with its groove side up. The four cam bolts together on the floor, not on a board. Screwdriver within reach. Controllers put away: the app needs tracked hands.
- At the Mac, type but do not start: `tools/quest/run.sh android --job drawer --lang el --secs 600 --keep-running` (never `--hud`).

In order, with what the wearer does:

- [ ] **H1, 20 s.** Start the recording, in passthrough, before the app starts. Stand over the parts, look slowly from the front across the sides, the bottom and the back to the bolts. Hold still 5 s. Say "go": the Mac starts the app.
- [ ] **H2, about 45 s.** Look down at the boards with the front in the middle of the view, hands away, no talking. The outline lights up on the front; then the blue ghost snaps onto it (10 to 12 s after the step appeared in the last two runs). Hold still 3 s after the snap. Leave the front where it is: the app says step 1 is done and asks for a cam bolt (press Next once if it has not after 10 s).
- [ ] **H3, about 60 s.** The take marker is on the bolts. Pick up one bolt, hold it still about 30 cm from your face until the app says "Σωστό" (23 s and 64 s in the last two runs; press Next if it takes longer). Look at the ring on the hole, screw the bolt in, take both hands away. Wait until the marker for bolt 2 appears.
- [ ] **H4, 40 s.** With the ring for bolt 2 showing: hands off 3 s, slide the front about 20 cm, hands off 3 s, turn it a quarter turn, hands off 3 s. Keep the whole front in view.
- [ ] Not in the cut, about 60 s: screw in bolts 2 to 4, hands away after each.
- [ ] **H5, 45 s.** The app asks for the left side panel. Look at the boards for 3 s: the outline is on a side board. Pick it up, look back at the front for 3 s: the ghost of the side panel stands on its left end. Lower the panel onto the two bolts, hands away.
- [ ] **H6, 30 s, optional.** Press Back once, pick up the back board instead, hold it in view. Stop after 20 s if the app says nothing. Then stop the recording.

Afterwards, at the Mac (the wearer can keep the headset on; it only has to be on USB):

```sh
tools/quest/pull_recordings.sh                 # lists today's recordings on the headset
tools/quest/pull_recordings.sh pull            # the newest one into jobs/_media/cut/headset/raw/, with its duration
open jobs/_media/cut/headset/raw/<file>.mp4    # note where each shot starts and ends
tools/quest/pull_recordings.sh map H1 raw/<file>.mp4 0:05 0:15
tools/quest/pull_recordings.sh map H2 raw/<file>.mp4 0:22 0:52     # launch to 3 s after the snap; sped up to 18 s
tools/quest/pull_recordings.sh map H3 raw/<file>.mp4 1:30 1:55     # 25 s that end on the marker for bolt 2
tools/quest/pull_recordings.sh map H4 raw/<file>.mp4 2:18 2:33
tools/quest/pull_recordings.sh map H5 raw/<file>.mp4 4:00 4:15
cd jobs/_media/cut && ./build.sh               # both videos, about two minutes
```

The times above are examples. `map` cuts the span into `headset/H<n>.mp4` and remembers it in `headset/map.txt`; calling it again for a shot replaces it, and `map` alone re-applies every line. Then check the take against the run log (`tools/quest/runs/<run>/agq.jsonl`): shot 8 needs `panel` events with `trusted: true` and `track` events with `source: markerless` while the front was moved.

## Fallback plan

Never play a line over a take that does not show what the line says.

- **The take does not show the guide following the front** (shot 8). Screen-record 15 s of the desktop preview to `jobs/_media/cut/headset/H8.mp4` (`cd client && npm run dev`, then `?mode=xr&job=drawer`), swap in the commented shot 8 line in `shots.txt`, run `python3 vo.py nofollow` and `./build.sh`. The line then says that following was logged today but is not in this recording.
- **No H1.** Swap in the commented shot 1 line: the phone photo of the parts, labelled "Phone photo".
- **No H5.** Swap in the commented shot 9 line: the 13:50 headset capture, labelled as such. It is from the earlier guide rebuilt from the same measurements, on the same real front.
- **No usable recording at all.** Keep shots 1 to 5, 10 and 11, use the phone photo for shot 1, the 13:50 capture for shot 9, the desktop preview for shots 6 to 8, and replace lines 6 to 8 in `vo-lines.txt` with: "The headset app ran on a Quest 3 today, and its log is in the submission. We do not have a clean recording, so this is the same guidance in the desktop preview." Then `python3 vo.py` and `./build.sh`.

## Before the cut is final

All open items are listed once, in `docs/SUBMISSION.md` under "Before submitting". The ones that change the video: the recording session above, `TEAM NAMES` on the end card in both shot lists, and `TODO(length)`: use `demo-60s.mp4` if the form's limit is one minute.
