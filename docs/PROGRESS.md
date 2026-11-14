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
| A | | Backend and infra lead | INF-01 | todo | |
| B | | Agent and prompts | AGT-01 | todo | |
| C | | CAD and Blender | CAD-01 | todo | |
| D | | Phone client and Quest XR | CLI-01 | todo | |
| E | | Phase 2 perception, voice, demo story | LIV-01 | todo | |

### Gates (go / no-go checkpoints)

| Gate | When | Pass condition | Owner | Result |
|---|---|---|---|---|
| G1 Hosting | Sat 21:00 | Headless Blender render works on the chosen host (§8.3) | A + C | pending |
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
| SET-01 | Link Gemini billing, confirm **Tier 1**, create `GEMINI_API_KEY` | A | todo | – | Tier 1 shown in AI Studio | | |
| SET-02 | Smoke-call `gemini-4-carbon`, `gemini-4-hydrogen`, `gemini-decision-1` (`/v1beta/decisions`), and a Live session with an ephemeral token | A | doing | SET-01 | All four return without error | Key in `.env` lists gemini-4-carbon, gemini-4-hydrogen, gemini-decision-1, gemini-3.8-live; calls not yet made | Sat 20:00 |
| SET-03 | boxd login + payment method, create VM `agj`, run `uname -m` | A | todo | – | Architecture recorded here | | |
| SET-04 | Python 3.12 venv with cadquery, google-genai, fastapi, uvicorn, pymupdf, opencv-python-headless, numpy, pydantic-settings | all | todo | – | `python -c "import cadquery, fitz, cv2"` works on each laptop | | |
| SET-05 | Install Blender 5.3 on the Macs (A, C) | C | todo | – | `blender -b --version` prints 5.3 | | |
| SET-06 | Node + `three@0.187.0`, cloudflared | D | todo | – | `npm create vite` app runs on a phone via tunnel | | |
| SET-07 | fal key; treg token (optional) | A | todo | – | Keys in `.env` | | |
| SET-08 | Quest 3: charge, update, developer mode, Meta Quest Developer Hub, Immersive Web Emulator in Chrome | D | todo | – | Headset visible in `chrome://inspect` | | |
| SET-09 | Choose the golden guide and get the physical product to the venue | B | todo | – | PDF in `eval/golden/<name>/guide.pdf`; product owner named | | |
| SET-10 | Repo skeleton: layout (§8.4), `.gitignore`, `.env.example`, `CREDITS.md`, contracts from §4 | A | todo | – | Pushed to `main` | | |

### 3.2 Backend and infrastructure (A)

| ID | Task | Owner | Status | Depends on | Done when | Evidence | Updated |
|---|---|---|---|---|---|---|---|
| INF-01 | FastAPI app, `/healthz`, demo-key check, static client serving | A | todo | SET-10 | `/healthz` returns over HTTPS on the host | | |
| INF-02 | Job folder layout (§4.1), stage runner with `.done` markers and retry | A | todo | INF-01 | A job restarts and skips finished stages | | |
| INF-03 | `events.jsonl` writer + SSE `GET /api/jobs/{id}/events` with `Last-Event-ID` replay | A | todo | INF-02 | Phone feed reconnects without losing events | | |
| INF-04 | REST endpoints (§4.5): upload, status, plan, approve, retry, files | A | todo | INF-02 | All return the documented shapes | | |
| INF-05 | Hosting test G1 with C, then deploy | A | todo | SET-03, SET-05 | G1 recorded in §2 | | |
| INF-06 | `POST /api/live/token` (single-use, 30 min, config locked) | A | todo | SET-02 | Phone and headset open a Live session with it | | |
| INF-07 | `eval/smoke.sh` end-to-end | A | todo | INF-04 | Exit 0 on the golden guide | | |
| INF-08 | Secrets scan, README, CREDITS | A | todo | – | No keys in git history; CREDITS complete | | |

### 3.3 Agent and prompts (B)

