# Judge procedure (every round)

Follow this in order. The rubric is `eval/judge/RUBRIC.md`, the template `eval/judge/SCORECARD.md`. Round 0 (WebXR baseline) is `eval/judge/round-0-webxr.md`.

**Hard rules for the judge**
- Read the apps; never edit them. Write only under `eval/judge/`. Do not commit unless the human asks.
- Do not build inside a builder's worktree (Unity `Library/`, Gradle `build/` and `.gradle/` would change under a running agent). Build from an exported copy in the scratchpad.
- Never put `GEMINI_API_KEY`, `FAL_KEY`, `BOXD_API_KEY` or the demo key in a scorecard. Write `?key=<DEMO_KEY>`.
- Do not name the brand reference or the 3D scaffolding reference.

## 0. Setup for the round

```sh
R=1                                                     # round number
J=/Users/foivospro/Projects/hackathon/eval/judge/round-$R
S=<scratchpad>/judge-r$R                                 # the session scratchpad, never /tmp
mkdir -p "$J" "$S"
cd /Users/foivospro/Projects/hackathon
git worktree list | tee "$J/worktrees.txt"
```

## 1. Find and read each app

```sh
for w in $(git worktree list --porcelain | awk '/^worktree /{print $2}'); do
  for d in quest-unity quest-android tools/quest docs/QUEST_TESTING.md; do
    [ -e "$w/$d" ] && echo "$w  $d  $(git -C "$w" rev-parse --short HEAD)  dirty=$(git -C "$w" status --porcelain | wc -l)"
  done
done | tee "$J/apps.txt"
```

For each app worktree `W`:
1. `git -C W log --oneline main..HEAD` and `git -C W status --short`: what changed and what is uncommitted. Score **committed HEAD**; list uncommitted files in the scorecard as "not scored".
2. Read, in this order: the app's README, its build doc, `docs/QUEST_TESTING.md` (shared test conventions, AGQ log names), then the code for each criterion (search terms below).
3. Export a clean copy for building: `git -C W archive HEAD quest-unity | tar -x -C "$S/unity"` (or `quest-android`, `tools`).

Where to look per criterion (grep the app tree):

| Criterion | Search for |
|---|---|
| R1 | `identify`, `PassthroughCamera`, `CameraX`, `ImageReader`, JPEG encode, 768 |
| R2 | `manifest`, `walkthrough`, `target_m`, `from_dir`, axis conversion `(x, z, -y)`, glTFast / filament / sceneform |
| R3 | `PnP`, `solvePnP`, `aruco`, `apriltag`, `intrinsics`, `pose`, `Kalman`, `OneEuro`, `track` |
| R4 | `/ws/perception`, `locate`, `step_complete`, `max_unacked`, `ack` |
| R5 | ghost material, arrow, turn arrow, ring, label, `BRAND`, pulse Hz, line wrap |
| R6 | `XRHand`, `OVRHand`, `hand_at_target`, `type: "hands"`, dwell |
| R7 | `/api/tts`, `live/token`, `/ask`, `SpeechRecognizer`, microphone, `el`, `en` |
| R8 | start flow, permission requests, buttons in the first-run path |
| R9 | head-locked parents (`camera.add`, child of `CenterEyeAnchor`), target frame rate, foveation |
| R10 | tests folders, build scripts, AGQ logger, replay camera |
| R11 | `part_in_hand`, `inventory`, highlight in world |

## 2. Build and test (E2)

Save every command's full output to `$J/<app>-<step>.log`, and the time it took.

**WebXR**
```sh
cd /Users/foivospro/Projects/hackathon/client
npm ci && npm run typecheck                 # must print nothing and exit 0
npm run build                               # only from an exported copy, it writes client/dist
```

**Unity** (editor on this Mac: `/Applications/Unity/Hub/Editor/6000.3.28f1`)
```sh
U=/Applications/Unity/Hub/Editor/6000.3.28f1/Unity.app/Contents/MacOS/Unity
$U -batchmode -nographics -quit -projectPath "$S/unity/quest-unity" -runTests -testPlatform EditMode \
   -testResults "$J/unity-editmode.xml" -logFile "$J/unity-tests.log"
$U -batchmode -nographics -quit -projectPath "$S/unity/quest-unity" -buildTarget Android \
   -executeMethod <BuildScript.Method named in the app README> -logFile "$J/unity-build.log"
```
If the README gives no batchmode build method, R10 is at most 1 ("manual steps").

**Android/Kotlin**
```sh
cd "$S/android/quest-android"
./gradlew --no-daemon assembleDebug test lint 2>&1 | tee "$J/android-build.log"
```

**Secrets** (all apps): `git -C W grep -nE "AIza[0-9A-Za-z_-]{20,}|FAL_KEY=|BOXD_API_KEY=" HEAD -- .` must find nothing; also search the built APK (`unzip -p app.apk | strings | grep -E 'AIza'`). A key in an APK is R10 = 0.

## 3. Offline replay checks (E2)

Run what `tools/quest/` provides; if a tool is missing, write "tool missing" in the round notes and score the dependent metric as not measured.

