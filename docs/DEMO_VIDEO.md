# Demo video script (2:42)

For the person recording and editing the video on Sun 15 Nov, before the 17:30 submission. Brought up to date at 16:10 for the builds of 15:54 and 16:00 and for what the headset logged between 14:58 and 16:09. Corrected at 16:35 against the logs, which ended at 16:31 when read. Voice-over is English. The app speaks and writes Greek, so each headset shot notes the Greek and its English subtitle. The headset app in every shot is `quest-android`. There is no printed marker.

**Rule for every line:** say only what `docs/SUBMISSION.md` backs with a log event or a file.

**State of the cut.** `jobs/_media/cut/demo-rough.mp4` (2:42) and `demo-60s.mp4` (1:00) are built with the voice-over and a placeholder card for each headset shot. Three headset recordings are in `jobs/_media/cut/headset/raw/` (61 s, 191 s and 153 s, pulled between 15:36 and 15:58), but none is mapped to a shot, and by their file names they were recorded at 14:35, 14:43 and 14:55, before the 14:58 run, so they show older builds. One new recording session fills the placeholders: [Recording session](#recording-session-one-take-about-6-minutes). Voice-over line 10 and its card are out of date: [Voice-over](#voice-over).

**How the headset shots are recorded.** With the headset's own recorder: Meta button, Camera, Record video. The app stays open behind the menu and goes on when the menu closes. Stop the recording the same way. The headset cannot be screen-recorded from the Mac while it is worn, so there is no other way to get these shots.

**Where the material is** (git-ignored, in the main checkout, not in a worktree):

- `jobs/_media/cut/`: both cuts, the shot lists (`shots.txt`, `shots-60s.txt`), the voice-over lines (`vo-lines.txt`, `vo-lines-60s.txt`), one wav per line in `vo/`, `build.sh`, and its `README.txt`.
- `jobs/_media/`: `segment-build-el.mp4`, `preview-knagglig-el.mp4`, `stills/`, with `stills/07-drawer-parts-photo.jpg` (phone photo of the real parts) and `stills/08-headset-capture-1350.jpg` (headset capture: the side panel's ghost on the real front, from the 13:46 run on the earlier guide).
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
| 6 | 1:02–1:20 (18 s) | Headset: launch, the boards lit on the floor with the face that is up on the label, the ghost snaps onto the front (sped up to fit) | "On a Quest 3, with no printed marker. The headset finds each board on the floor by its measured size, lights up the one to take, and snaps the guide onto the real drawer front." | "Βήμα 1, σελίδα 2. Πάρε: Πρόσοψη του συρταριού." → "Step 1, page 2. Take: drawer front." When the app starts at step 2: "Βήμα 2, σελίδα 3. Πάρε: Βίδα έκκεντρου." → "Step 2, page 3. Take: cam bolt." | **Record** (H2). Logs: `flat_parts` (`lit`, `cue`), `place … markerless` |
| 7 | 1:20–1:45 (25 s) | Headset: the cam bolts, each confirmed aloud, from the take marker to the next bolt | "Take a cam bolt. The app checks it is the right part, shows the hole, and moves on by itself." Then the app speaks | "Σωστή βίδα! Βίδωσέ τη στην τρύπα που φωτίζεται." → "Right bolt! Screw it into the lit hole." "Μπράβο! Πάμε στην επόμενη, 2 από 4." → "Well done! On to the next, 2 of 4." | **Record** (H3). Logs: `perception … right_hardware`, `coach_phase` |
| 8 | 1:45–2:00 (15 s) | Headset: the real front is slid and turned, the guide follows | "Slide the front, or turn it, and the guide follows. The detector runs on the headset, three times a second, and reads which way round the board lies from its groove." | none | **Record** (H4). Only if the take shows it; else the fallback |
| 9 | 2:00–2:15 (15 s) | Headset: the outline on the right board, the side panel's ghost standing on the front | "Next, a side panel. Its outline lights up on the right board, and its ghost stands where it goes on the real front." | "Σωστό κομμάτι! Κατέβασέ το πάνω στις δύο βίδες της αριστερής άκρης." → "Right piece! Lower it onto the two bolts at the left end." | **Record** (H5). Logs: `held_verdict right`, `flat_parts.lit` |
| 9b | optional (10 s) | Headset: a wrong item held up, the app says so | none (the app speaks) | "Λάθος κομμάτι. Πάρε τη βίδα έκκεντρου." → "Wrong piece. Take the cam bolt." | **Record** (H6), only if the run log has `held_verdict … wrong` and this `voice` line at that moment. Commented line in `shots.txt` |
| 9c | optional | Headset: the bottom, then the back, each confirmed in the hand | none (the app speaks) | "Σωστό κομμάτι!" → "Right piece!" | **Record** (H7, H8). Not in `shots.txt` yet: a shot needs its own line there before `build.sh` uses it (`README.txt` in the cut folder) |
| 10 | 2:15–2:32 (17 s) | Card "What the headset logged today": the numbers of `docs/SUBMISSION.md`, and what is not proven | Out of date, see [Voice-over](#voice-over) | none | Made (card), to be changed |
| 11 | 2:32–2:42 (10 s) | The finished drawer as the guide builds it, then the name and the stack | "Assembly Guide Agent. A paper guide, rebuilt in 3D, and followed on the real thing." | none | Ready: `jobs/drawer/renders/cover.png` |

The 60 s cut (`shots-60s.txt`): title 2 s, shots 1 (3 s), 2 (8 s), 3 (4 s), 6 (10 s), 7 (11 s), 8 (8 s), 9 (4 s), 10 (6 s), 11 (4 s), with shorter lines (`vo-lines-60s.txt`). It uses the first seconds of the same mapped clips, so start each mapped span on the action.

## Voice-over

The voice is the server's own (`POST /api/tts`), one wav per line in `jobs/_media/cut/vo/`. `python3 vo.py` there synthesizes changed lines, checks each against its slot and mixes; a line that does not fit stops the mix and the wording is shortened, not the shot. Nobody has listened to it: listen once before submitting.

**Line 10 must change.** It still says: "Not proven yet: a part the wrong way round, a wrong small part, and steps four to six." Since 14:58 the log has steps 2 to 5 and the back board of step 6 without the Next button (the wearer did press re-align and "check this part"), each of the four bolts confirmed, and a wrong item reported. The 16:21 session also reached the finish, with four presses on Next at the cam locks and the last two pegs not confirmed. Wording that the log backs (shorten it if `vo.py` says it does not fit its 17 s):

> "Logged on the headset today: boards found by size, the guide locked on the real front, each cam bolt confirmed, a wrong item called out, and the build coached from the bolts to the back board without pressing Next. Not proven yet: which way round a side panel goes, and a whole build without pressing Next."

Change the same words on the card of shot 10 and in line 10 of `vo-lines-60s.txt`, then run `python3 vo.py` and `./build.sh`.

## Recording session (one take, about 6 minutes)

One recording from H1 to the end, with the headset's own recorder. Two people: the wearer, and one at the Mac.

Before the headset goes on:

- **Out of direct sunlight.** In the sun on the grey carpet the boards were not recognised (14:58 run: 67 of 456 passes labelled any board). Out of the sun it worked (15:21).
- The five boards flat on a floor darker than they are, 3 cm or more between them, the front with its groove side up. The four cam bolts together on the floor, not on a board, and the four white pegs in a separate group. Screwdriver within reach. Controllers put away: the app needs tracked hands.
- At the Mac, type but do not start: `tools/quest/run.sh android --job drawer --lang el --secs 600 --keep-running` (never `--hud`).

In order, with what the wearer does:

- [ ] **H1, 20 s.** Meta button, Camera, Record video, in passthrough, before the app starts. Stand over the parts, look slowly from the front across the sides, the bottom and the back to the bolts. Hold still 5 s. Say "go": the Mac starts the app.
- [ ] **H2, about 45 s.** Look down at the boards with the front in the middle of the view, hands away, no talking. The ghost snaps onto the front: 6 s after the first step at 14:58 and 10 s at 16:00, but 28 s and 42 s in two sessions between, after presses on re-align. Hold still 3 s after the snap. The app chooses the step it starts at from what it sees: with the front on the floor it began at step 2 in four sessions and at step 1 in two (up to 16:09; over all ten sessions from 14:58 to 16:21 it began at step 2 five times and at step 1 five times). At step 1, leave the front where it is: the step ends by itself. From a session start this was seen twice, after 12 s and 14 s (16:11:17, 16:21:59). Do not press Back to see it again: the ten endings logged from 15:56 to 16:07 all followed a press on Back, and since the 16:10 build the step no longer ends by itself after Back.
- [ ] **H3, about 90 s: the four bolts.** The take marker is on the bolts. Pick up one bolt and hold it still in view until the app says "Σωστή βίδα!" (5 to 20 s after the take prompt at 15:56 to 15:57). Look at the ring on the hole, screw the bolt in, take both hands away. The app says "Μπράβο! Πάμε στην επόμενη, 2 από 4." Do the same for each bolt: all four were confirmed at 15:22 to 15:24, three of four at 15:56 to 15:57.
- [ ] **H6, 20 s: a wrong item.** When the app asks for bolt 3 or 4, pick up a white peg instead and hold it still in view. The app says "Λάθος κομμάτι. Πάρε τη βίδα έκκεντρου." (8 s after the action began at 15:57 and 10 s at 16:23; at 15:23 the verdict came after 6 s, but the older sentence was spoken, 10 s after the action began). Put the peg back and take the bolt.
- [ ] **H4, 40 s.** With the ring for a bolt showing: hands off 3 s, slide the front about 20 cm, hands off 3 s, turn it a quarter turn, hands off 3 s. Keep the whole front in view.
- [ ] **H5, 45 s: a side panel.** The app asks for the left side panel. Look at the boards for 3 s: the outline is on a side board, and the label should name the face that is up (the log holds that reading, not the label: check it in the take). Pick it up, hold it in view until "Σωστό κομμάτι!" (8 s, 8 s and 59 s after the step began at 15:00, 15:24 and 15:58), look back at the front for 3 s, lower the panel onto the two bolts, hands away.
- [ ] Not in the cut: the two cam locks of each side, and the right side panel. At a cam lock the app no longer moves on while its instruction is read (15:59).
- [ ] **H7, 30 s: the bottom.** Pick it up, hold it in view until "Σωστό κομμάτι!" (15 s at 15:02), slide it into the grooves, hands away.
- [ ] **H8, 30 s: the back.** The same (21 s at 15:02). Then stop the recording: Meta button, Camera, stop. The pegs and the finish ran once on the headset (16:26 to 16:28: the first two pegs were confirmed, the last two were not, then "Το συρτάρι είναι έτοιμο."): record them only as an extra.

Afterwards, at the Mac (the headset can stay on the wearer; it only has to answer on adb, as it does for the run log):

```sh
tools/quest/pull_recordings.sh                 # lists today's recordings on the headset and what is already pulled
tools/quest/pull_recordings.sh pull            # the newest one into jobs/_media/cut/headset/raw/, with duration, size and frame rate
tools/quest/pull_recordings.sh pull 3          # or the newest three of today
open jobs/_media/cut/headset/raw/<file>.mp4    # note where each shot starts and ends
tools/quest/pull_recordings.sh map H1 raw/<file>.mp4 0:05 0:15
tools/quest/pull_recordings.sh map H2 raw/<file>.mp4 0:22 0:52     # launch to 3 s after the snap; sped up to 18 s
tools/quest/pull_recordings.sh map H3 raw/<file>.mp4 1:30 1:55     # 25 s that hold one confirmation and end on the next marker
tools/quest/pull_recordings.sh map H4 raw/<file>.mp4 3:10 3:25
tools/quest/pull_recordings.sh map H5 raw/<file>.mp4 4:00 4:15
tools/quest/pull_recordings.sh map --show      # what is mapped
tools/quest/pull_recordings.sh durations       # durations of the raw files and of each H<n>.mp4
cd jobs/_media/cut && ./build.sh               # both videos; ./build.sh rough or ./build.sh 60s builds one
```

The times above are examples. `map` takes a slot from H1 to H8, cuts the span into `headset/H<n>.mp4` exact to the frame and notes it in `headset/map.txt`; calling it again for a shot replaces it, and `map` alone re-applies every line. Times are seconds (12.5) or m:ss (0:12.5). A mapped clip is already trimmed, so leave "in" at 0 in `shots.txt`. The cut folder is the main checkout's; `AGJ_CUT_DIR` overrides it. `build.sh` needs ffmpeg and ImageMagick.

Then check the take against the run log (`tools/quest/runs/<run>/agq.jsonl`): shot 7 needs `perception` events with `event: right_hardware` in the span, shot 9b a `held_verdict` with `verdict: wrong` and the `voice` line, shot 8 `panel` events with `trusted: true` and `track` events with `source: markerless` while the front was moved.

## Fallback plan

Never play a line over a take that does not show what the line says.

- **The take does not show the guide following the front** (shot 8). Screen-record 15 s of the desktop preview to `jobs/_media/cut/headset/H8.mp4` (`cd client && npm run dev`, then `?mode=xr&job=drawer` in desktop Chrome; not tried with this job, `malm-drawer` is known to load), swap in the commented shot 8 line in `shots.txt`, run `python3 vo.py nofollow` and `./build.sh`. The line then says that following was logged today but is not in this recording. This uses the H8 slot: do not map the back board to H8 in that case.
- **No H1.** Swap in the commented shot 1 line: the phone photo of the parts, labelled "Phone photo".
- **No H5.** Swap in the commented shot 9 line: the 13:50 headset capture, labelled as such. It is from the earlier guide rebuilt from the same measurements, on the same real front.
- **No usable recording at all.** Keep shots 1 to 5, 10 and 11, use the phone photo for shot 1, the 13:50 capture for shot 9, the desktop preview for shots 6 to 8, and replace lines 6 to 8 in `vo-lines.txt` with: "The headset app ran on a Quest 3 today, and its log is in the submission. We do not have a clean recording, so this is the same guidance in the desktop preview." Then `python3 vo.py` and `./build.sh`.

## Before the cut is final

All open items are listed once, in `docs/SUBMISSION.md` under "Before submitting". The ones that change the video: the recording session above, line 10 and its card, `TEAM NAMES` on the end card in both shot lists, and `TODO(length)`: use `demo-60s.mp4` if the form's limit is one minute.