| ID | Task | Owner | Status | Depends on | Done when | Evidence | Updated |
|---|---|---|---|---|---|---|---|
| AGT-01 | Hand-write `expected_plan.json` for the golden guide | B (done by the data owner) | done | SET-09 | File committed | `51384ab`: `eval/golden/knagglig/expected_plan.json` (4 part types, 9 pieces, 28 screws, 8 steps) and `eval/golden/lack-side-table/`. Labelled from the PDF, not yet checked on the physical product | Sat 19:45 |
| AGT-02 | Rasterise + Carbon structured extraction with `box_2d` (§5.1); fallback Hydrogen `xhigh` if Carbon rejects the schema | B | todo | SET-02 | `plan.json` for the golden guide | | |
| AGT-03 | Crops + box re-check + count validation + flags | B | todo | AGT-02 | Crops look right; mismatches flagged | | |
| AGT-04 | `score_plan.py` against the expected plan | B (scorer by the data owner) | doing | AGT-01, AGT-02 | Recall ≥ 0.9, counts exact ≥ 80%, Kendall τ ≥ 0.8 | `51384ab`: `eval/score_plan.py` written and self-tested; waits for a real `plan.json` from AGT-02 | Sat 19:45 |
| AGT-05 | Hydrogen part loop with tools (§5.6), 3 attempts, events logged | B | todo | CAD-02, CAD-03 | Golden parts pass or are flagged | | |
| AGT-06 | Hydrogen step-fix loop and flip choice | B | todo | CAD-05 | Golden steps pass or are flagged | | |
| AGT-07 | Per-step Decision 1 question writer for phase 2 (§6.1) | B | todo | AGT-02 | Questions JSON cached per step and valid | | |

### 3.4 CAD and Blender (C)

| ID | Task | Owner | Status | Depends on | Done when | Evidence | Updated |
|---|---|---|---|---|---|---|---|
| CAD-01 | Headless render test: Workbench → EEVEE → Cycles (G1 with A) | C | done | SET-05 | Engine chosen and recorded in §6 | Workbench on the M4 (see §4) | Sat 20:00 |
| CAD-02 | CadQuery harness `run_part.py` (STL, STEP, `mates.json`, missing-frame check) | C | done | SET-04 | 3 fixture parts export | `pipeline/cad/harness.py`, `lib.py`; 6 KNAGGLIG parts build in 1.3 s | Sat 20:00 |
| CAD-03 | `render_parts.py` batch with guide angle and line-art style | C | todo | CAD-01 | One Blender call renders N parts | | |
| CAD-04 | Placement code: mates → transforms, Kabsch, flip candidates (§5.3) | C | done | CAD-02 | Golden step 1–3 placed correctly | `pipeline/assemble/placement.py` + `collide.py`; 37 pieces, 0.0 mm residual; misfit and overlap tests caught | Sat 20:00 |
| CAD-05 | Scene + per-step NLA animation + `render_step.py` | C | done | CAD-04 | Step renders match the guide angle | `pipeline/blender/assemble.py`; `eval/golden/knagglig/out/steps.png` | Sat 20:00 |
| CAD-06 | GLB export (§5.4) + `manifest.json` (§4.3) + gltfpack | C | doing | CAD-05 | GLB loads in three.js with one clip per step, ≤ 5 MB | GLB + manifest done (400 KB, 8 clips, 23.5k triangles); gltfpack not applied; crops missing | Sat 20:00 |
| CAD-07 | Phase-2 refs: `step-NN-ref.png` and composite `step-NN-sheet.png` | C | todo | CAD-05 | Sheets exist for every golden step | | |
| CAD-08 | Hand-made two-step dummy GLB + manifest for D, tonight | C | done | SET-05 | D can play it on a phone | `eval/golden/knagglig/out/walkthrough.glb` + `manifest.json` (the real 8-step KNAGGLIG) | Sat 20:00 |
| CAD-09 | Sandboxed runner for LLM-written CadQuery (CPU, memory, file limits; killable), reusing the reference runner only if its owner agrees (PLAN §7.2) | C | todo | CAD-02 | A runaway script is killed within its limit | | |

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
| LIV-02 | Decision 1 request builder (step header + frame + composite sheet + questions) | E | todo | AGT-07, CAD-07 | One real request returns all answers | | |
| LIV-03 | Thresholds, N-of-M smoothing, hysteresis, state machine, cooldowns (§6.2) | E | todo | LIV-02 | Events emitted on recorded clips | | |
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
| Decision 1 latency p50 / p95 | §6.3 | | | | |
| Quest `immersive-ar` + hands + planes + anchors | §6.0 | | | | |
| Quest `getUserMedia` passthrough camera during XR | §6.0 | | | | |
| Quest mic + Gemini Live | §6.0 | | | | |
| Quest recording / casting includes our layers | §6.0 | | | | |
| Quest fps with the golden GLB | §6.0 | | | | |
| Live silent on `turnComplete:false` | §6.4 | | | | |
| Phase-2 replay accuracy / false alarms / latency | §9 | | | | |
| Phone heat after 15 min (if the phone is the camera) | §7 | | | | |