```sh
T=/Users/foivospro/Projects/hackathon/tools/quest              # or the worktree that has it
python3 -I $T/score_track.py --log <replay AGQ log> --truth <ground-truth poses> > "$J/<app>-track.txt"
python3 -I $T/summarize.py <AGQ log>  > "$J/<app>-summary.txt"
python3 -I $T/compare.py <log A> <log B> > "$J/compare.txt"     # same clip, two apps
```

Perception accuracy, independent of the client (the server baseline):
```sh
cat jobs/_verify/bench_sc.txt                      # 83.7 % at 0.45, 0 false completes (MALM renders)
python eval/phase2/p2.py score eval/phase2/labels.json runs/<name>/frames.jsonl runs/<name>/events.jsonl
```
A client earns R4 = 3 only when frames from **its own** encoder and socket code reproduce that accuracy (replay mode sending `jobs/_verify/frames/*.jpg`).

## 4. Device runs (E3, E4)

Needs the Quest 3 on USB with developer mode, `adb devices` showing it. Per app:

```sh
adb install -r <apk>                                                  # native apps
adb shell am start -n <package>/<activity>
adb logcat -c; adb logcat -s AGQ:V Unity:I > "$J/<app>-logcat.txt"   # while the run happens
adb pull <log path from QUEST_TESTING.md> "$J/<app>-agq.jsonl"
adb shell dumpsys gfxinfo <package> > "$J/<app>-gfx.txt"             # frame stats, Android
adb shell "logcat -d -s VrApi" | grep FPS > "$J/<app>-vrapi.txt"      # FPS= lines, both native apps
scrcpy --no-audio --record "$J/<app>-run.mp4"                         # or the headset's own capture, for E4
```

WebXR: open `https://<host>/?mode=xr&job=malm-drawer&key=<DEMO_KEY>&debug=1` in Quest Browser; logs through `chrome://inspect` console export; camera reports land in `jobs/_diag/`.

**Standard device script** (same for every app, same table, same light, the real MALM drawer):
1. Launch, time to first panel. Count every tap, pinch, typed character and permission prompt until the first ghost (R8).
2. Look at the MALM box or guide page: identify (R1), 5 tries.
3. Locate with the drawer at a known step; note `next_step` vs truth (R4).
4. Register or let tracking acquire. Measure ghost offset at 3 actions with a ruler (R5).
5. Move the drawer 20 cm, then turn it 90°: does the ghost follow, in how long (R3). Hold still 3 s (jitter).
6. Stage mistakes: hand at the wrong hole, wrong board, wrong screw (R6, R11), 5 each.
7. Voice: next, back, repeat, a free question, in EN and EL (R7).
8. Five minutes of guidance with camera, tracking and socket on: fps (R9).

## 5. Score

1. Fill `SCORECARD.md` §1 for each app, criterion by criterion, reading the anchors from 5 down.
2. Apply the caps (RUBRIC §3) **after** reading the anchors: device-only without E3 → max 2; partly-device without E3 → max 3; anchor 5 needs E4; a device failure seen in a log overrides code.
3. Mark every capped cell `cap 2, code N` so builders see what device evidence would unlock.
4. Compute totals (`score / 5 × weight`, one decimal), fill §2 and the recommendation.
5. Compare with the previous round; mark regressions.

Quick total check:
```sh
python3 -c "w=[7,7,20,10,12,8,7,8,8,7,6]; s=[...]; print(round(sum(a/5*b for a,b in zip(s,w)),1))"
```

## 6. How to write a fix a builder agent can act on

Every fix has the five parts below, names one criterion, and states the points it would gain. Rank the three by points per hour of work. No vague advice ("improve tracking"): if the judge cannot say how to verify it, it is not a fix yet.

```
**[R3] Marker pose from the passthrough camera** (+8 pts: 1 → 3)
- File: quest-unity/Assets/Scripts/Tracking/MarkerTracker.cs (new); wire in Assets/Scripts/App.cs Start()
- Symptom: no `track` events in round-1 log; ghost stays where the two corners put it after the drawer moved 20 cm.
- Expected: `track` events ≥ 10 Hz with `method:"marker"`, `state:"tracked"` ≥ 70 % of guidance time, ghost follows a 20 cm move within 1 s.
- Verify: `python3 -I tools/quest/summarize.py agq.jsonl` shows track.rate ≥ 10 and tracked ≥ 0.70; replay
  `score_track.py --log replay.jsonl --truth eval/judge/frames/track/truth.json` error ≤ 10 mm / 3°.
```

Rules for fixes:
- **File**: a path that exists (with a line or function) or the exact path of a new file and where it is called from.
- **Symptom**: what was observed, with the number and the artifact file (`round-N/<app>-agq.jsonl` line, test name).
- **Expected**: the rubric anchor's observable condition, in numbers.
- **Verify**: one command or one device-script step, and the output that proves it.
- Device evidence first: when a criterion is capped only for lack of a log, the fix is "produce the log", and it is usually the cheapest points on the board.
