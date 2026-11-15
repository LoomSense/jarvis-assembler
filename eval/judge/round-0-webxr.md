# Round 0 · WebXR baseline (`client/src/xr/`)

```
Round:         0 (baseline, WebXR only)
Date (Athens): 2026-11-15 10:10
Judge:         judge agent, rubric v1 (eval/judge/RUBRIC.md)
Commit:        main 35af668 (client/src/xr unchanged since d73bdf1 except 3 lines in xrMain.ts: ask for the camera on page open)
Server:        not exercised by the judge this round; server numbers quoted from jobs/_verify/ and docs/HANDOFF.md
Device:        Quest 3 · Quest Browser 38.2.0.13 (Chrome 134), from the camera report in jobs/_diag/
Artifacts:     eval/judge/round-0/
                 webxr-typecheck.log            tsc --noEmit, exit 0
                 webxr-preview-malm.png         desktop preview, ?mode=xr&job=malm-drawer, parts check card
                 webxr-preview-malm-action.png  same, step 1 cam bolt action (ghost, turning arrow, panel)
                 quest-camera-diag.json         Quest 3 camera report: 0 video inputs, NotFoundError
```

**Device evidence: none above E2 for the guidance itself.** The only device artifact is the camera report, and it shows a failure. No AGQ log, no fps, no capture. Every device-only criterion is therefore capped at 2, and the camera failure lowers the camera-dependent ones further.

## 1. Scores

### webxr · total **29.8 / 100**

| ID | Criterion | Wt | Score | Ev. | Points | Evidence | Cap / unverified |
|---|---|---|---|---|---|---|---|
| R1 | Identify | 7 | **1** | E3 (failure) | 1.4 | `scan.ts` sends one 768 px q0.7 JPEG to `POST /api/identify`; server answer measured 0.99 / 3 to 4 s on a MALM guide page (HANDOFF §1). On the tested Quest 3 `getUserMedia` finds **0 video inputs** (`quest-camera-diag.json`: `NotFoundError`, `videoInputs: 0`), so the headset cannot take the picture. Identify is also button-triggered (Identify on the coach card). | device failure overrides code (code alone: 2) |
| R2 | Guide load and transforms | 7 | **2** | E2 | 2.8 | `jobAssembly.ts` runs the `walkthrough` clip to its end, reads nodes under `assembly`, converts Z-up (`toGltf`); MALM drawer loads in the desktop preview with no console errors (`webxr-preview-malm*.png`). No test compares nodes with manifest `target_m`; load time on device not measured. | partly device, no E3 |
| R3 | 6-DoF tracking | 20 | **1** | E1 | 4.0 | Two-corner fingertip touch (`register.ts` `CornerSnap`, 1 cm / 0.8 s hold, yaw + position only) plus WebXR anchors (`placement.ts`). The ghost does not follow a moved object; after a `turn` action or a move the user presses **Move** and touches again. No camera pose or intrinsics in WebXR, so no automatic tracking is possible here (README "What this is not"). Touch accuracy never measured on device. | unverified; code alone is also 1 |
| R4 | Step and state detection | 10 | **1** | E2 (server) / E3 (failure) | 2.0 | Server baseline `jobs/_verify/bench_sc.txt`: 83.7 % at 0.45, **0 false completes**, step 3 (cam locks) never fires (0.44), step 7 can fire on a before frame (0.69); clean renders only. Client wiring exists (`live.ts` → `src/assist`, `Find step` → `/locate`), but on the tested headset no frames can be sent (0 cameras). The phone-as-camera fallback (`role=camera`) was not part of this client's run. | device failure; would be 2 with a working camera path |
| R5 | On-object guidance | 12 | **2** | E2 | 4.8 | Ghost at final place, insertion arrow / turning arrow, hole rings, callouts, BRAND ghost colours and ≤ 1 Hz pulses (`ghost.ts`, `stepGuides.ts`), max 2 instruction lines (`panels.ts` `wrap`). Seen in preview: step 1 shows ghost bolt with turning arrow. **Defect:** MALM hardware is titled by raw code (`118331 2/2`) because `HARDWARE_NAMES` (`stepGuides.ts:18`) only knows the three KNAGGLIG codes and `jobAssembly.ts` names a fasten part by `a.code`; the manifest's `hardware[].names` ("cam bolt" / "βίδα έκκεντρου") is ignored. Never seen on a real object. | cap 2, code 3 |
| R6 | Hands and intent | 8 | **2** | E1 | 3.2 | `handChecks.ts` (`hand_at_target` 5 cm, `still` 1 cm / 0.5 s, `raised`) at 10 Hz to the socket; `handJudge.ts` turns the ghost red when a hand stays 650 ms at another part's place (a warning before the part is down). Accuracy depends entirely on the two-corner registration; no device run. | cap 2, code 2 to 3 |
| R7 | Voice and conversation | 7 | **2** | E1 | 2.8 | `speech.ts`: Gemini TTS through `/api/tts` with prefetch, browser voice fallback, EN and EL, server sentences spoken without codes. **No voice input at all:** the mic stream is opened in `media.ts` and never used; no Gemini Live, no `/api/jobs/{id}/ask`. Speech in Quest Browser unverified. | device only, no E3 |
| R8 | Zero-setup flow | 8 | **1** | E1 + E3 (camera failure) | 1.6 | Path in code: open URL with `?key=` (typed or QR) → ① Camera tap + prompt → ② Enter AR tap + XR prompt → Identify pinch → (plate auto) → two corner holds → parts check "I have them all" → Next per action (dwell 1.5 s on the target can replace Next). At least 5 explicit actions plus 2 prompts. On the tested device the camera step fails, so the job must come from the URL (`?job=`). Not timed. | device failure |
| R9 | Performance and comfort | 8 | **2** | E1 | 3.2 | No head-locked UI: panel placed once 0.8 m ahead and 12° down, then world-fixed (`xrMain.ts` `panelNeedsFront`), later beside the snapped object; pulses ≤ 1 Hz; `prefers-reduced-motion` honoured. No fps measured, no `updateTargetFrameRate`, no foveation setting, canvas panels redrawn every 250 ms to 1 s in place mode. | cap 2, code 2 |
| R10 | Engineering | 7 | **2** | E2 | 2.8 | `npm run typecheck` exit 0 (`webxr-typecheck.log`); documented run commands; IWER emulator (`?emu=1`), scripted scan answers (`?mock=scan`), camera reports to `/api/diag`. **No unit tests for any `src/xr` module** (not even `solveSnap`), `npm run shots` covers the guide page only, logging is free-text `console.info` (no AGQ events), no replay camera mode. | |
| R11 | Find parts | 6 | **1** | E1 | 1.2 | "Pick up now" card with a turning 3D copy and name/code (`partsCard.ts`); held-part name by the hand when the camera names it (`heldTag`), which cannot happen on the tested device. Nothing marks a part in the real pile. | device failure (code alone: 2) |
| | **Total** | **100** | | | **29.8** | | 7 of 11 criteria unverified on device |

