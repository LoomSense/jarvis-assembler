# Handoff: where the project stands

Status as of **Sun 15 Nov 2026, 13:05 Athens**. For the team and their coding agents. Deadlines today (Athens): feature freeze 17:00, submission 17:30, code freeze 18:00. Numbers were measured today unless marked UNVERIFIED.

**In short:** the demo is the native Android app (`quest-android/`), with no printed marker. On the Quest 3 it shows passthrough, reads the live camera with its pose, loads the guide, finds the current step, gets live checks from the server and speaks Greek. Tracking of the real part without a marker is built and installed but **does not lock on the headset yet**: the first real frames (12:36) were all rejected. Two causes are being fixed now (§3).

---

## 1. Decisions made today

| Decision | Detail |
|---|---|
| Demo app | **`quest-android/`** (Kotlin, Meta Spatial SDK 0.15.0). `quest-unity/` is the backup: its round-2 build is installed on the headset but has never been opened there. The WebXR client is not the demo path. |
| No marker | The marker strip cannot be printed. Registration is markerless (§2). The marker code stays in both apps and still wins if a strip is seen. |
| Demo flow | Starts with the product preset (`job=malm-drawer`, `lang=el`). Product identification is an optional extra shot. |
| Build agent model | `claude-fable-5-2` through `AGJ_AGENT_MODEL` in `.env` for the demo; the code default and this morning's timed builds are `claude-opus-5-5`. Check the event's rules on non-Gemini models before submitting. |
| Judge | Round 2 (static review): Android 39.8, Unity 39.0 (round 1: 36.0 and 23.8). Scores are capped because the round-2 builds had not been seen on the headset. `eval/judge/round-2.md` has the ranked defect lists. |

---

## 2. How the Android app registers the guide on the real part (no marker)

Sources of the assembly pose, strongest first. `world/AssemblyAnchor.kt` holds the pose (WORLD ← TABLE), `world/WorldModule.kt` orchestrates, `MainActivity.frameTick` draws the ghost from `vision.worldFromAssembly()`. `track.source` and `place.source` in the AGQ log say which one is live.

| Source | What it is | Where |
|---|---|---|
| marker | printed AprilTag strip (not used in the demo) | `vision/MarkerDetector.kt`, `MarkerTracker.kt` |
| `manual` | `world.nudge(...)`; no UI caller yet | `world/WorldModule.kt` |
| `markerless` | on-device detector of the white drawer front lying flat (380 × 130 × 16 mm in the model), 3 Hz, x, y and yaw; first step only | `vision/PanelPose.kt`, reference `server/perception/panel.py`, method in `tools/markerless/FRONT_PANEL.md` |
| `flow` | optical flow follows every part between server answers and moves the ghost when the real base part is slid (position only) | `vision/PartFlowTracker.kt`, `world/PartTracks.kt`, `world/PartTracking.kt` |
| `server_box` | the server's box for the base part (`POST /api/perception/{job}/parts`, `mode: "step"`), ray-cast onto the work surface; must be 0.6–1.5× the part's long side and agree with a second answer | `world/PartsFinder.kt`, `WorldModule.onBase` |
| `table` / `fixed_spot` | MRUK scene table, else a guess in front of the head | `world/SceneTable.kt`, `world/Table.kt` |

The work surface height comes from the MRUK table, else from Meta's depth raycast (`WorldModule.probeSurface`, `surfaceY`). The demo headset has **no room scan**, so only the depth path is used.

Also on `main` since this morning: glass step window with guide drawing, parts card, camera preview, push-to-talk voice commands (`/api/voice`), pick-up markers on every instance of every part a step needs, a verdict badge at the hand for right / wrong / unsure part with a manual "check this part" (`ui/HeldVerdict.kt`, `ui/HeldPartGuide.kt`), translucent ghost material with an unlit fallback (`--es ghost_material unlit`).

---

## 3. What the headset has shown so far (Android)

| Works on the device | Evidence (AGQ log, `tools/quest/runs/*-android/`) |
|---|---|
| Passthrough behind the app | headset captures |
| Live camera 1280 × 960 with a camera pose on every frame | `camera`, `camera_pose` |
| Guide load, step locate | `job_loaded` about 2.2 s, `locate` 1.2–1.6 s |
| Perception socket | acks 70–120 ms median, 4 Hz |
| Greek spoken instructions, 80–90 fps | `voice`, `frame` (the first minute after launch dips to p5 37–50 fps) |
| Server box moves the guide onto the part | twice, confidence 0.96 and 0.98 |

