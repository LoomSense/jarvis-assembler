# Team progress

The single shared status of the build. **Every teammate's coding agent updates this file** whenever a task changes state (rules in §1). The plan behind every task is `docs/PLAN.md`; section numbers like "§6.0" refer to it.

Code freeze: **Sunday 15 Nov 2026, 18:00 Athens time.** Submit by 17:30.

---

## 1. Update protocol (for people and their agents)

**When to update:** whenever one of your tasks starts, finishes, gets blocked or is cut; whenever you make a decision others depend on; whenever you finish a test listed in §4.

**How:**
1. `git pull --rebase` first.
2. Edit **only your own rows** in §3 and **only your own log** in §7. Never rewrite another person's row; ask them, or add a note in your own log.
3. Change the status and the "Updated" time. Fill in "Evidence" when a task is done: a commit hash, a file path, a screenshot path or a measured number.
4. A blocker goes in §5 with the task ID and who can unblock it.
5. A decision that changes the plan goes in §6 **and** in `docs/PLAN.md` §1.
6. Commit only this file (plus `PLAN.md` for decisions) with the message `progress: <task id> <status>`, for example `progress: CAD-03 done`. Then `git pull --rebase && git push`.
7. Times are Athens time, `Sat 21:40` style.

**Statuses:** `todo` · `doing` · `blocked` · `done` · `cut`. Only one person owns a task. A task is `done` only when its "Done when" condition is met and the evidence is filled in.

**Agents:** read `CLAUDE.md` (or `AGENTS.md`) at the repo root. It points here.

---

## 2. Team and status at a glance

| Person | Name | Role | Current task | Status | Updated |
|---|---|---|---|---|---|
| A | Evangelos | Backend and infra lead | INF-08; INF-09/10/11 done | doing | Sun 07:10 |
| B | | Agent and prompts | AGT-01 | todo | |
| C | (covered by the data owner on Sat) | CAD and Blender | DEM-02 (waits for G3); CAD-01…09 done | todo | Sat 23:20 |
| D | | Phone client and Quest XR | CLI-01 | todo | |
| E | | Phase 2 perception, voice, demo story | LIV-01 | todo | |

### Gates (go / no-go checkpoints)

| Gate | When | Pass condition | Owner | Result |
|---|---|---|---|---|
| G1 Hosting | Sat 21:00 | Headless Blender render works on the chosen host (§8.3) | A + C | **Mac: pass, chosen** (boxd sign-ups closed). Full pipeline through the public URL in 48 s, Sun 03:10 |
| G2 Quest camera | Sat 22:00 | Headset `getUserMedia` gives real frames during an XR session → option A; otherwise option B (phone camera) (§6.0, D12) | D + E | pending |
| G3 Phase 1 | Sun 00:00 | Golden guide → walkthrough with ≥ 3 animated steps playing on a phone | B + C + D | pending |
| G4 Phase 2 | Sun 11:00 | Replay accuracy ≥ 70%, 0 false alarms on the correct clip, event p50 ≤ 500 ms, Live speaks an injected event | E | pending |
| G5 XR | Sun 11:00 | Ghost stays aligned with the real base panel while the user moves around the table | D | pending |
| G6 Freeze | Sun 15:00 | Feature freeze; only bug fixes after | all | pending |
| G7 Submit | Sun 17:30 | Repo, 1-minute video and details submitted | E + A | pending |

---

## 3. Tasks

Columns: **ID** · **Task** · **Owner** · **Status** · **Depends on** · **Done when** · **Evidence** · **Updated**

### 3.1 Setup (first hour, everyone)

| ID | Task | Owner | Status | Depends on | Done when | Evidence | Updated |
|---|---|---|---|---|---|---|---|
| SET-01 | Link Gemini billing, confirm **Tier 1**, create `GEMINI_API_KEY` | A | done | – | Tier 1 shown in AI Studio | A Gemini key from the organisers is in `.env` on A's Mac (the demo host) | Sun 05:10 |
| SET-02 | Smoke-call `gemini-4-carbon`, `gemini-4-hydrogen`, `gemini-decision-1` (`/v1beta/decisions`), and a Live session with an ephemeral token | A | done | SET-01 | All four return without error | `make models` PASS on A's Mac: Carbon 3.7 s, Hydrogen 2.3 s, Decision 1 307 ms, Live opens with our minted token and calls a tool after 2.1 s | Sun 05:10 |
| SET-03 | boxd login + payment method, create VM `agj`, run `uname -m` | A | cut | – | Architecture recorded here | boxd sign-ups are closed ("We've reached capacity", Sun 03:00), and no team member has a boxd account. The Google Cloud temp account from the organisers was not received by A | Sun 03:12 |
| SET-04 | Python 3.12 venv with cadquery, google-genai, fastapi, uvicorn, pymupdf, opencv-python-headless, numpy, pydantic-settings | all | todo | – | `python -c "import cadquery, fitz, cv2"` works on each laptop | | |
| SET-05 | Install Blender 5.3 on the Macs (A, C) | C | todo | – | `blender -b --version` prints 5.3 | Blender 5.3.0 runs headless on the data owner's Mac (`brew install --cask blender`), where the C tasks were done. The team MacBook Air is still blocked by disk space (§5) | Sat 23:20 |
| SET-06 | Node + `three@0.187.0`, cloudflared | D | todo | – | `npm create vite` app runs on a phone via tunnel | | |
| SET-07 | fal key; treg token (optional) | A | done | – | Keys in `.env` | `FAL_KEY` in `.env`; first real call made the MARKUS tilt mechanism in about 2 min ($0.375) | Sun 05:10 |
| SET-08 | Quest 3: charge, update, developer mode, Meta Quest Developer Hub, Immersive Web Emulator in Chrome | D | todo | – | Headset visible in `chrome://inspect` | | |
| SET-09 | Choose the golden guide and get the physical product to the venue | B | todo | – | PDF in `eval/golden/<name>/guide.pdf`; product owner named | | |
| SET-10 | Repo skeleton: layout (§8.4), `.gitignore`, `.env.example`, `CREDITS.md`, contracts from §4 | A | done | – | Pushed to `main` | `9b22d55`: `server/`, `prompts/`, `deploy/`, `tests/`, `requirements.txt`, `Makefile`, `.env.example`, `CREDITS.md`, and `README.md` with the API and event contracts as built | Sat 23:15 |

### 3.2 Backend and infrastructure (A)