**Measured numbers**

| Metric | Value | Source |
|---|---|---|
| Build | typecheck pass (0 errors); `vite build` not run by the judge | `round-0/webxr-typecheck.log` |
| Unit tests | 0 for `src/xr` | `find client -name "*.test.*"` → none |
| identify p50 ms, correct k/n | server 3 to 4 s, 1/1 MALM page; on headset n/a (no camera) | HANDOFF §1, `quest-camera-diag.json` |
| job_loaded ms (malm-drawer) | n/a (loads in desktop preview, not timed) | screenshots |
| max transform error mm | n/a (no test) | |
| track rate / tracked % / jitter / error | n/a: no tracking (manual touch + anchors) | `register.ts` |
| step accuracy, false completes | 83.7 %, 0 (server, clean renders, not through this client) | `jobs/_verify/bench_sc.txt` |
| fps p50 / p5 | n/a | |
| user actions / seconds to first ghost | ≥ 5 actions + 2 prompts (code count), not timed | `xrMain.ts` |
| errors in log | 1 device report: `NotFoundError`, 0 video inputs | `quest-camera-diag.json` |

## 2. Three biggest gaps

1. **No automatic 6-DoF tracking (R3, 20 pts, scores 1).** The browser gives no camera pose or intrinsics in the XR frame, so the ghost stays where two fingertip touches put it; any move or turn needs a manual re-snap. This is the reason the native apps exist; it is out of reach for WebXR, not a bug.
2. **No headset camera on the tested Quest (R1, R4, R8, R11).** Quest Browser 38.2 lists 0 video inputs; identify, locate, live checks and held-part names all depend on that frame. In the headset the flow falls back to a job in the URL and buttons. Reported fix (Browser 40.1 + "Headset cameras" permission) is unverified.
3. **No device evidence or instrumentation (caps on R5, R6, R7, R9).** Nothing in `client/src/xr/` has a measured run on a Quest: no fps, no ghost-offset measurement, no AGQ log, no replay mode, no unit tests. The code for ghost/arrows/hands is the strongest part of the client and earns only 2 because it was never measured. Close behind: voice is output only (mic opened, never used).

