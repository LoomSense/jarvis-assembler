# Rubric addendum: R12 Visual and interaction quality

Version 1 (proposal) · Sun 15 Nov 2026 · extends `eval/judge/RUBRIC.md` v1. Applies from the round after it is accepted; round 0 is not re-scored.

The user asked for a premium spatial UI in the style of current headset operating systems and excellent guidance on the real object. R5 scores whether guidance is **on the object and accurate**; R12 scores whether it **looks and behaves as specified** in `docs/design/XR_NATIVE_SPEC.md` (glass windows and ornaments, type, motion, sound, states). The checklist in spec §8 (36 items, 8 critical ★) is the measuring instrument.

## 1. Weight change (total stays 100)

| ID | Criterion | v1 | v2 (proposed) | Why |
|---|---|---|---|---|
| R1 | Identify | 7 | **6** | |
| R2 | Guide load and transforms | 7 | **6** | Mostly settled by shared server data |
| R3 | 6-DoF tracking | 20 | **16** | Still the largest single criterion |
| R4 | Step and state detection | 10 | **9** | |
| R5 | On-object guidance quality | 12 | **10** | Its look-and-feel part moves to R12; R5 keeps placement accuracy |
| R6 | Hands and intent | 8 | 8 | |
| R7 | Voice | 7 | 7 | |
| R8 | Zero-setup flow | 8 | 8 | |
| R9 | Performance and comfort | 8 | 8 | |
| R10 | Engineering | 7 | **5** | |
| R11 | Find parts | 6 | **5** | |
| **R12** | **Visual and interaction quality** | | **12** | New |
| | **Total** | 100 | **100** | 6+6+16+9+10+8+7+8+8+5+5+12 |

Quick total check for `judge.md` §5 once accepted:
```sh
python3 -c "w=[6,6,16,9,10,8,7,8,8,5,5,12]; s=[...]; print(round(sum(a/5*b for a,b in zip(s,w)),1))"
```
Accepting this is a plan-level decision: log it in `docs/PROGRESS.md` §6 and update `RUBRIC.md` §2 and the `judge.md` weight vector in the same commit.

## 2. Kind and evidence

**Kind: device only** (it is about what the wearer sees and hears), with one exception: editor or emulator captures of the app's own build (E2) count for anchors 1 and 2.

| Evidence | What counts for R12 |
|---|---|
| E0 | Text, design docs, mockups. Scores 0. The spec mockups never count as evidence for an app. |
| E1 | Code read by the judge: materials, sizes, curves, sounds wired into the main flow. Alone: at most 1. |
| E2 | Screenshots or a recording of **the app's own build** in the Unity editor (Play mode with the XR simulator), Meta XR Simulator, or the Spatial SDK emulator, saved under `eval/judge/round-N/`. At most 2. |
| E3 | Device log without visuals. Does not raise R12 above 2 by itself (the checklist needs pictures and sound). |
| E4 | Headset capture (Quest screen recording with audio, or scrcpy with audio, plus screenshots) of the scored build, on the real MALM drawer, during the standard device script (`judge.md` §4). Required for 3 and above. |

Caps (consistent with `RUBRIC.md` §3): without E4 the score is **at most 2**; a capture of a different build does not count; a known failure in the capture (for example head-locked panel, flashing) overrides code. Mark capped cells `cap 2, code N`.

## 3. How to score the checklist

1. Record during the device script: the coach flow from launch to the first ghost, one full MALM step with a `place` action (step 2), one `fasten` with a turning arrow (step 3 cam locks), one staged wrong part (step 6, DR runner on the left side), one turn (step 4), the completion, and one 30° head turn while looking at the window. Keep audio on. 3 to 6 minutes is enough.
2. For each of the 36 items in spec §8 write `pass`, `fail` or `not shown` (counts as fail) with the timestamp or screenshot file that shows it. Use the ruler in spec §8 (drawer front 380 mm, window 0.52 m) for sizes; flash rates are counted frame by frame on the recording (count light/dark changes in one second).
3. Pass rate = passes / 36. Critical set: items **1, 2, 11, 12, 17, 18, 26, 31**.
4. "Never" breaches from spec §4.13 seen anywhere in the capture (head-locked UI, flashing above 1 Hz or 3 flashes per second, more than one ghost, text walls, status by colour alone) are listed separately; each one caps the score as the anchors say.

## 4. Anchors

Read from 5 down; give the highest score whose every condition is met.

| Score | Observable condition |
|---|---|
| 0 | No in-world UI, or the app does not reach guidance. |
| 1 | UI exists in code (E1), or captures show a "never" breach: head-locked UI, flashing faster than 1 Hz, text walls (more than 2 instruction lines), or more than one ghost at a time. |
| 2 | E2 captures of the app's own build show the glass window beside the object with an ornament toolbar, a ghost with rim and edges, and ≥ 50 % of the checklist items that an editor capture can show; no "never" breach. (Also the cap for any score without E4.) |
| 3 | E4 on the real drawer: **all 8 critical items pass** and the pass rate is **≥ 60 %**; no "never" breach. |
| 4 | E4: all critical items, pass rate **≥ 75 %**, and these states are all captured and pass: next (ghost + path or turning arrow + label), correct (check + fade + sound), wrong (red dashed outline + ✕ + spoken reason + error status), unsure or tracking-lost (amber, honest), turn preview. Body text measured ≥ 1/28 of the window height. |
| 5 | E4: all critical items, pass rate **≥ 90 %** (≥ 33 of 36), in **one continuous capture** of at least 3 minutes from launch to a completed step; distinct sounds for press, correct, wrong and done heard in the capture; reduced-motion mode shown stopping every loop; no "never" breach anywhere in the round's captures. |

Notes for the judge:
- Score each app separately; the WebXR baseline is scored with the same checklist (its canvas panels can pass many items).
- Do not reward a look that hides poor guidance: if R5 is 1 (no ghost on the object), R12 is at most 2.
- When two items conflict in a capture (for example a pretty panel that sits over the object), the critical item decides.

## 5. Scorecard row

Add to `SCORECARD.md` §1 for each app:

```
| R12 | Visual and interaction quality | 12 | <0-5> | <E-level> | <pts> | pass k/36, critical k/8, breaches: <list or none>, capture: round-N/<app>-r12.mp4 | <cap note> |
```

and the per-item list as `round-N/<app>-r12-checklist.md` (36 lines: item, pass/fail/not shown, timestamp or screenshot).

## 6. Fix template for R12

Same five parts as `judge.md` §6. Example:

```
**[R12] Move the buttons into a toolbar ornament** (+2.4 pts: 2 → 3 needs this and items 1, 2)
- File: quest-unity/Assets/Scripts/UI/StepWindow.cs (Build buttons), new ToolbarOrnament.cs
- Symptom: round-N/unity-r12.mp4 00:42, buttons sit inside the text area; checklist items 7, 8 fail.
- Expected: glass capsule under the window, 14 mm in front, ≤ 4 items, one primary (spec §2.5).
- Verify: re-capture step 2; items 7 and 8 pass in round-N+1/unity-r12-checklist.md.
```