| ID | Task | Owner | Status | Depends on | Done when | Evidence | Updated |
|---|---|---|---|---|---|---|---|
| INF-01 | FastAPI app, `/healthz`, demo-key check, static client serving | A | doing | SET-10 | `/healthz` returns over HTTPS on the host | `9b22d55`: `server/app.py`, demo key on every `/api` and `/files` route, static client from `client/dist`. `/healthz` answered over HTTPS through a trycloudflare tunnel from A's Mac; not on the demo host yet (INF-05) | Sat 23:15 |
| INF-02 | Job folder layout (§4.1), stage runner with `.done` markers and retry | A | done | INF-01 | A job restarts and skips finished stages | `9b22d55`: `server/orchestrator/runner.py`. `kill -9` during `steps`, then restart: only `steps` ran again and the job finished. Retry from `refs` reran only `refs` and `export` (`tests/test_server.py`) | Sat 23:15 |
| INF-03 | `events.jsonl` writer + SSE `GET /api/jobs/{id}/events` with `Last-Event-ID` replay | A | done | INF-02 | Phone feed reconnects without losing events | `f3db491`: `eval/smoke.sh` reconnects mid-history with `Last-Event-ID` and gets every later event exactly once. Not tried with the phone client yet. A trycloudflare tunnel buffers SSE, so use `/events/poll` there (§6) | Sat 23:15 |
| INF-04 | REST endpoints (§4.5): upload, status, plan, approve, retry, files | A | done | INF-02 | All return the documented shapes | `9b22d55`: all PLAN §4.5 routes; `tests/test_server.py` 11 of 11 pass; shapes listed in `README.md` | Sat 23:15 |
| INF-05 | Hosting test G1 with C, then deploy | A | done | SET-03, SET-05 | G1 recorded in §2 | Host = A's Mac (M4 Pro): `deploy/mac_host.sh` (awake, auto-restart, cloudflared quick tunnel, health check every 60 s). `eval/smoke.sh <url>` PASS in 48.4 s through the public URL; the client page loads. The URL changes if the tunnel restarts: read `jobs/_host/url.txt` in `.demo-host/` | Sun 03:12 |
| INF-06 | `POST /api/live/token` (single-use, 30 min, config locked) | A | done | SET-02 | Phone and headset open a Live session with it | `POST /api/live/token` minted a token (v1beta) and a Live session opened with it from Python (`eval/smoke_models.py`). Not yet tried from the phone or headset client | Sun 05:10 |
| INF-07 | `eval/smoke.sh` end-to-end | A | done | INF-04 | Exit 0 on the golden guide | `f3db491`: `eval/smoke.sh` exits 0 on KNAGGLIG: 20.4 s on localhost, 50.6 s through an HTTPS tunnel. It uses the hand-checked plan until AGT-02 lands | Sat 23:15 |
| INF-08 | Secrets scan, README, CREDITS | A | doing | – | No keys in git history; CREDITS complete | `make secrets` (gitleaks): no leaks in the history; `README.md` and `CREDITS.md` written; CREDITS lists 3 items to confirm before submission | Sat 23:15 |
| INF-09 | Parts inventory before assembly: photos of the laid-out box vs the plan (D22) | A | done | – | Catches a missing part on a test photo | `86f58fc`: `POST/GET /api/jobs/{id}/inventory`, `pipeline/inventory.py`, `inventory` event; 7 tests. Composed test photos from KNAGGLIG renders: missing handle slat caught (9.6 s, $0.024), 2 of 24 short screws missing caught (15.3 s, $0.033). Not tried on real phone photos; the client does not call it yet | Sun 07:10 |
| INF-10 | Ask the guide: questions and undo plans during assembly (D23) | A | done | – | Grounded answers on the golden guide | `cc2792a`, merged `2b9b248`: `POST /api/jobs/{id}/ask`, `pipeline/ask.py`; 15 tests. 5 real KNAGGLIG questions (el/en) correct or plausible, incl. undo for a panel put on upside down; 3–5 s typical, spikes to 14 s. The client does not call it yet | Sun 07:10 |
| INF-11 | Independent step check in the build loop: `./check` with Decision 1 (D24) | A | done | – | Wrong renders fail, right ones pass | `df87e70`, merged `21ac453`: `pipeline/check/step_check.py`, `./check` in the agent workspace, BRIEF updated, scores in `agent/run.json`; 10 tests. KNAGGLIG: 7/8 right renders pass, 7/7 swapped wrong renders fail; MARKUS: 7/11 pass in 4.2 s (steps 2 and 7 rightly fail, 4 doubtful). Not yet run inside a full agent session. `AGJ_VERIFIER=iris` is a seam only | Sun 07:10 |

### 3.3 Agent and prompts (B)

| ID | Task | Owner | Status | Depends on | Done when | Evidence | Updated |
|---|---|---|---|---|---|---|---|
| AGT-01 | Hand-write `expected_plan.json` for the golden guide | B (done by the data owner) | done | SET-09 | File committed | `51384ab`: `eval/golden/knagglig/expected_plan.json` (4 part types, 9 pieces, 28 screws, 8 steps) and `eval/golden/lack-side-table/`. Labelled from the PDF, not yet checked on the physical product | Sat 19:45 |
| AGT-02 | Rasterise + Carbon structured extraction with `box_2d` (§5.1); fallback Hydrogen `xhigh` if Carbon rejects the schema | B | doing | SET-02 | `plan.json` for the golden guide | D20: done by the build agent on the Claude Agent SDK, not by Carbon/Hydrogen (`pipeline/agent/extract.py`, by the data owner). KNAGGLIG from a clean job: plan + 6 scripts, the build passed on its first run, 438 s, 33 turns, $2.12; `score_plan.py` PASS (all gates 1.00, flips 0.88). One run, one guide. No `box_2d` yet, and not yet run through the server (`AGJ_BUILD_AGENT=claude`) | Sat 23:50 |
| AGT-03 | Crops + box re-check + count validation + flags | B | todo | AGT-02 | Crops look right; mismatches flagged | | |
| AGT-04 | `score_plan.py` against the expected plan | B (scorer by the data owner) | done | AGT-01, AGT-02 | Recall ≥ 0.9, counts exact ≥ 80%, Kendall τ ≥ 0.8 | `51384ab`: `eval/score_plan.py`. The plan written by the build agent from the PDF alone passes all three gates (1.00, 1.00, 1.00; flips 0.88; step content 0.75) · `eval/bakeoff/results/claude_sdk/` | Sat 23:50 |
| AGT-05 | Hydrogen part loop with tools (§5.6), 3 attempts, events logged | B | doing | CAD-02, CAD-03 | Golden parts pass or are flagged | D20: done by the build agent on the Claude Agent SDK, not by Carbon/Hydrogen (`pipeline/agent/extract.py`, by the data owner). KNAGGLIG from a clean job: plan + 6 scripts, the build passed on its first run, 438 s, 33 turns, $2.12; `score_plan.py` PASS (all gates 1.00, flips 0.88). One run, one guide. The agent's scripts run in the CAD-09 sandbox. Events go to the feed; not yet seen through the server | Sat 23:50 |
| AGT-06 | Hydrogen step-fix loop and flip choice | B | doing | CAD-05 | Golden steps pass or are flagged | D20: done by the build agent on the Claude Agent SDK, not by Carbon/Hydrogen (`pipeline/agent/extract.py`, by the data owner). KNAGGLIG from a clean job: plan + 6 scripts, the build passed on its first run, 438 s, 33 turns, $2.12; `score_plan.py` PASS (all gates 1.00, flips 0.88). One run, one guide. The agent compares the step renders with the guide itself; nothing measures how well | Sat 23:50 |
| AGT-07 | Per-step Decision 1 question writer for phase 2 (§6.1) | B | done | AGT-02 | `94c1a86`: `pipeline/perception/questions.py` writes `refs/step-NN-questions.json` and sets `questions` on each manifest step; KNAGGLIG 8 of 8 written with `gemini-4-hydrogen` and valid against the API limits; option keys are the sheet labels. Done by the data owner. Not run from the server yet | Sun 00:45 |

### 3.4 CAD and Blender (C)