## 3. Top 3 fixes for the WebXR client (if it stays in the race)

1. **[R9, R5, R6] Produce a device log** (+up to 7 pts: R9 2 → 3, R5 2 → 3, R6 2 → 3)
   - File: `client/src/xr/xrMain.ts` (`renderer.setAnimationLoop`, line ~1455) and a new `client/src/xr/agq.ts`
   - Symptom: no fps, no ghost-offset or hand numbers from a real Quest 3; every device-only criterion capped at 2.
   - Expected: AGQ JSON lines (`start` with `app:"webxr"`, `build`, `device`; `frame` 1 s summaries with `p5`, `p50`; `step` with `by`; `track` with `method:"touch"` and the snap residual) posted to `/api/diag` or kept for download; fps p5 ≥ 60 over 5 minutes.
   - Verify: `python3 -I tools/quest/summarize.py round-1/webxr-agq.jsonl` shows `frame.p5 ≥ 60`; ruler check of ghost offset at 3 actions ≤ 2 cm, recorded in the scorecard.
2. **[R5] Use the manifest's hardware names** (+0 now, removes a defect that blocks R5 = 3 on any MALM run)
   - File: `client/src/xr/jobAssembly.ts:128` (`name: screw ? a.code …`) and `client/src/xr/stepGuides.ts:18` (`HARDWARE_NAMES`)
   - Symptom: MALM step 1 panel title `118331 2/2`, card label `118331` (`round-0/webxr-preview-malm-action.png`), though the manifest says "cam bolt" / "βίδα έκκεντρου".
   - Expected: title "Cam bolt 2/2", meta `118331 · ⌀8 × 37 mm`, Greek from `hardware[].names.el`.
   - Verify: `npm run dev`, open `/?mode=xr&job=malm-drawer`, → three times; the panel title reads "Cam bolt 2/2".
3. **[R7] Voice commands on the opened mic** (+2 pts: R7 2 → 3 once verified on device)
   - File: `client/src/xr/media.ts` (`micStream`, unused) and `client/src/xr/xrMain.ts` (`onButton`)
   - Symptom: the mic is requested and never read; no way to say next, back, repeat, where am I.
   - Expected: next / back / repeat / where-am-I in EN and EL through Gemini Live (`POST /api/live/token`) or push-to-talk transcription, mapped to `onButton`; `step` events logged with `by:"voice"`.
   - Verify: device script step 7, ≥ 4 of 5 commands acted on in each language, visible in the log.

## 4. What the native apps must show to beat this baseline

The baseline is 29.8. A native app scoring only on code would land around the same number: device-only criteria cap at 2 without logs. To score **above 2 on a device-only criterion**, the round-N artifacts must contain, from a Quest 3 running the scored commit:

| Criterion | Minimum evidence for 3 |
|---|---|
| R3 tracking | AGQ `track` events with `method` and `state`; summary showing ≥ 10 Hz, ≥ 70 % tracked, jitter ≤ 3 mm; a move-and-turn test (20 cm, 90°) with the ghost following in ≤ 1 s, plus a replay `score_track.py` result ≤ 10 mm / 3° |
| R5 on-object | ruler-measured ghost offset ≤ 2 cm at 3 actions, with a headset capture frame of each |
| R6 hands | `hands` messages at ~10 Hz in the log; staged wrong-hole / wrong-board results (≥ 3 of 5 warned) |
| R7 voice | speech heard in EN and EL; ≥ 4 of 5 voice commands acted on per language (`step.by:"voice"`) |
| R8 zero setup | counted actions and seconds from launch to first ghost (≤ 5 actions, ≤ 90 s), with the log's timestamps |
| R9 comfort | `frame` summaries over 5 minutes with camera, tracking and socket on: p50 ≥ 72, p5 ≥ 60 |
| R11 find parts | held-part answer right on ≥ 4 of 5 tries (logged `perception` events), or the inventory call used |

For R1, R2 and R4 (partly device) a native app can reach 3 offline with replay through its own camera code path, and needs the device log to go higher. For all of them, the log's `start.build` must match the scored commit, and the camera frames must come from the passthrough camera API (a `start.camera_mode:"live"` run), not from replay.
