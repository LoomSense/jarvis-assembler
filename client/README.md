# Client (phone + Quest 3)

Vite + TypeScript + vanilla three.js `0.187.0` (PLAN §7, D14).

```bash
npm install
npm run dev            # http://localhost:5173/?mode=xr  (desktop preview)
npm run typecheck
```

## Quest 3 (`?mode=xr`, PLAN §6.0)

WebXR needs a secure context. Pick one:

| Route | Commands | Open in Quest Browser |
|---|---|---|
| USB (fastest) | `npm run dev`, then `npm run quest` (`adb reverse`) | `http://localhost:5173/?mode=xr` |
| Tunnel | `npm run dev`, then `npm run tunnel` | the `https://….trycloudflare.com/?mode=xr` URL it prints |
| LAN | `npm run dev:https` | `https://<laptop-ip>:5173/?mode=xr` (accept the self-signed cert) |

Debug: headset on USB with developer mode, then `chrome://inspect` on the laptop. Without a headset, use Meta's Immersive Web Emulator extension in desktop Chrome.

**Flow in the headset:** look at the table → pinch to place the build plate (hit-test, else detected planes, else "Place in front of me") → drag and turn the plate onto the real base → Done → the ghost of the next part, with Back / Repeat / Next on the panel. The plate is anchored, and persisted across sessions where Quest Browser allows it.

**Desktop preview:** orbit with the mouse, click the panel buttons, keys `1`–`4` switch the ghost state (next / correct / wrong / unsure), `→` presses the screen's main button, `←` goes back. Point the mouse at a part to see its name.

**Guide flow:** "Before you start" tips the guide draws → parts check ("Do you have everything?") → one action at a time (one part or one screw), each with an arrow (push) or a turning arrow (twist), a callout with the part's name and code, and a "Pick up now" card with the part in 3D and the parts list. Progress is saved per product (localStorage) and resumes after a reload.

### URL parameters

| Parameter | Effect |
|---|---|
| `job=<id>` | Load a pipeline job (`manifest.json` + GLB) from `/files/<id>/`, the server's route. The dev server mirrors it from `<repo>/jobs/<id>/`. Add `key=<DEMO_KEY>` against the real server. |
| `sim=1` | No real furniture: placed parts are solid, a "Show mistake" button plays the wrong-part warning. Defaults the product to the LACK side table. |
| `product=lack\|knagglig` | Built-in hand-made product when no `job` is given. |
| `lang=el` | Greek UI, step text and voice. |
| `voice=0` | No speech. Otherwise Gemini TTS via the dev server's `POST /api/tts` (key in the repo-root `.env`, never sent to the browser), falling back to the browser's voice. |
| `auto=0` | No hands-free confirm (hand held still on the part for 1.5 s = Next). |
| `debug=1` | Tracking diagnostics (`planes · hit-test · anchor · hands`) in the panel footer: evidence for XR-01. |
| `hands=0` | Hide the hand joint spheres. |

To build a job: `.venv/bin/python -m pipeline.run eval/golden/knagglig/plan.json jobs/knagglig` (needs Blender 5.3 and the pipeline venv, see `pipeline/README.md`). Guide crops for the built-in products: `scripts/demo-crops.sh`, `scripts/lack-crops.sh` (git-ignored: the guides are IKEA's).

## Files

| File | What |
|---|---|
| `src/xr/xrMain.ts` | Scene, input, modes (place → align → guide), panel content |
| `src/xr/session.ts` | Session features, detected-plane outlines |
| `src/xr/placement.ts` | Reticle, build plate, drag/rotate, anchors (persistent where supported) |
| `src/xr/ghost.ts` | Assembly view: steps split into actions, placed / ghost / upcoming parts, poses and turns, insertion loop, badges, screw shapes |
| `src/xr/stepGuides.ts` | Per-action arrows (push), turning arrows (twist), hole rings, callouts |
| `src/xr/partsCard.ts` | "Pick up now" card with the part in 3D and the parts list with progress; name tags |
| `src/xr/jobAssembly.ts` | Loads a pipeline job (manifest + GLB) into the same steps and actions |
| `src/xr/speech.ts` | Voice: Gemini TTS through `/api/tts` with prefetch, browser voice as fallback |
| `src/xr/i18n.ts` | English and Greek strings, Greek part names |
| `vite.config.ts` | Dev server: `POST /api/tts` (Gemini TTS) and `GET /files/<job>/…` (job files) |
| `src/xr/panels.ts` | Canvas-texture step panel and pinch buttons (BRAND.md §5) |
| `src/xr/handChecks.ts` | `hand_at_target`, `still`, `raised` at 10 Hz (WebSocket wiring waits for LIV-01) |
| `src/xr/demoAssembly.ts` | Hand-made KNAGGLIG box and LACK side table (sizes estimated), guide tips |