| ID | Task | Owner | Status | Depends on | Done when | Evidence | Updated |
|---|---|---|---|---|---|---|---|
| CAD-01 | Headless render test: Workbench → EEVEE → Cycles (G1 with A) | C | done | SET-05 | Engine chosen and recorded in §6 | Workbench on the M4 (see §4) | Sat 20:00 |
| CAD-02 | CadQuery harness `run_part.py` (STL, STEP, `mates.json`, missing-frame check) | C | done | SET-04 | 3 fixture parts export | `pipeline/cad/harness.py`, `lib.py`; 6 KNAGGLIG parts build in 1.3 s | Sat 20:00 |
| CAD-03 | `render_parts.py` batch with guide angle and line-art style | C | done | CAD-01 | One Blender call renders N parts | `e3db37c`: `pipeline/blender/render_parts.py`; one call renders the 6 KNAGGLIG parts and screws as line art and shaded (12 PNGs at 512², 1.2 s). Run by the data owner | Sat 22:50 |
| CAD-04 | Placement code: mates → transforms, Kabsch, flip candidates (§5.3) | C | done | CAD-02 | Golden step 1–3 placed correctly | `pipeline/assemble/placement.py` + `collide.py`; 37 pieces, 0.0 mm residual; misfit and overlap tests caught | Sat 20:00 |
| CAD-05 | Scene + per-step NLA animation + `render_step.py` | C | done | CAD-04 | Step renders match the guide angle | `pipeline/blender/assemble.py`; action-by-action animation (turn, place, fasten one screw at a time), 41 actions, 65 s; `eval/golden/knagglig/out/walkthrough_frames.png` | Sat 21:00 |
| CAD-06 | GLB export (§5.4) + `manifest.json` (§4.3) + gltfpack | C | done | CAD-05 | GLB loads in three.js with one clip per step, ≤ 5 MB | `fc0c904`: `eval/glb_check/check.mjs` loads the GLB with the three.js r187 loader in Node (not yet in a browser or on a phone): `walkthrough` + 8 step clips, all manifest nodes found, every action at its manifest time, 2.0 MB. `pipeline/pack.py` (gltfpack `-cc -kn -ke`) gives 197 kB and passes the same check. `pipeline/crops.py`: 8 step crops at 300 dpi. The check found and fixed a bug: in step clips all pieces moved at once. Run by the data owner | Sat 23:00 |
| CAD-07 | Phase-2 refs: `step-NN-ref.png` and composite `step-NN-sheet.png` | C | done | CAD-05 | Sheets exist for every golden step | `e3db37c`: `pipeline/refs.py`, `pipeline/blender/render_refs.py`; 8 of 8 KNAGGLIG steps have `refs/step-NN-ref.png`, `-sheet.png` (1536×768, largest 445 kB) and `-sheet.json`; adds 4 s to `pipeline.run`. Not yet tried in a Decision 1 request. Run by the data owner | Sat 22:50 |
| CAD-08 | Hand-made two-step dummy GLB + manifest for D, tonight | C | done | SET-05 | D can play it on a phone | `eval/golden/knagglig/out/walkthrough.glb` + `manifest.json` (the real 8-step KNAGGLIG) | Sat 20:00 |
| CAD-09 | Sandboxed runner for LLM-written CadQuery (CPU, memory, file limits; killable), reusing the reference runner only if its owner agrees (PLAN §7.2) | C | done | CAD-02 | A runaway script is killed within its limit | `28a870f`: `pipeline/cad/sandbox.py`; `--selftest` 11 of 11: an endless loop is killed at its 4 s CPU limit, a sleep at 6 s, a memory hog at 1.2 GB in 1.7 s; network, writes outside the out folder and `.env` reads are refused on macOS only. Not yet called by the build loop. Run by the data owner | Sat 23:00 |

### 3.5 Phone client (D)

| ID | Task | Owner | Status | Depends on | Done when | Evidence | Updated |
|---|---|---|---|---|---|---|---|
| CLI-01 | Vite + three.js viewer playing the dummy GLB (clips, clamp, visibility) | D | todo | SET-06, CAD-08 | Plays on Android and iPhone | | |
| CLI-02 | Upload + plan review screen (crops beside JSON, flags, Approve) | D | todo | INF-04 | Approve works end to end | | |
| CLI-03 | Build feed over SSE | D | todo | INF-03 | Live events appear with thumbnails | | |
| CLI-04 | Walkthrough UI: step bar, scrub, highlight, camera tween, portrait layout | D | todo | CLI-01 | Golden walkthrough usable on a phone | | |
| CLI-05 | Phone camera role `?role=camera`: capture loop with backpressure + MediaPipe gate | D | todo | LIV-01 | Frames reach the server at ≤ 4 Hz | | |
| CLI-06 | Apply `docs/design/tokens.css` and BRAND.md to every phone screen (status = colour + shape, reduced motion) | D | todo | CLI-01 | Screens match BRAND.md; checked in light and dark | | |
| CLI-07 | Port reusable scaffolding (PLAN §7.2): camera framing, viewer skeleton, emissive highlight/fade, studio lighting, easing; add the CREDITS line | D | todo | CLI-01 | Viewer uses the ported modules; CREDITS updated | | |

### 3.6 Quest 3 XR (D)

| ID | Task | Owner | Status | Depends on | Done when | Evidence | Updated |
|---|---|---|---|---|---|---|---|
| XR-01 | `immersive-ar` session with three.js on our HTTPS URL: passthrough, hands, planes, anchors | D | todo | SET-08 | Hands and a detected table visible in the headset | | |
| XR-02 | Camera test inside Quest Browser (`enumerateDevices`, `getUserMedia` during XR) → G2 | D | todo | XR-01 | G2 recorded in §2 | | |
| XR-03 | Placement: pinch to place the plate, drag/rotate to align with the real base, persistent anchor | D | todo | XR-01, CAD-06 | Virtual base sits on the real base | | |
| XR-04 | Ghost of the next part + looping insertion animation + step panel with the guide crop | D | todo | XR-03 | Golden steps shown at true scale | | |
| XR-05 | Hand checks (`hand_at_target`, `still`, `raised`) sent over the perception WS | D | todo | XR-03, LIV-01 | Server logs hand summaries at about 10 Hz | | |
| XR-06 | Ghost colour from perception events (green/red), pinch buttons (next, back, repeat) | D | todo | XR-04, LIV-03 | Events change the ghost in the headset | | |
| XR-07 | Performance (≥ 72 fps) and headset recording/casting test | D | todo | XR-04 | Measured fps recorded; casting works | | |
| XR-08 | XR panels and ghost follow BRAND.md XR rules (0.40 × 0.28 m at 0.8 m, text ≥ 0.7°, 82% opaque, ghost pulse ≤ 0.83 Hz, world-anchored) | D | todo | XR-04 | Checked in the headset | | |

### 3.7 Phase 2 perception and voice (E)

| ID | Task | Owner | Status | Depends on | Done when | Evidence | Updated |
|---|---|---|---|---|---|---|---|
| LIV-01 | Perception WS route with roles, frame protocol, ≤ 2 in flight, acks (§4.5) | E | todo | INF-01 | Replay script streams frames and gets acks | | |
| LIV-02 | Decision 1 request builder (step header + frame + composite sheet + questions) | E | done | AGT-07, CAD-07 | `94c1a86`: `pipeline/perception/request.py` `ask(job, step, frame)`; 64 real requests, 0 failed, all 10 answers each. Frames were renders, not camera pictures. Done by the data owner | Sun 00:45 |
| LIV-03 | Thresholds, N-of-M smoothing, hysteresis, state machine, cooldowns (§6.2) | E | doing | LIV-02 | `pipeline/perception/logic.py` (events, N-of-M, hysteresis, cooldowns, state) with 12 unit tests on made-up answers, and `replay.py` writing the files `p2.py` scores. No recorded clip exists, so the done condition is not met. Thresholds are the PLAN §6.2 values, untuned. Written by the data owner | Sun 01:10 |
| LIV-04 | Record and label 5 clips with the camera chosen at G2 | E | todo | XR-02 | `eval/phase2/labels.json` committed | | |
| LIV-05 | `replay.py` eval and threshold tuning → G4 | E | todo | LIV-03, LIV-04 | Metrics recorded in §4 | | |
| LIV-06 | Gemini Live client (audio worklet, playback, interrupts, tools, event injection) on phone **and** headset | E | todo | INF-06 | Live speaks an injected warning in the headset | | |
| LIV-07 | Voice smoke: 20 background injections, 10 warnings, barge-in | E | todo | LIV-06 | 0 unprompted replies; warnings cite step and page | | |
| LIV-08 | Fallback voice: fixed phrases with TTS / speechSynthesis | E | todo | LIV-03 | Works with Live disabled | | |

### 3.8 Demo and submission (E leads from Sun 13:00)