| Does not work yet | What the log shows | State |
|---|---|---|
| Panel detector never trusts a frame | 12:36: 37 real frames examined on a dark floor, good contrast, 35 rejected on size, 2 as not a clean rectangle. Clean rectangles measured about 430 × 110–125 mm against the model's 380 × 130 | The real drawer front is being measured; the model's sizes were estimated from the guide drawings |
| Work surface height jumps | the depth probe follows the gaze: −0.03 m (floor) to 0.56 m within 30 s, and the ghost followed it | Being fixed: measure the height at the part (depth ray through the server's box) and lock it |
| Hand tracking | every launch: `skeleton: Native Assert … Failed to initialize session for body` | Being fixed on a branch |
| `/parts` rarely sees the drawer front | 10 of 194 calls | Most calls were made while the headset lay on a desk |
| Instruction text for dowel, cam lock and pin says "screw it in" | the served `malm-drawer` job predates the text fix `e32eadd` | Rebuild the job's text |

The headset battery ran out at about 12:45 (it had been kept awake over adb while lying on the desk); it is charging until about 13:30.

---

## 4. In progress, not on `main` yet

| Work | Branch (local worktree) |
|---|---|
| Tracking at every step: the detector takes the assembly's footprint per step, enabled only where no wrong pose is trusted on 360 rendered frames | `worktree-agent-a19bcb4dade759a5c` |
| UI fixes from the judge: step window within 30° of the work (done), Re-align reachable, the no-marker instruction shown and spoken, Register card without marker wording | `worktree-agent-a22c5f4481d0ef0c8` |
| Exact on-object guidance: hole positions, insertion direction, turn sense, "2 of 4" counting | `worktree-agent-a4912ec359f206139` |
| Hands: real hand joints, clear log line when hand tracking is off in the headset settings | `worktree-agent-a67d038dc77cbaeea` |
| Frame recorder (`--ez dump_frames true`) and `tools/markerless/eval_real.py` to run the detector on real recordings | `worktree-agent-a37437ea54efe947b`, being merged |

Local `main` is ahead of `origin/main` between pushes. Agent worktrees start from `origin/main`, so an agent must run `git rebase main` before reading code.

---

## 5. Build, install, run, read the log (Android)

```sh
cd quest-android && ./gradlew test assembleDebug && cd ..
tools/quest/install.sh android                        # installs the debug APK and grants permissions
tools/quest/run.sh android --job malm-drawer --lang el --secs 300 --keep-running
#   --hud adds the debug panel (it shows the server address: never record with it)
#   --no-launch attaches to a running app; the run folder is tools/quest/runs/<time>-android/
grep -E '"ev":"(place|place_candidate|panel|surface|part_track|held_verdict)"' tools/quest/runs/<run>/agq.jsonl
```

What to read: `panel` (`trusted`, `reason`, `size_err_mm`, `rectness`, `contrast`, `table_y_m`), `place` (`source`, `reason`), `surface`, `part_track` (`alive`, `moved`, `ms`), `ghost_material` (`kind`). A headset view without wearing it: `scrcpy --no-window --no-audio --no-playback --record f.mp4 --time-limit 4`, then crop the left eye with ffmpeg. `adb exec-out screencap` returns an empty image on Quest.

Keep the headset on a wall charger when it is not worn: the Mac's USB port does not keep up with the app running.

---

## 6. Server (unchanged contracts, three additions today)

Hosted from a Mac with `deploy/mac_host.sh`; the public URL is in `jobs/_host/url.txt` and every route needs `?key=<DEMO_KEY>` or the `X-Demo-Key` header. 324 tests pass on `main`.

| Addition | Detail |
|---|---|
| `POST /api/perception/{job}/parts` with `"mode": "step"` | one call returns every item a step needs: `items[]` with `id`, `code`, `kind`, `name.en/.el`, `visible`, `box_2d`, `boxes` (every instance), `confidence`, `count_seen`, `count_needed`, `count_text`; median 5.1 s. The default mode is unchanged. |
| `POST /api/perception/{job}/panel` | JPEG + `fx fy cx cy` + `cam_in_table` (16 floats, row-major) → `{trusted, reason, x, y, yaw, corners_px, ms}`. For clients without OpenCV (Unity). |
| Step-complete for hardware the reference sheet hides | a close-up reference (`refs/step-NN-detail.png`, written by `python -m pipeline.perception.details jobs/<job>`). On the 55 MALM render pairs: 46 → 50 correct, false "done" 1 → 0. Step 3 (cam locks) is still marginal (0.46 against a 0.45 line). Renders only. |

Full API, examples and frame conventions: `server/perception/README.md`. Earlier endpoints (`/api/identify`, `/locate`, `/api/voice`, `/api/tts`, the perception socket) are as documented there and in `quest-android/README.md`.

### Guide data conventions (for any client)
- GLB: metres, Y-up. Root `assembly`; parts `part_<id>_<n>`, hardware `hw_<code>_<n>`. **Parts rest hidden (scale 1e-4)**; final transforms exist only at the end of the `walkthrough` clip.
- Manifest `target_m`, `from_dir`, `assemblyRotationDeg` are in Blender's Z-up frame: `(x, y, z)_blender → (x, z, −y)` for glTF. `from_dir` points from the target towards where the part comes from.
- Test jobs on the host: `malm-drawer` (7 steps, a real drawer exists), `knagglig` (8 steps), `lack`.

---

## 7. Submission pack

- `docs/SUBMISSION.md` and `docs/DEMO_VIDEO.md`: written for the Android demo without a marker; every claim tied to evidence; open items in the "Before submitting" block (`TODO(tracking: confirm on headset)`, team names, the form's track and video length, the model rule).
- Rough cuts with an English voice-over, outside git: `jobs/_media/cut/demo-rough.mp4` (2:47) and `demo-60s.mp4` (1:00). Five headset shots are placeholders; drop recordings as `jobs/_media/cut/headset/H1.mp4` … `H5.mp4` and run `./build.sh` there.

## 8. Backup app (Unity)

`quest-unity/` (Unity 6.3 LTS, Meta XR SDK 209): passthrough fixed on the device this morning; round 2 added the UI, voice, camera preview and a world layer (table placement, server box, pick targets) with the same server calls. Not opened on the headset since. Build and conventions: `quest-unity/README.md`; known defects: `eval/judge/round-2.md`.
