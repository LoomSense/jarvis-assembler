# Handoff: where the project stands, and what a native Quest app needs

Status as of **Sun 15 Nov 2026, 10:00 Athens**. For the team, and for whoever builds the native Unity app for Quest 3. Numbers below were measured today unless marked UNVERIFIED.

---

## 1. Status

### Works end to end (on `main`)
| Area | What | Where | Evidence |
|---|---|---|---|
| Guide → 3D | PDF → plan → CadQuery parts → fit checks → animated GLB + manifest, one action at a time (place, fasten, turn, press, tighten) | `pipeline/` | KNAGGLIG 37 parts / 41 actions, MALM drawer 19 parts / 26 actions, all fit checks 0.0 mm |
| Build agent | Reads a new guide PDF and writes the plan + part scripts | `pipeline/agent/` | MALM drawer (steps 1–7 of AA-516949) built first try, ~10 min |
| Instruction text | Verb per hardware kind (screw / push dowel / drop-in-and-turn cam lock / press pin), correct Greek articles | `pipeline/blender/instructions.py` | `e32eadd`, 18 tests |
| Server | Jobs, SSE feed, catalog (`/api/catalog/open` fetches the IKEA PDF by name or article and builds it), TTS, Live token, perception socket | `server/` | 225 tests pass |
| Identify | `POST /api/identify`: one camera frame → which product (reads box/label text: name, article, AA- number; or matches a built job by picture) | `server/identify.py` | MALM guide page → `MALM desk`, 0.99, 3–4 s; KNAGGLIG render (no text) → matched by picture |
| Locate | `POST /api/perception/{job}/locate`: one frame → which step the real assembly is at (Decision 1 per step, best-fit order) | `server/perception/locate.py` | MALM renders: after step 1 → next 2, after step 5 → next 6, finished → done; ~1 s for 7 steps in parallel |
| Live checks | `WS /ws/perception/{job}`: step complete, wrong hardware, wrong orientation, spoken in EN/EL | `server/perception/` | Real key: p50 447 ms, p95 822 ms per frame; step_complete fired 2.5 s after the first frame |
| Headset (WebXR) | Glass panels, one action at a time, arrows / turning arrows / hole rings, parts card, name tags, poses and turns, voice (Gemini TTS), Greek | `client/src/xr/` | Desktop preview only |
| Headset scan flow | Coach 1/4–4/4: look at object → identify → (download + build guide) → locate step → touch two corners → guidance | `client/src/xr/scan.ts`, `register.ts` | Desktop preview with mocked answers (`?mock=scan`) |

### Measured accuracy (perception)
Decision 1 step_complete on the MALM drawer, clean renders, no hands (55 calls, `jobs/_verify/`): **83.7 % correct, 0 false "done"** at threshold 0.45. Weak steps: **step 3** (cam locks) scores 0.44 and never fires; **step 7** (runner screws) can fire falsely after a turn (0.69 on a "before" frame). Model-written questions (`AGJ_QUESTIONS=model`) gave the same accuracy with one false "done". Real camera, hands and light are **not measured yet**.

### Not verified on hardware (the biggest open risk)
- Nothing in `client/src/xr/` has run on a real Quest 3: passthrough camera permission inside the session, fingertip corner touch, anchors after the snap, 72 fps, voice in Quest Browser.
- Quest Browser exposes the Quest 3 passthrough cameras through `getUserMedia` from Browser 40.1, after the "Headset cameras" permission, according to developer reports (UNVERIFIED: no official Meta page found). `/xr-check.html` tests it; reports land in `jobs/_diag/`.

### Honest limits (say them if asked)
- **No automatic 6-DoF object tracking in WebXR.** The browser gives camera pixels but no camera pose or intrinsics in the XR frame, so the ghost cannot follow a moved object by itself. Registration is a two-corner touch plus WebXR anchors; re-snap after moving or turning the object. The camera is used for identify, locate and live checks. This is what the native app (§2) can fix.
- Guides are IKEA's; crops and PDFs stay out of git except the test guides already in `eval/guides/`.

### Demo hosting
`deploy/mac_host.sh` on a Mac (boxd sign-ups are closed). Public URL in `jobs/_host/url.txt`; every route needs `?key=<DEMO_KEY>` or the `X-Demo-Key` header.

---

## 2. Native Quest 3 app (Unity): what to reuse, what to add

Goal: the same guidance, plus **real-time tracking of the physical assembly** so the ghost stays on it when it moves or is turned. Everything server-side stays as is; the native app is a new client.

Native Android (Kotlin, Meta Spatial SDK) variant: `quest-android/` (build, install, launch, module map in `quest-android/README.md`; framework research in `quest-android/RESEARCH.md`).

### 2.1 Reuse as is
- **Server API** (all need the demo key):