| ID | Task | Owner | Status | Depends on | Done when | Evidence | Updated |
|---|---|---|---|---|---|---|---|
| DEM-01 | Record backup phase-1 footage (screen + phone) | E | todo | G3 | Files saved | | |
| DEM-02 | Pre-build the demo job on the host | C | todo | G3 | Job URL works by QR | | |
| DEM-03 | Shoot the headset view + outside view for phase 2 | E | todo | G4/G5 | Clips saved | | |
| DEM-04 | Edit the 1-minute video (§11) | E + D | todo | DEM-01, DEM-03 | Exported MP4 | | |
| DEM-05 | Submit repo, video, details | A | todo | DEM-04 | Confirmation received | | |
| DEM-06 | Pitch rehearsal ×3 with a timer, fallback drill | all | todo | DEM-05 | Under 3:00 each time | | |

---

## 4. Test results (fill in as measured)

| Test | Plan ref | Result | Measured value | Who | When |
|---|---|---|---|---|---|
| Headless render on M4 (Workbench / EEVEE / Cycles) | §8.3 | pass | Workbench 0.11 s, EEVEE 0.31 s (3.3 s first run), Cycles 4 spp 0.10 s; all non-black; Freestyle drew no lines | planning agent | Sat 19:10 |
| Headless render on boxd, `uname -m` | §8.3 | | | | |
| CadQuery import on M4, Python 3.12 | §13 | pass | cold import 1.36 s; part build 35–101 ms; one process per part costs 1.6 s | planning agent | Sat 19:10 |
| Blender 5.3 GLB export with one clip per step | §5.4 | pass | 8 parts-steps, 39 nodes → 940 KB GLB, 8 animations, 0.77 s wall (`eval/bench/blender_assemble.py`) | planning agent | Sat 19:10 |
| Carbon structured output | §13 | | | | |
| Decision 1 latency p50 / p95 | §6.3 | fail | p50 802 ms, p95 1764 ms (limits 250 / 600) over 64 requests, 4 at a time, from a Mac on home wifi in Athens; 2 JPEG images at `medium` and 10 questions, 2,300 input tokens. One at a time: median 709 ms, best 462 ms. Not measured from the host | data owner | Sun 00:45 |
| Quest `immersive-ar` + hands + planes + anchors | §6.0 | | | | |
| Quest `getUserMedia` passthrough camera during XR | §6.0 | | | | |
| Quest mic + Gemini Live | §6.0 | | | | |
| Quest recording / casting includes our layers | §6.0 | | | | |
| Quest fps with the golden GLB | §6.0 | | | | |
| Live silent on `turnComplete:false` | §6.4 | | | | |
| Phase-2 replay accuracy / false alarms / latency | §9 | | | | |
| Phone heat after 15 min (if the phone is the camera) | §7 | | | | |
| End-to-end smoke through the server (golden guide, localhost) | §9 | pass | 20.4 s from upload to GLB; the three.js r187 load check (`eval/glb_check`) passes on the server-built job | A | Sat 23:10 |
| Server killed during a job | §8.1 | pass | `kill -9` while waiting for approval and again during `steps`: the job resumed, only `steps` ran again, event numbers stayed unbroken | A | Sat 23:12 |
| GLB load check with the three.js r187 loader (`eval/glb_check`) | §5.4 | pass | KNAGGLIG: 2.0 MB, 24k triangles, 38 meshes, `walkthrough` + 8 step clips, every action at its manifest time. Packed with gltfpack: 197 kB, same checks pass. In Node, not in a browser | data owner | Sat 23:00 |
| CadQuery sandbox self-test | §7.2 | pass | 11 of 11 on macOS: endless loop killed at the 4 s CPU limit, sleep at 6 s, memory hog (1.2 GB) in 1.7 s, 40 MB file refused; network, outside writes and `.env` reads refused. Not run on Linux, where those last three are not blocked | data owner | Sat 23:00 |
| Build agent on the golden guide, from the PDF alone | §9 | pass | 438 s, 33 turns, $2.12, 1 build; 37 pieces, 0.0 mm residual; scorer gates 1.00 / 1.00 / 1.00. One run. A run before it failed on two bugs of ours (fixed) | data owner | Sat 23:45 |
| Build agent on a second guide (LACK side table) | §9 | pass | 121 s, 21 turns, $1.03, 1 build; 9 pieces, 0.0 mm residual; gates 1.00 / 1.00 / 1.00, but 1 step where the hand label has 2 (one unnumbered drawing; flagged by the agent) | data owner | Sun 00:10 |
| Part pictures, reference sheets and crops | §5.4 | pass | 12 part pictures 1.2 s, 8 reference renders 0.8 s, 8 sheets (1536×768, ≤ 445 kB) 1.9 s, 8 crops at 300 dpi. Sheets not yet sent to Decision 1 | data owner | Sat 23:00 |
| cloudflared quick tunnel without an account | §13 | pass, with a limit | HTTPS, upload, REST and files work. **SSE is buffered: 0 bytes in 20 s.** Smoke passes in 50.6 s with long-polling | A | Sat 23:14 |
| First CadQuery import after an install | §13 | note | over 30 s once (bytecode compile), then about 1.4 s. The server waits up to 180 s before it reports CadQuery as missing | A | Sat 23:05 |
| Phone guide page on the demo host (headless Chrome, iPhone 13 emulation) | §7 | pass, emulated only | KNAGGLIG and MARKUS load through the public URL, step through four screens each, no console errors. Not yet on a real phone | A | Sun 05:40 |
| Phase-2 lab replay through the host's socket (stand-in render frames, real Decision 1) | §9 | pass, lab only | step 6 of KNAGGLIG: `step_complete` 3.5 s after the scene changed, decision p50 720 ms; 9 s of an unchanged scene: 0 events. No camera, hands or headset | A | Sun 05:30 |

---

## 5. Blockers

| Task | Blocked by | Who can unblock | Since | Resolved |
|---|---|---|---|---|
| SET-04, SET-05, SET-06 on the team MacBook Air | Disk had about 1 GB free; Blender, the CadQuery venv (1.3 GB) and node_modules need at least 5 GB | Laptop owner (free space), or move the pipeline to boxd / another laptop | Sat 19:10 | |
| SET-01, SET-02, INF-06 | No `GEMINI_API_KEY` on A's laptop, and Tier 1 is not confirmed | A (in person): link billing in AI Studio, put the key in `.env`, run `make models` | Sat 23:15 | |
| SET-03, INF-05, G1 on boxd | boxd CLI is not installed or logged in on A's laptop | A (in person): install the CLI, `boxd auth login`, add a payment method, run `make deploy-box` | Sat 23:15 | Sun 03:12: boxd closed; hosting on A's Mac instead |

---

## 6. Decisions log

Decisions made during the build. Copy plan-changing ones into `docs/PLAN.md` §1.