---

## 5. Blockers

| Task | Blocked by | Who can unblock | Since | Resolved |
|---|---|---|---|---|
| SET-04, SET-05, SET-06 on the team MacBook Air | Disk had about 1 GB free; Blender, the CadQuery venv (1.3 GB) and node_modules need at least 5 GB | Laptop owner (free space), or move the pipeline to boxd / another laptop | Sat 19:10 | |

---

## 6. Decisions log

Decisions made during the build. Copy plan-changing ones into `docs/PLAN.md` §1.

| When | Decision | Why | By |
|---|---|---|---|
| Sat 19:30 | Quest 3 is the primary phase-2 display; phone is the fallback (D11) | Instructions in place, at true scale, beat a small screen | team |
| Sat 19:30 | Headset camera decided by test G2; phone on a stand otherwise (D12) | WebXR gives no passthrough pixels | team |
| Sat 20:00 | Golden guide = IKEA KNAGGLIG box; smoke = LACK side table; stretch = BEKVÄM, KALLAX, Sauder | Reviewed the PDFs: KNAGGLIG has 8 clear steps, board-only parts, and hardware counts that cross-check | team |
| Sat 20:15 | Stay on vanilla three.js; reuse small scaffolding modules with a neutral credit (D14) | Reference has no WebXR or step playback; reusable parts are plain three.js | team |
| Sat 20:15 | Brand: `docs/design/BRAND.md` + `tokens.css` (D15) | One consistent look across phone, XR and voice | team |
| Sat 20:00 | Unlabelled IKEA parts cite page + drawing box with descriptive ids; warning pages filtered out; pre-assembled frames are single parts | Seen in the real guides (PLAN §0, §5.1, §5.2) | team |

---

## 7. Activity logs

Append one line per update, newest at the bottom of **your own** log only: `Sat 21:40 · TASK-ID · what changed · evidence`.

### Log A
-

### Log B
-

### Log C
- Sat 20:00 · CAD-01/02/04/05/08 · 3D pipeline built and run on KNAGGLIG (`pipeline/`, `eval/golden/knagglig/`): plan → parts → fit checks → GLB in about 3 s (done by the planning agent on Simos's machine)

### Log D
-

### Log E
-


### Log Data (ground truth, evaluation, benchmarking)
- Sat 19:45 · AGT-01 · done: golden KNAGGLIG and smoke LACK expected plans, hand-labelled from the PDFs · `51384ab`
- Sat 19:45 · AGT-04 · doing: `eval/score_plan.py` (three gates, exit code) · `51384ab`
- Sat 19:45 · data · IKEA-Manual downloaded to `eval/datasets/` (git-ignored); `eval/ikea_manual_to_expected.py` writes 102 expected plans. The dataset has no hardware, labels or flips: use it for parts, counts, step order and geometry only · `51384ab`