| Call | Use |
|---|---|
| `GET /api/catalog?q=` · `POST /api/catalog/open {q}` → `{job_id, ready, product}` | find or build a guide by name / IKEA article / product URL |
| `GET /api/jobs/{id}` | build status and progress while a guide is built |
| `GET /files/{job}/manifest.json` · `/files/{job}/walkthrough.glb` · `/files/{job}/crops/step-NN.png` · `/files/{job}/renders/part-<id>-shaded.png` | the guide data |
| `POST /api/identify {image: base64 JPEG, lang}` | which product is in view |
| `POST /api/perception/{job}/locate {image, lang}` → `{next_step, done_through, steps[], message}` | which step the assembly is at |
| `WS /ws/perception/{job}?role=display&key=` | live step_complete / wrong_hardware / flip_needed; protocol in `server/perception/README.md` |
| `POST /api/tts {text}` → `audio/wav` | Gemini voice (EN/EL) |
| `POST /api/live/token` | Gemini Live voice session |

- **GLB conventions** (`pipeline/README.md`): metres, Y-up. Root node `assembly`; parts `part_<id>_<n>`, hardware `hw_<code>_<n>`, ring `marker`, cameras `cam_step_NN`. Clips: `walkthrough` (whole guide) and `step_NN`.
  - **Parts rest hidden (scale 1e-4).** Their final transforms exist only at the end of the `walkthrough` clip: sample the clip at its last frame and read each node's local transform under `assembly`.
  - Prefer `walkthrough.glb` over the packed one unless the loader supports `EXT_meshopt_compression` (glTFast does with its meshopt package; UNVERIFIED for your Unity version).
- **Manifest** (`manifest.json`): per step `title`, `cite.page`, `crop`, `hardware[{code,qty}]`, `assemblyRotationDeg`, and ordered `actions[]` with `type` (place, fasten, turn, press, tighten), `node`, `partId`/`code`, `text{en,el}`, `target_m`, `from_dir`, `t0_s/t1_s` in the step clip.
  - **`target_m`, `from_dir` and `assemblyRotationDeg` are in Blender's Z-up frame.** Convert to glTF/Unity Y-up: `(x, y, z)_blender → (x, z, −y)` for glTF; Unity is left-handed, so after the glTF importer's usual X flip check one action against the GLB.
  - `from_dir` points from the target towards where the part comes from.
- **Reference implementation of every visual**: `client/src/xr/ghost.ts` (actions, poses, turn loop), `stepGuides.ts` (arrows, turning arrows, rings, callouts), `jobAssembly.ts` (GLB + manifest → parts), `register.ts` (two-corner solver), `scan.ts` (scan flow).

### 2.2 Add in native (the reason to go native)
1. **Camera with pose.** Meta's Passthrough Camera API for Quest 3/3S gives the RGB camera image **plus its intrinsics and its pose** in the tracking space (UNVERIFIED for the exact SDK version; start from Meta's Unity passthrough-camera samples). That pose is what WebXR lacks.
2. **Object tracking**, in order of effort:
   - **a. Marker (fastest, most robust):** an ArUco/AprilTag sticker on the base part. Detect it in the camera image (OpenCV for Unity or a small native plugin), solve PnP with the intrinsics, transform with the camera pose → the assembly's 6-DoF pose every frame. Smooth it; fall back to the last pose when the marker is hidden.
   - **b. Model-based (no sticker):** render-and-compare or edge-based tracking against the GLB of the parts placed so far (we know them for every step). Bootstrap from the two-corner touch or the marker, then refine. Research-grade; plan days, not hours.
   - **c. Keep the two-corner touch** (port `register.ts`) as the fallback and for the first registration.
3. **Spatial anchors** (Meta XR SDK) for the table and the last good pose; **MRUK / scene** for the table plane.
4. **Hand tracking** (Meta XR SDK): same checks as `client/src/xr/handChecks.ts` (`hand_at_target`, `still`), and send hand summaries on the perception socket (`type: "hands"`, see its README).
5. **Frames to the server**: 768 px long edge, JPEG q0.7, at most `hello.limits.max_hz` and `max_unacked` from the socket's `hello` (today 4 Hz, 5 un-acked).

### 2.3 Suggested stack (UNVERIFIED versions; pick what installs cleanly)
Unity 6 LTS · Meta XR All-in-One SDK (passthrough, passthrough camera, hand tracking, anchors, MRUK) · glTFast (runtime GLB) · NativeWebSocket or similar · OpenCV for Unity (markers, PnP) · UnityWebRequest for REST.

### 2.4 First milestones
1. Load `/files/malm-drawer/manifest.json` + GLB, sample the walkthrough end, show step 1's ghost at a fixed pose. Check one `target_m` against the GLB to confirm the axis conversion.
2. Two-corner registration with hand tracking (port `register.ts`), anchors.
3. Passthrough camera → `POST /api/identify` and `/locate` with one frame; jump to `next_step`.
4. Marker tracking on the drawer front: ghost follows the drawer when it is moved or turned.
5. Perception socket at 4 Hz with hand summaries; voice through `/api/tts`.

Test jobs on the demo host: `malm-drawer` (7 steps; a real drawer exists for testing), `knagglig` (8 steps), `lack`.