| When | Decision | Why | By |
|---|---|---|---|
| Sat 19:30 | Quest 3 is the primary phase-2 display; phone is the fallback (D11) | Instructions in place, at true scale, beat a small screen | team |
| Sat 19:30 | Headset camera decided by test G2; phone on a stand otherwise (D12) | WebXR gives no passthrough pixels | team |
| Sat 20:00 | Golden guide = IKEA KNAGGLIG box; smoke = LACK side table; stretch = BEKVÄM, KALLAX, Sauder | Reviewed the PDFs: KNAGGLIG has 8 clear steps, board-only parts, and hardware counts that cross-check | team |
| Sat 21:00 | Steps are split into ordered actions (`turn`, `place`, `fasten`) in the GLB and the manifest; the root node `assembly` turns the whole assembly (PLAN §4.3) | The guide tells a person one action at a time: this screw, here, then the next | team |
| Sat 20:15 | Stay on vanilla three.js; reuse small scaffolding modules with a neutral credit (D14) | Reference has no WebXR or step playback; reusable parts are plain three.js | team |
| Sat 20:15 | Brand: `docs/design/BRAND.md` + `tokens.css` (D15) | One consistent look across phone, XR and voice | team |
| Sat 20:00 | Unlabelled IKEA parts cite page + drawing box with descriptive ids; warning pages filtered out; pre-assembled frames are single parts | Seen in the real guides (PLAN §0, §5.1, §5.2) | team |
| Sat 23:15 | Build feed: SSE stays the main path; `GET /api/jobs/{id}/events/poll?since=&wait=` is the fallback, and the client switches to it when the stream stays silent (D16) | A trycloudflare quick tunnel delivers no SSE bytes until the stream ends (measured). boxd is not tested yet | A |
| Sat 23:15 | `/healthz` without the demo key answers only `{ok: true}`; with the key it gives the full state (D17) | A client can tell "server down" from "wrong key", and nothing leaks | A |
| Sat 23:15 | The Live token locks the model, prompt, tools and voice-activity settings but not `session_resumption`; the reply adds `api_version` (v1beta tried first, then v1alpha) (D18) | A locked field could not carry the resume handle on reconnect; SDK 3.3 documents token creation as v1alpha only. Not tried against the API yet | A |
| Sat 23:20 | Manifest and job folder gain `glbPacked`, `cropSource`, `refs/step-NN-sheet.json` and the `part-<id>.png` / `-shaded.png` names; the CadQuery sandbox uses a fresh process per script (D19) | Additions made while finishing CAD-03/06/07/09; nothing a reader of the old contract relied on was renamed | data owner |
| Sat 23:50 | Phase 1 plan and parts are written by one agent on the Claude Agent SDK (`claude-opus-5-5`); phase 2 stays Gemini (D20) | Bake-off: the agent finished KNAGGLIG twice with all gates passed; one large Hydrogen call failed four times (a Hydrogen loop was not tried) | team, recorded by the data owner |
| Sat 23:15 | Until AGT-02 lands, a known test guide (matched by the PDF's sha256) uses its hand-checked plan and part scripts from `eval/golden/`, and the feed says so. Any other guide stops at `extract_plan` | D and E get a real server flow tonight; gate G3 allows a hand-fixed plan | A |
| Sun 07:10 | Parts inventory route and event (D22) | The tedious job before building: count what is in the box against the guide | A |
| Sun 07:10 | `POST /api/jobs/{id}/ask` for questions and undo plans during assembly (D23) | The client voice is fixed phrases; the person could not ask, and nothing said how to undo a mistake | A |
| Sun 07:10 | Build agent gets an independent `./check` against the guide's drawings, 3 fix rounds then flag (D24) | The agent graded its own step renders; now a second model checks them | A |
| Sun 06:10 | New server routes beyond PLAN §4.5: `GET /api/catalog`, `POST /api/catalog/open`, photos in `POST /api/jobs` (`pages`), and an MCP server over the HTTP API (D21) | One app for everyone: find a guide by name or product code, or photograph the paper guide; agents can drive it. Additions only, nothing renamed | A |

---

## 7. Activity logs

Append one line per update, newest at the bottom of **your own** log only: `Sat 21:40 · TASK-ID · what changed · evidence`.

### Log A
- Sat 23:15 · SET-10, INF-01 to INF-04, INF-07 · backend built and pushed: FastAPI app, demo key, job folders, `events.jsonl` with SSE and long-poll, a stage runner with `.done` markers, every PLAN §4.5 route, the smoke test; 11 tests pass · `9b22d55`, `6322b58`, `f3db491`
- Sat 23:15 · for D · the API and event shapes as built are in `README.md`. SSE messages have no `event:` name, so one `onmessage` gets everything. `stage` in `GET /api/jobs/{id}` and in events is one of `ingest, extract_plan, await_approval, parts, place, steps, refs, export, done`; `status` is one of `queued, running, waiting_approval, done, error`. `POST /api/jobs` still answers `stage: "ingesting"` and approve answers `stage: "building"`, as PLAN §4.5 says. Through `make tunnel` SSE is buffered: fall back to `/events/poll`. `make dev` then `make smoke` gives you a finished job to load
- Sat 23:15 · for B · agent hooks are in `server/orchestrator/stages.py`: `pipeline/agent/extract.py` with `async def extract_plan(job_dir, emit)`, and `pipeline/agent/parts.py` with `async def build_parts(job_dir, emit)`. A subprocess can print `EVENT {json}` and `RESULT {json}` lines instead. The uploaded guide is `jobs/<id>/guide.pdf`; the 200 dpi pages and their text are in `pages/`, listed in `pages/index.json`
- Sat 23:15 · for C · the server runs the pipeline stage by stage (harness, fit check, `assemble.py`, `refs`, then `pack` and `crops`), not through `pipeline/run.py`. A test builds KNAGGLIG both ways and fails if the server makes fewer files, so a new step in `run.py` also needs a line in `server/orchestrator/stages.py` or `pipeline_cli.py`. `crops.build` gets a copy of the plan whose `guide` is the uploaded PDF
- Sat 23:15 · for E · put the perception WebSocket in `server/perception/routes.py` as `router`; the app includes it when the module exists, and `server.auth.ws_has_key(ws)` checks the demo key. `POST /api/live/token` is written but not tried against the API. The client must send `sessionResumption` itself (D18)
- Sat 23:15 · tools on A's Mac (M4 Pro) · Python 3.12 venv, Blender 5.3.0, cloudflared 2026.11.0, gitleaks and gltfpack (`npm ci` in `eval/glb_check`) are installed. The boxd CLI is not
- Sat 23:25 · INF-01 to INF-04 · hardened after an independent review of `server/`: the demo key is checked before an upload is read (also on `/ws`), plan scripts must be `parts/<name>.py`, a crashed server's leftover Blender is stopped on restart (tested with `kill -9` during `steps`), queue time no longer counts against the 40-minute budget, stage processes do not inherit secrets; 14 tests pass · `1abcaa0`, `cecaaea`
- Sat 23:45 · CLI-01, CLI-04 (D's rows, not edited) · A's coding agent is building the phone walkthrough in `client/` on A's Mac: per step "gather the parts / build action by action / check", free orbit, screw inventory, EL and EN. It only reads the pipeline's GLB and manifest and edits nothing in `pipeline/`. Not committed yet. Anyone about to start a client: talk to A first, so it is not built twice
- Sat 23:45 · XR-01, XR-02 preparation (D's rows, not edited) · started a headset check page, `client/public/xr-check.html`, that measures the open Quest items in one visit (granted features, hands, planes, anchors, hit test, depth, camera frames before, during and after an `immersive-ar` session for G2, microphone) and a fact check of the items PLAN §6.0 marks UNVERIFIED against current sources. In progress; no headset result yet
- Sat 23:45 · INF-08 · checked the repo against the hackathon pages (submit repo, demo video and project details by Sun 18:00; judged on usefulness, craft and aura; judges look at what was built in the window). Open points for the team: the GitHub repo is private, so the organisers cannot open it yet; 14 guide PDFs (26 MB) are tracked since `b6057a9`, although `eval/guides/README.md` says to commit only `fetch.sh`, which matters if the repo is made public; no agent reads a guide yet (AGT-02 to AGT-07 are `todo`), while the README says one does; no video or project text exists yet. No secrets found in the tree or the history
- Sat 23:45 · for C · a job folder built from `pipeline.run` at about 23:03 fails `eval/glb_check` (step clips mistimed against the manifest's action times); the same command at 23:40 on current `main` passes. Rebuild any job made before the fix
- Sun 02:15 · LIV-01 (E's row, not edited) · for the data owner and E: a phase-2 socket route exists on A's Mac and is **not pushed yet**: `server/perception/` with `WS /ws/perception/{job_id}` as PLAN §4.5 (one session per job for several devices, binary frames with one ack each, a headset socket can be display, hands and camera at once), the current action sent by the display so the server knows which screw code is expected now, confirming and correcting events with ready Greek and English sentences and the `[PERCEPTION …]` line, a simulated mode for client work without a key, and the protocol in `server/perception/README.md`; 33 tests, no real Decision 1 call made (no key on this Mac). It was written at the same time as `pipeline/perception/` and overlaps it (questions, thresholds, N-of-M). It is being reworked now so the real path uses `pipeline.perception`'s request, question files, measured thresholds and concurrency; nothing under `pipeline/perception/` is edited
- Sun 02:15 · INF-07, AGT-02 · `eval/smoke.sh` on KNAGGLIG through the server with `AGJ_BUILD_AGENT=claude` on A's Mac: PASS in 409.1 s (upload to `approval_required` 392.5 s, 41 events over SSE, GLB 1.94 MB with `walkthrough` + 8 step clips; job `1115-0022-2d4ab6`). Uses this Mac's Claude login, not an API key
- Sun 02:15 · CLI-01, CLI-04, XR-02 (D's rows, not edited) · A rejected the first phone walkthrough as too busy. Three full-screen mockups were built and A chose "workshop at night" (`client/mockups/workshop/`: one 3D scene, two buttons, one short line of words, remaining parts hovering at the top with their counts). The real page is being rebuilt on it; not pushed yet. `client/public/xr-check.html` (headset check, posts its report to `POST /api/diag`, new route) is finished and has run under Quest 3 emulation only: no headset was available tonight, so G2 has no result
- Sun 02:15 · for C · the client now decides each surface from the mesh's GLB material name (grain only on wood). The manifest carries no material per part, and `pipeline/blender/assemble.py` knows only `pine` and `steel`: any other `material` in a plan is drawn in pine's colour
- Sun 02:55 · AGT-05 (B's row, not edited), for B and C · new guides beyond the repo: the build agent finished the IKEA MARKUS **office chair** (official PDF, 11 steps, casters, gas lift, tilt mechanism, 11 bolts) and the BEKVÄM step stool, first build passing both times ($2.24 and $2.84). The walkthrough put the seat on the base at step 4 because the pipeline had no sub-assemblies. Added them in `02d32cc`: plan `subassemblies`, adds with `into`, a joining add; the group is built beside, turned as the guide draws it, then set in place in one move (BRIEF.md and pipeline/README.md). Also new shape helpers in `pipeline/cad/lib.py` (cushion, turned, radial, curved_panel, bent_rod, tube, cylinder, rounded_box) and an `approximate_shape` flag. Re-run on MARKUS from the PDF alone: the agent wrote the seat unit as a sub-assembly by itself and the chair looks like a chair; 12 min, $2.94. KNAGGLIG and LACK placements unchanged; 33 tests pass. For D: manifest steps gain `subassembly`, `stagedPoses`, `joins`; a joining action is `place` with `nodes`
- Sun 03:12 · INF-05, G1 · the demo is hosted on A's Mac: boxd sign-ups are closed. `deploy/mac_host.sh` runs from `.demo-host/` (a clean worktree of `main`, so local uncommitted work in the main checkout is not served). Smoke PASS through the public URL in 48 s. The phone and headset need the URL from `jobs/_host/url.txt` plus `?key=`. The Mac must stay plugged in and online until the pitch; the plan says to put it on a phone hotspot, not venue Wi-Fi. If someone gets a Google Cloud or boxd account, `deploy/remote_setup.sh` works on any Ubuntu x86_64 VM
- Sun 04:30 · CLI-01, CLI-04, LIV-01, LIV-06 (D's and E's rows, not edited) · pushed: the guide page for phone and laptop at `/` (`client/src/guide/`), live guidance in `client/src/assist/` (camera frames to `WS /ws/perception/{job_id}`, spoken corrections without the six-digit codes), the perception socket in `server/perception/` running `pipeline.perception`'s request, Stream and Watcher, `POST /api/diag`, and `/xr-check.html`. The Quest client in `client/src/xr/` stays exactly as pushed in `9302397`, at `?mode=xr`; a second Quest client built in parallel on A's Mac was dropped. Decisions for §6, not yet logged: 4 decision calls in flight and 5 un-acked frames with a 9 s watchdog (D7 says 2 / 2 / 2 s), decision timeout 4 s (§6.5 says 800 ms), both from the data owner's latency measurements. 86 tests pass. Nothing has run with a real vision key, camera or headset
- Sun 05:10 · for everyone · added tonight (all on `main`, all tested, 86 tests pass): sub-assemblies (`02d32cc`); shape helpers and an `approximate_shape` flag; `press`, `tighten` and `notes` actions so no step is empty (`e39994c`); image-to-3D parts through fal for shapes CadQuery cannot draw (`generate` in the plan, `985d696`, tested with a real key); a drawing check that scores every step's render against the guide's drawing with Decision 1 and posts it in the feed (`0c65998`, `d046303`; its orientation answer proved unreliable and is not used); optional `size` on upload, passed to the agent as true scale; the MARKUS office chair as a second known guide (`4c36957`)
- Sun 05:10 · demo host · both known guides build through the public URL: KNAGGLIG 67 s, MARKUS 87 s, with step scores in the feed. The build agent re-ran MARKUS from the PDF alone with the new brief and used the seat sub-assembly, press-fit casters, tightening and notes on its own (610 s, $2.98); it did not choose fal for any part
- Sun 05:10 · keys · the Gemini and fal keys were printed once in A's coding session by a check command (not in git, not sent elsewhere). Rotate both after the demo
- Sun 05:45 · where the project stands (A's check) · phase 1 works end to end on the demo host for the known guides and, with the build agent, for new PDFs; its phone page passes in an emulated iPhone, not yet on a real one; the upload/feed/approve screen (`/?mode=build`) is being built in the other session. Phase 2: socket, logic, questions, voice token and the Quest client exist; a lab replay with the real key passes; nothing has run with a real camera, hands, the physical product or the headset (G2, G4, G5 pending). Demo jobs: KNAGGLIG `1115-0453-7ca85e`, MARKUS `1115-0505-4dcc90`. `deploy/redeploy_mac.sh` updates the host to `main`; README has a "Try it" table
- Sun 07:10 · INF-09/10/11 · agentic additions, server side only: parts inventory, ask-the-guide with undo plans, independent step check in the build loop; 162 tests pass. Client wiring for inventory and ask is not done · `86f58fc`, `2b9b248`, `21ac453`
- Sun 06:10 · INF-04 additions (D21) · on `main` and on the demo host, 126 tests pass: guide catalog (`GET /api/catalog?q=`, `POST /api/catalog/open {q, size?}`: a name, an IKEA article number or product URL opens the built guide at once or fetches the PDF from the maker's site and starts a build; checked live, `902.891.72` opens MARKUS); photo uploads (`pages` in `POST /api/jobs`: photos are flattened and cleaned into `guide.pdf`); MCP server `server/mcp_server.py` with 8 tools, driven once over stdio with a real client. Shapes are in `README.md`. The entry screen that uses them is not built yet: three mockups exist, the choice is with A
- Sun 07:00 · CLI (built by A, agreed with the session that owns the client) · the app now starts on the workbench (A chose mockup B): a shelf of flat-pack boxes from `GET /api/catalog`, search by name or IKEA code, scan with the camera where `BarcodeDetector` exists, upload through `?mode=build`; the ready screen (`?mode=ready&job=`) shows the finished model on the bench and links to the steps, to building with help (`&assist=1`) and to the Quest. `25e5b8e`. Checked in emulated iPhone through the public URL, no console errors
- Sun 07:00 · INF-05 · the trycloudflare tunnel died on Cloudflare's side at 06:38 while cloudflared kept retrying; `mac_host.sh` now replaces a dead tunnel after three failed checks (`d44debc`). Host restarted: the URL changed, read it from `.demo-host/jobs/_host/url.txt`
- Sun 07:30 · demo host at `1a7d734` (both sessions' work), checked through the public URL in emulated iPhone and desktop Chrome: workbench → box → ready, search by IKEA code, `?mode=build`, `?mode=ready`, `?job=&assist=1`, `?mode=xr` all open with no console errors; the headset's voice now comes from Gemini TTS through the server's new `POST /api/tts` (22 clips, all 200). Catalog: ikea.gr links work, ambiguous text answers 409 with the choices (`04c6fb0`). Still untested: a real phone, the Quest, the physical product
- Sun 08:15 · real colours and IKEA search · `GET /api/catalog/ikea?q=` finds IKEA products by name (international catalogue); a guide opened from IKEA keeps the maker's product photo and finish, the build agent reads per-part colours from it, Blender uses them (`3765593`, `a4aaa94`). MARKUS re-built that way (black frame and casters, dark grey seat; 618 s, $3.36) and saved as the known guide (`4e65835`); the demo job `1115-0505-4dcc90` is rebuilt from it. The host runs with `AGJ_BUILD_AGENT=claude` from `.env`, so a new IKEA product is built by the agent (about 10 min, $2–4 on this Mac's Claude login)

### Log B
-

### Log C
- Sat 20:00 · CAD-01/02/04/05/08 · 3D pipeline built and run on KNAGGLIG (`pipeline/`, `eval/golden/knagglig/`): plan → parts → fit checks → GLB in about 3 s (done by the planning agent on Simos's machine)

- Sat 20:20 · merge · rebased onto the data owner's commits (`51384ab`..`c6e79cd`); the pipeline's `plan.json` scores PASS on `eval/score_plan.py` against the hand-labelled expected plan (all gates 1.00, flips 1.00 after moving the turn-over to step 7)
- Sat 20:20 · naming · the pipeline calls the pre-assembled panels `side_panel` (long, 436 mm) and the loose slats `end_slat` (short, 310 mm, with the handles); the expected plan calls them `end_frame` and `side_slat`. Counts and steps agree. Which side is long needs a look at the real product
- Sat 21:00 · CAD-05/06 · the walkthrough now follows the guide action by action: the assembly is turned as each step draws it (upside down for 3–4, on end for 5–8), parts go in one at a time, each screw goes to its own hole and turns while driven in; the manifest lists every action with its target point and English/Greek text · `eval/golden/knagglig/out/`
- Sat 21:00 · CAD-05 · Hydrogen read the KNAGGLIG drawings (205 s, one call) and disagrees with the model in two places, both flagged in `plan.json`: step 4's long screws look driven down through the flat panel into the upright ends, and the cover suggests the battens sit under the box. Needs the real product.
- Sat 21:50 · AGT (bake-off, for B) · same brief and guide pages to both: headless Claude Code built the KNAGGLIG plan and 6 part scripts that pass the fit checks and `score_plan.py` in 504 s ($2.74); Gemini 4 Hydrogen as one call per round returned no usable answer in 4 tries (thinking used the whole output limit). No decision taken yet on the model for the build loop · `eval/bakeoff/README.md`, `pipeline/agent/BRIEF.md`
- Sat 22:50 · CAD-03/07 · (data owner) `pipeline.run` now ends with part pictures and phase-2 reference sheets (`e3db37c`). Sheet labels are the part ids and hardware codes, listed per step in `refs/step-NN-sheet.json` so AGT-07 can use them as answer keys. In the manifest, `refRender` now points to `refs/step-NN-ref.png` as PLAN §4.3 says (it pointed to the guide-angle check render), and `refSheet` and `thumb` are filled. The committed `eval/golden/knagglig/out/` was not regenerated
- Sat 23:00 · CAD-06 · (data owner) **step clips were wrong in every GLB so far**: the exporter slid each object's keys to time zero, so in `step_NN` all pieces moved together at the start. Fixed in `assemble.py` (`fc0c904`); `walkthrough` was never affected. `eval/golden/knagglig/out/` is regenerated, so D should take the new `walkthrough.glb`. A viewer that opens `walkthrough.packed.glb` (manifest `glbPacked`, new key) needs the meshopt decoder
- Sat 23:00 · CAD-06 · (data owner) step crops have no boxes to use yet (plan extraction is not built), so `crops.py` trims the page and splits it at its widest empty bands. Correct for all 8 KNAGGLIG steps by eye; a guess for other guides. Part crops are not made
- Sat 23:00 · CAD-09 · (data owner) wrote our own runner with `resource` limits instead of reusing the reference one (`28a870f`). `pipeline.run` still builds parts in-process; AGT-05 should call `sandbox.run_part`. On the Linux host it does not block network or file access

### Log D
-

### Log E
-


### Log Data (ground truth, evaluation, benchmarking)
- Sat 19:45 · AGT-01 · done: golden KNAGGLIG and smoke LACK expected plans, hand-labelled from the PDFs · `51384ab`
- Sat 19:45 · AGT-04 · doing: `eval/score_plan.py` (three gates, exit code) · `51384ab`
- Sat 19:45 · data · IKEA-Manual downloaded to `eval/datasets/` (git-ignored); `eval/ikea_manual_to_expected.py` writes 102 expected plans. The dataset has no hardware, labels or flips: use it for parts, counts, step order and geometry only · `51384ab`
- Sat 20:10 · LIV-04, LIV-05 · prepared, not started: `eval/phase2/p2.py` (frames at 4 Hz and 768 px, label check, G4 scoring), `labels.template.json` with the 5 KNAGGLIG clip scripts, README with the log format `replay.py` must write. Recording waits for the physical product (SET-09) and gate G2 · tested on a synthetic clip only
- Sat 23:15 · CAD-05 · preview video: `--callout` draws an arrow to the hole of the screw being driven with its code and count ("108560  3/8"), off by default · `c7988e2`. Tried on parts too and dropped it: an arrow to where a part goes read worse than the tint alone
- Sat 23:15 · proposal for D (XR) and E (perception), not a decision · how to show "which screw" in the headset:
  - **Virtual side:** draw the same arrow and label in the viewer at the current action's `target_m` (the manifest has it, with `code` and the action text). Once the GLB is aligned to the real base (XR-03), the arrow also points at the real hole. No vision needed.
  - **Real side:** the screws carry no printed code, so there is nothing to OCR. Telling long from short is the `hardware_in_hand` choice question, answered from the reference sheet (`refs/step-NN-sheet.png`, labels in `step-NN-sheet.json`).
  - **Highlighting the real screw:** locating a 3 cm screw in headset space from a 768 px frame at 4 Hz is not realistic this weekend. Cheaper and nearly the same for the user: a ring at the pinch point from Quest hand tracking, green when `hardware_in_hand` is the step's code, red with the right code shown when it is not. It lags by the decision latency (target p50 ≤ 250 ms, alert within 2 s).
- Sat 23:20 · docs · brought `pipeline/README.md`, `CREDITS.md` (gltfpack), PLAN §4.1/§4.3 and D19, and the SET-05, AGT-04 and §2/§4 rows here in line with what was built today
- Sat 23:50 · AGT-02/05/06 · the build agent is in `pipeline/agent/extract.py` (D20). It works in `<job>/agent/`, can only read and write there and run `./build`; a guard refuses every other command and path. To use it in the server: `export AGJ_BUILD_AGENT=claude` before `make run`, with a Claude login or `ANTHROPIC_API_KEY` on that machine. Not tried through the server yet, and only on KNAGGLIG. With it on, a known guide also goes to the agent, about 7 minutes and $2 per upload
- Sat 23:50 · for A · `requirements.txt` gained `claude-agent-sdk`; `pipeline/cad/harness.py` runs part scripts in the sandbox when `CAD_SANDBOX=1` (the agent's `./build` sets it, the server's `parts` stage does not yet)
- Sun 00:10 · AGT-02/05 · the build agent also finishes the LACK side table from its PDF: first build passes, 121 s, $1.03 · `eval/bakeoff/results/claude_sdk_lack/`. Two guides so far, both simple (boards and screws); nothing with cam locks, dowels or more than 10 parts tried
- Sun 00:30 · SET-01/02 (A's rows, not edited) · a Gemini key now works on the data owner's Mac: `make models` passes for all four models (Carbon 3.6 s, Hydrogen 1.2 s, Decision 1 742 ms, Live tool call after 1.7 s). The project is on Prepay: it answered 402 "prepayment credits are depleted" until $5 of prepaid credit was bought, even with eligible Cloud credits on the account. The key is only in that Mac's `.env`; A's laptop and the host still need one
- Sun 00:45 · AGT-07/LIV-02 (B's and E's rows, filled in by the data owner) · questions and the Decision 1 request exist (`94c1a86`, `pipeline/perception/`, usage in `pipeline/README.md`). Measured on KNAGGLIG with stand-in frames (renders from another side, no hands; `pipeline.perception.bench`, result in `eval/phase2/runs/synth-knagglig/bench.json`, not in git):
  - **Latency fails the limit**: p50 802 ms, p95 1764 ms against 250 / 600. Sending PNG on a new connection per request gave 2.5 s; JPEG on a kept-open connection gives 0.7 s. `low` resolution or 3 questions instead of 10 saved under 100 ms. The rest is the round trip and the model. For E: plan for about 1 frame per second per connection, or several connections; the N-of-M windows in PLAN §6.2 (for example 8 of 10) then take 10 s, not 2 s
  - **`step_complete` does not reach 0.85**: on the frame of the finished step it answered 0.17 to 0.86 (only step 6 passed 0.85). On the frame just before the step it answered 0.06 to 0.24. In all 7 steps that have a "before" frame, the finished frame scored higher than the "before" frame. A fixed 0.85 threshold would miss 7 of 8 completions here; a rise against the value at step start may work. Needs real clips to decide
  - **`orientation_ok`**: right side of 0.5 in 15 of 15, but correct poses score only 0.52 to 0.85 and two of three wrong poses 0.40 and 0.46. Too close to trust for `flip_needed` as is
  - **`part_in_hand` names a part when nobody holds one**: 44 of 64 frames with no hands. `hands_present` was below 0.5 in 64 of 64. LIV-03 must read `part_in_hand` and `right_part` only when `hands_present` is high
  - Not tested: hands, real light, a real camera, any guide other than KNAGGLIG
- Sun 01:10 · LIV-03/LIV-05 (E's rows; code by the data owner) · decision logic and clip replay are in `pipeline/perception/` (`logic.py`, `replay.py`; 26 unit tests pass in total). For E's socket route: keep one `Watcher` per job and step, call `feed(answers, ts)` per frame, send the returned events; `request.ask(job, step, jpeg_bytes)` takes the frame bytes from the socket. Tried end to end on a stand-in clip (4 render frames before step 6, then 14 after, 2 per second, real Decision 1 requests): `step_complete` fired once, 3.5 s after the scene changed; sequential latency 509 to 1413 ms. Open: with the measured answers (Sun 00:45) the 0.85 / 0.15 thresholds would rarely fire for `step_complete` and `flip_needed` on other steps; they can only be tuned on real clips (`replay.py --cached`). `eval/phase2/labels.template.json` still uses invented part names (`end_frame`, `side_slat`, `handle_slat`); the job's ids are `bottom_panel`, `side_panel`, `end_slat`, `end_slat_handle`, so labels must use those
- Sun 01:15 · AGT-02/05 · the build agent now also passes through the server: `AGJ_BUILD_AGENT=claude`, server on a local port, `eval/smoke.sh <base> <key> eval/guides/ikea/lack-side-table__AA-2606170-1.pdf` → PASS in 143.5 s (upload to `approval_required` 136.5 s, 33 events, SSE; agent 125 s, 20 turns, $1.06; GLB 0.19 MB with `walkthrough` + 1 step clip). The plan has 1 step where the guide could be read as 2, as in the CLI run. Still not tried: a harder guide (running now), the host, a machine without a Claude login
- Sun 01:25 · LIV-03 latency (E's row; code by the data owner, written and measured by a helper agent, tests re-run by me: 17 pass) · `pipeline/perception/stream.py` runs K Decision 1 requests at once and keeps only the newest waiting frame. For E's socket route: one `Stream` per job and step, `submit(jpeg, ts)` per frame, events from `subscribe()`. Measured with real requests on a stand-in clip (renders, no hands; step 5 for 2 s, then step 6), delay from scene change to `step_complete`, which needs 8 yes-frames:
  - 1 request at a time: 5.4 to 11.8 s, about 1 answer per second, and at 8 frames per second 85 of 97 frames are dropped
  - 2 at a time: 3.7 to 5.5 s
  - 4 at a time, 4 to 8 frames per second: 2.6 to 3.8 s, about 3.5 answers per second
  - 8 at a time, 8 frames per second (one run): 1.8 s, 6 answers per second
  - Request latency stayed at p50 0.7 to 1.1 s and p95 1.4 to 2.6 s at every K; no 429 or other error in about 400 requests
  - Not measured: warnings that need 4 of 5 frames (should be faster than `step_complete`, estimate only), the host, a real camera. G4 asks for event p50 ≤ 500 ms; with a 0.7 s request that cannot be met, so the gate needs a decision
- Sun 01:40 · AGT-07/LIV-03 (B's and E's rows; data owner, experiments by a helper agent, tests re-run by me: 33 pass) · `step_complete` separates better. Three changes: the left half of the reference sheet now draws the parts the step adds in orange (`refs/step-NN-added.png`; `step-NN-ref.png` stays plain), the question names what the step adds and says the camera may be on another side, and `gemini-4-hydrogen` writes the criteria from a "before" render next to the "after". In `logic.py`, `step_complete` now votes at ≥ 0.45 (was 0.85) and only when `orientation_ok` ≥ 0.4. Stand-in bench on KNAGGLIG, 15 pairs, before → after: finished frames 0.17–0.86 → 0.28–0.93, not-finished 0.06–0.24 → 0.14–0.34, best split 13/15 → 14/15 (at 0.43), latency p50 802 → 643 ms. Tried and dropped: before/after choice question, `score` question, before and after side by side on the sheet (worse)
  - Step 3 stays weak (finished frame 0.28 in two runs: the box is upside down and the new panel is underneath). The flow needs another way past a step: the user saying "done", or a pinch
  - `orientation_ok`: wrong poses scored 0.08–0.31, right ones 0.41–0.81, but one right pose just after a turn scored 0.13. `flip_needed` still uses ≤ 0.15 and was not retuned
  - Hydrogen's wording moves the values by about ±0.05 between runs. All of this is renders without hands, one guide; the thresholds are provisional until real clips exist. Whether an orange reference makes the model look for orange parts in a real frame is unknown
  - `pipeline.refs` takes 2 s longer (two more Blender renders). `-sheet.json` gained `added` and `before`
- Sun 01:55 · AGT-07 · for A: the refs stage (`python -m pipeline.refs`, which the server already calls) now also writes `refs/step-NN-questions.json` and `questions` on each manifest step, so a job built through the server is ready for phase 2 without a manual step. Default is template wording (0.01 s, no network). Set `AGJ_QUESTIONS=model` in the server's environment to have `gemini-4-hydrogen` write the criteria: 24 to 28 s more for the 8 KNAGGLIG steps, and that is the version the bench numbers above were measured with. Template wording has not been benchmarked on the orange sheet. A failure in this part is returned in the stage result and does not fail the build. 33 tests pass
- Sun 08:25 · AGT-02/05 (data owner) · one more guide through the build agent, not one the team tried: the Teknik 2-shelf bookcase (16 pages, 12 steps, cam locks). Session 469 s, 66 turns, $2.85, first build passed: 6 part types, 61 hardware pieces, 12 step clips, worst fit error 0.0 mm. The cover render matches page 1 by eye; the 12 steps were not checked one by one. `run.json` shows `final_build_ok: false` (the rebuild after the session failed, likely because another session was editing `pipeline/refs.py` at that moment, not confirmed); the same build passes when re-run. GLB 5.3 MB before packing, 416 kB packed · `eval/bakeoff/results/claude_sdk_teknik_bookcase/`
