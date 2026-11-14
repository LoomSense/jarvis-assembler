# Mobile client: viewer, camera, sensors, audio

Owner: mobile/front-end engineer. Target: a single web app that runs in mobile Chrome (Android) and Safari (non-Pro iPhones), opened by a QR code, with no install.

Sources were checked on 2026-11-14. Anything marked **UNVERIFIED** could not be confirmed against an official page and must be tested on a real device before Sunday 12:00.

---

## 1. Stack decision

| Choice | Decision | Why |
|---|---|---|
| Build | **Vite + TypeScript** | Fast HMR, `vite build` gives static output for Vercel. |
| UI framework | **Vanilla TS + a small DOM helper (no React)** | The UI is about 5 screens and a few buttons. three.js owns the canvas, and React adds re-render traps (for example a mixer recreated on each render) with no benefit at this size. If a teammate is much faster in React, keep three.js outside React in a plain `Viewer` class and call it from `useEffect`. |
| PWA | **No service worker. Add a `manifest.webmanifest` only (name, icon, `display: standalone`)** | A service worker cache is a classic source of "the judge sees a stale build" bugs. The manifest alone allows add-to-home-screen if we want it. |
| 3D | **three.js r187** (latest release, 30 Oct; https://github.com/mrdoob/three.js/releases) | Pin the exact version: `npm i three@0.187.0`. |
| three modules | `GLTFLoader`, `MeshoptDecoder` (from `three/examples/jsm/libs/meshopt_decoder.module.js`), `OrbitControls`, `AnimationMixer`, `RoomEnvironment` for cheap lighting | Meshopt decodes faster than Draco and also compresses animation data. Use `DRACOLoader` only if the backend team ships Draco. |

**three.js compared with `<model-viewer>`**

| | three.js | `<model-viewer>` |
|---|---|---|
| Play one named clip once and clamp | Yes (`AnimationAction`) | `animation-name` + `play({repetitions:1})` (UNVERIFIED that it clamps cleanly) |
| Scrub a clip | Yes (`action.time`) | Only through `currentTime` |
| Highlight one node (emissive, dimming others) | Full material access | Limited (Materials API, no per-node access) |
| Camera per step, from GLB cameras | Yes | No |
| Effort | More code | Almost zero |

**Recommendation: three.js.** Step-specific highlighting and per-step cameras are core to the demo, and `<model-viewer>` fights us on both. Fallback: if the viewer is not working by Sunday 10:00, drop in `<model-viewer>` with `animation-name` per step so the demo still has a 3D view.

---

## 2. Step playback design

### GLB contract with the backend (agree this tonight)

- One `AnimationClip` per step, named `step_01`, `step_02`, and so on, in the guide's order.
- Each part is a node with `name = part_<ID>` (for example `part_B`) and `extras: { partId: "B", label: "Side panel" }`.
- Optional step cameras named `cam_step_01`, and so on. If a camera is missing, the client frames the bounding box of the parts that move in that clip.
- `manifest.json` next to the GLB:

```json
{ "jobId": "abc", "glb": "model.glb",
  "steps": [ { "n": 1, "clip": "step_01", "title": "Attach B to A",
    "pickParts": ["B"], "hardware": [{"id":"104321","qty":4}],
    "crop": "crops/step_01.jpg", "refRender": "renders/step_01.jpg",
    "camera": "cam_step_01" } ] }
```

### Playback

- When step N is selected: `mixer.stopAllAction()`, then apply the end pose of steps 1..N-1 (seek each earlier action to `clip.duration` and call `mixer.update(0)`). The scene then shows the assembly state before step N.
- Each step's action uses `setLoop(THREE.LoopOnce, 1)` with `clampWhenFinished = true` (https://threejs.org/docs/#api/en/animation/AnimationAction).
- **Play/pause:** `action.paused = !action.paused`. **Scrub:** an `<input type=range>` sets `action.time = t * clip.duration; mixer.update(0)`. **Replay:** `action.reset().play()`.
- Listen for `mixer.addEventListener('finished', …)` and show the "Next step" button.

### Highlighting the part to pick up

- `OutlinePass` (EffectComposer) costs a full-screen pass or more on mid-range GPUs. **Skip it.**
- Instead, clone the material of the `pickParts` meshes and pulse `emissive` (amber, `emissiveIntensity` driven by a 0.3–1.0 sine). Set the other parts to `transparent: true, opacity: 0.35`. Restore the original materials on step change. This costs no extra passes.
- Phase 2 reuses the same highlight call when Decision 1 reports "next part".

### Camera transitions

- Tween `camera.position` and `controls.target` over 600 ms (ease-in-out lerp in the render loop, no tween library).
- The target pose comes from `cam_step_NN` or from a bounding-box fit.
- Free orbit stays enabled. A "re-center" button returns to the step's camera.

### Portrait layout

```
+-----------------------+
| Step 3/12  ‹  ›   🔊  |  header 48px
+-----------------------+
|                       |
|     3D canvas         |  ~58vh, touch → OrbitControls
|                       |
+-----------------------+
| [guide crop  ]  hw:   |  ~30vh: crop (object-fit: contain, tap = fullscreen zoom)
| [ image      ]  4×104 |        + hardware chips
+-----------------------+
| ▶ ━━━━●━━━━  ⟲        |  controls 56px, thumb reachable
+-----------------------+
```

In landscape, a CSS grid places the crop and canvas side by side (`@media (orientation: landscape)`). Use `100dvh`, not `100vh`, because of the mobile address bar. Set `touch-action: none` on the canvas only.

---

## 3. HTTPS serving

`getUserMedia` and the Wake Lock API require a secure context (https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia, https://developer.mozilla.org/en-US/docs/Web/API/Screen_Wake_Lock_API).

| Option | Setup | Pros | Cons |
|---|---|---|---|
| **cloudflared quick tunnel** (dev, recommended) | `cloudflared tunnel --url http://localhost:5173` | Real HTTPS cert, works on any phone, no account | Random URL on each restart. Vite HMR WebSocket through the tunnel can be flaky (UNVERIFIED), so reload manually. Recent Vite versions reject unknown Host headers, so add `server.allowedHosts: ['.trycloudflare.com']` (UNVERIFIED exact Vite version, https://vite.dev/config/server-options) |
| ngrok | `ngrok http 5173` | Stable, inspector UI | Free tier has an interstitial page and needs an account |
| mkcert + LAN | `mkcert` + `vite --host` + `@vitejs/plugin-basic-ssl` | No internet hop, lowest latency | The root CA must be installed on each phone, which is painful on iOS (Settings → certificate trust). Not viable for judges' phones |

**Demo:** deploy the client as a static site on **Vercel** (`vite build`, output `dist/`). The backend URL comes from `VITE_API_URL` at build time, with a fallback to the `?api=` query parameter so we can re-point it without redeploying.

- The backend (separate host) must send `Access-Control-Allow-Origin: https://<app>.vercel.app` (plus `http://localhost:5173` and `*.trycloudflare.com` in dev) for REST and the GLB.
- WebSockets are not covered by CORS, so the backend checks the `Origin` header itself.
- The QR code encodes the Vercel URL.

---

## 4. Camera capture (phase 2)

```ts
const stream = await navigator.mediaDevices.getUserMedia({
  video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 },
           frameRate: { ideal: 15, max: 30 } },
  audio: false });            // the mic is opened separately for Live (section 6)
```

- Use `ideal`, not `exact`, so the call still succeeds on laptops.
- Ask for 720p, not 1080p. That is enough for a 640 px upload and reduces heat.
- Set `<video playsinline muted autoplay>`. On iOS, `playsinline` is required or the video goes fullscreen.

**Frame grab options**

| API | Android Chrome | iOS Safari | Use |
|---|---|---|---|
| `ImageCapture.grabFrame()` | Yes | **No** (Chromium-only per MDN, https://developer.mozilla.org/en-US/docs/Web/API/ImageCapture; table not rendered, UNVERIFIED) | Don't use |
| `canvas.drawImage(video)` + `canvas.toBlob('image/jpeg', q)` | Yes | Yes | **Default** |
| `OffscreenCanvas.convertToBlob({type:'image/jpeg'})` | Yes | Baseline since March 2023 (https://developer.mozilla.org/en-US/docs/Web/API/OffscreenCanvas/convertToBlob). JPEG support per browser not listed, so check `blob.type` | Use in a worker if the main thread janks |

**Size and quality.** The Decision API sets token cost by its `resolution` parameter (low = 280 tokens, medium = 560, high = 1120), not by our pixel count. Uploading more pixels than the model uses only wastes bandwidth.

- Default: **long side 640 px, JPEG quality 0.7**, about 30–60 KB. Use `resolution: low` for "hands/part present" checks and `medium` for "step done / orientation" checks (the backend chooses).
- At 4 Hz that is about 200 KB/s upstream, which is fine on Wi-Fi and borderline on venue 4G.

**Transport.** Send frames over **one WebSocket to our backend**, as a binary frame with a short JSON header. Do not call the Decision API directly from the phone, for three reasons:

1. Ephemeral tokens are documented for the Live API only (https://ai.google.dev/gemini-api/docs/ephemeral-tokens). A direct call would expose the API key.
2. The backend already holds the reference render for each step.
3. The backend can log every frame for debugging.

**Backpressure: latest frame wins, at most one in flight.** With 150–250 ms Decision latency plus the network hop, 4 Hz with one request in flight is achievable. If a request is in flight when the tick fires, skip the frame. Never queue frames.

---

## 5. On-device gating with MediaPipe hand landmarker

- Package: `@mediapipe/tasks-vision`.
- Model: `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task`. Pin the WASM path to the installed version, not `@latest`.
- API: `HandLandmarker.createFromOptions(vision, { baseOptions:{ modelAssetPath, delegate:'GPU' }, runningMode:'VIDEO', numHands:2 })`, then `detectForVideo(frame, tsMs)`, which is synchronous and needs monotonic timestamps (https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker/web_js).

**Gating policy** (a small state machine in `gate.ts`):

| Hand state (8 Hz on-device) | Action |
|---|---|
| No hands for ≥ 1 s after activity | **Send a "step done?" frame** (`medium`). The user has stepped back, so the work area is unoccluded. This is the most valuable frame. |
| Hands present and still (wrist landmark moves < 2% of frame over 500 ms) | Send at 2 Hz with `low`, to check "right part / orientation" |
| Hands moving fast | Send nothing |
| Hand raised high in frame, holding something | Send immediately with `medium`, as a "holding a part up" check |
| No hands, nothing changed | Send at 0.5 Hz as a keep-alive |

This cuts API calls by roughly 3–5x compared with a fixed 4 Hz, and it sends better frames.

**Worker.** `detect*` blocks the thread (team fact), so run it in a **module Worker**: transfer an `ImageBitmap` from `createImageBitmap(video)` each tick.

- Known risk: `FilesetResolver` loading WASM inside module workers has had issues (UNVERIFIED). Fallback: load the classic `vision_bundle` in a classic worker, or run on the main thread at 5 Hz with `delegate:'CPU'`.
- GPU delegate in a worker needs `OffscreenCanvas` WebGL. If it fails, use CPU.
- **Cost on mid-range phones: UNVERIFIED.** Measure the ms per `detectForVideo` call in the worker on our slowest Android and an older iPhone. If it exceeds 60 ms, drop to 4 Hz and `numHands: 1`.

The gate is optional. Feature-flag it (`?gate=0`) so the demo still works if MediaPipe misbehaves on a judge's phone.

---

## 6. Audio for the Live voice agent

Formats (https://aistudio.google.com/docs/live-api/capabilities, the target of the live-guide redirect):

- **Input:** raw 16-bit little-endian PCM, 16 kHz native, MIME `audio/pcm;rate=16000`. The API resamples other rates.
- **Output:** raw 16-bit little-endian PCM at 24 kHz.
- **Interruption:** a server message with `interrupted: true` arrives. At that point we must stop playback and clear queued audio.

The browser connects **directly to Live** with an ephemeral token fetched from our backend (`POST /live-token`).

**Mic capture.** Call `getUserMedia({audio:{echoCancellation:true, noiseSuppression:true, autoGainControl:true, channelCount:1}})`.

- Use one `AudioContext` created at the device default rate (do not force 16 kHz; iOS support for a custom `sampleRate` is UNVERIFIED).
- An `AudioWorkletProcessor` downsamples to 16 kHz (simple averaging decimation is enough for speech), converts Float32 to Int16, and posts 20–40 ms chunks (640 samples at 16 kHz is 40 ms) to the main thread, which base64-encodes and sends them.
- **Playback:** decode Int16 to Float32, create an `AudioBuffer` with `sampleRate: 24000` (the browser resamples), and schedule back-to-back `AudioBufferSourceNode`s with a running `nextStartTime`. Keep the active sources in an array.
- **Barge-in:** on `interrupted`, call `stop()` on all sources and reset `nextStartTime`. Server VAD handles detection. Echo cancellation keeps the agent from interrupting itself, but test this on the loud-speaker phone on a stand.

**iOS quirks**

- Create or resume the `AudioContext` **inside the tap handler** of a "Start assembling" button (https://developer.mozilla.org/en-US/docs/Web/API/Web_Audio_API/Best_practices#autoplay_policy).
- While the mic is active, iOS may route output to the earpiece or lower its volume (UNVERIFIED for current iOS). Test, and recommend **wired or Bluetooth earphones** in the demo UI.
- iOS may suspend the context on interruption or when the app is backgrounded. Watch `ctx.onstatechange` and show a "tap to resume" overlay.

**Session limits.** Do not send video to Live: an audio+video session is capped at 2 minutes without resumption, and frames go to Decision anyway. The audio-only 15-minute limit is enough for the demo. On `goAway` or a close event, reconnect with a new token.

Decision results arrive from the backend over our WebSocket. The client injects them into Live as a text turn (`clientContent`, for example "OBSERVATION: wrong part, user holds C, expected B"), and Live speaks it.

> **Coordination point:** the backend may own this instead (server-side Live session). Decide tonight. The client design works either way.

---

## 7. Wake lock, orientation, vibration, thermal

| Feature | Android Chrome | iOS Safari | Fallback |
|---|---|---|---|
| Screen Wake Lock (`navigator.wakeLock.request('screen')`) | Yes | Baseline "newly available" since March 2025, so iOS 18.4+ (https://developer.mozilla.org/en-US/docs/Web/API/Screen_Wake_Lock_API; exact iOS version UNVERIFIED) | Re-request on `visibilitychange`, because the lock is auto-released when the page is hidden. For older iOS, use a hidden looping muted `<video playsinline>` (NoSleep pattern, UNVERIFIED on iOS 18). The live camera stream may itself keep the screen on (UNVERIFIED) |
| `screen.orientation.lock('portrait')` | Only in fullscreen (UNVERIFIED details) | Not supported (UNVERIFIED) | Responsive CSS for both orientations (section 2), so no lock is needed |
| `navigator.vibrate` | Yes | **No** (team fact) | Short WebAudio "tick" or "buzz" tone plus a full-screen color flash (green = OK, red = correction) |

**Battery and thermal.** Camera, WebGL, MediaPipe and the audio stream together will heat a phone within 10–20 minutes (UNVERIFIED; measure). Mitigations:

- `renderer.setPixelRatio(Math.min(devicePixelRatio, 2))`.
- **Render on demand**: only when the mixer is playing, controls change, or the highlight pulses. In phase 2 the 3D view can drop to 15 fps.
- Use 720p capture and the hand-landmarker gate.
- Watch `navigator.getBattery()` (Chrome only) and show "plug in" below 20%. Tell users to keep the phone on a charger on the stand.

---

## 8. Live reasoning feed (phase 1)

Use **SSE** (`EventSource`) for phase 1:

- It is one-way, reconnects automatically (resuming from `Last-Event-ID`), and passes cleanly through tunnels and proxies.
- The phase 2 WebSocket is separate.
- Endpoint: `GET /jobs/{jobId}/events`.

```json
{ "id": 42, "ts": 1763150000123, "jobId": "abc",
  "type": "reasoning",            // reasoning | part_status | step_ready | progress | error | done
  "stage": "generate_parts",       // parse_pdf | generate_parts | build_scene | export
  "partId": "B", "stepN": null,
  "level": "warn",                 // info | warn | success | error
  "text": "Part B rejected: holes on wrong face, regenerating",
  "progress": 0.38,
  "thumb": "parts/B_v2.png" }      // optional preview image
```

- Render the feed as a chat-like list with auto-scroll, a colored dot per `level`, and a part chip.
- `type: "step_ready"` lets the viewer unlock steps progressively, and `done` loads the GLB.
- Keep at most 200 DOM rows.

---

## 9. Performance budget (mid-range phone)

| Metric | Budget | How to measure |
|---|---|---|
| GLB transfer size | ≤ 5 MB (meshopt + quantize via `gltfpack -cc` or `gltf-transform optimize`) | DevTools Network |
| Triangles (whole scene) | ≤ 150k. CadQuery tessellation tolerance is set by the backend; ask for about 0.5 mm linear | `renderer.info.render.triangles` |
| Draw calls | ≤ 100. Merge hardware instances (screws, dowels) with `InstancedMesh` or merge on export | `renderer.info.render.calls` |
| Textures | None (flat PBR colors). Guide crops are separate JPEGs, ≤ 1024 px long side, ≤ 150 KB | — |
| Time to first render after `done` | < 3 s on 4G | `performance.now()` marks |
| FPS while orbiting | ≥ 45 | stats.js overlay behind `?debug=1` |
| Frame grab + encode | < 25 ms main thread | `performance.measure` |
| Frame round trip (send → decision) | p50 < 500 ms | backend echoes `seq`, client logs |
| Hand landmarker | < 40 ms per call in worker | worker timing |
| Device temperature, battery drain per 10 min | observe | manual |

---

## 10. Folder layout and skeleton

```
client/
  index.html
  vite.config.ts            # server.host, allowedHosts, build target es2020
  public/manifest.webmanifest
  src/
    main.ts                 # router: upload → build feed → viewer → assist
    api.ts                  # API_URL, REST, SSE, WS helpers
    ui/                     # upload.ts, feed.ts, stepbar.ts, layout.css
    viewer/
      Viewer.ts             # renderer, scene, controls, render-on-demand
      steps.ts              # clip-per-step playback, pose replay
      highlight.ts          # emissive pulse / dim others
      cameraRig.ts          # step camera tween
    assist/
      camera.ts             # getUserMedia, video element
      frameLoop.ts          # capture + backpressure
      gate.ts               # hand-state machine
      hands.worker.ts       # MediaPipe in worker
      live.ts               # Live API WS, token, messages
      audio/mic-worklet.ts, player.ts
      device.ts             # wake lock, vibrate fallback, battery
```

**Load the GLB and play step N**

```ts
import * as THREE from 'three';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';
import { MeshoptDecoder } from 'three/examples/jsm/libs/meshopt_decoder.module.js';

export class Steps {
  mixer!: THREE.AnimationMixer; clips: THREE.AnimationClip[] = []; current?: THREE.AnimationAction;
  async load(url: string, scene: THREE.Scene) {
    const loader = new GLTFLoader().setMeshoptDecoder(MeshoptDecoder);
    const gltf = await loader.loadAsync(url);
    scene.add(gltf.scene);
    this.mixer = new THREE.AnimationMixer(gltf.scene);
    this.clips = gltf.animations
      .filter(c => /^step_\d+$/.test(c.name))
      .sort((a, b) => a.name.localeCompare(b.name));
    return gltf;
  }
  play(n: number) {                         // n is 1-based
    this.mixer.stopAllAction();
    for (let i = 0; i < n - 1; i++) {        // apply end pose of prior steps
      const a = this.mixer.clipAction(this.clips[i]);
      a.setLoop(THREE.LoopOnce, 1); a.clampWhenFinished = true;
      a.play(); a.time = this.clips[i].duration;
    }
    const act = this.mixer.clipAction(this.clips[n - 1]);
    act.reset(); act.setLoop(THREE.LoopOnce, 1); act.clampWhenFinished = true; act.play();
    this.mixer.update(0);
    this.current = act;
  }
  scrub(t01: number) { if (!this.current) return;
    this.current.paused = true; this.current.time = t01 * this.current.getClip().duration; this.mixer.update(0); }
  tick(dt: number) { this.mixer?.update(dt); }
}
```

Caveat: if two clips animate the same node, the clamped earlier actions keep blending with the current one. The backend should make each step's clip touch only the parts that move in that step, with absolute keyframes. If tests show blending artifacts, switch to "sample the prior clip at its end, then `stopAllAction`" (UNVERIFIED which approach is cleaner; test tonight with a two-step dummy GLB).

**Frame-capture loop with backpressure**

```ts
export function startFrameLoop(video: HTMLVideoElement, ws: WebSocket,
                               shouldSend: () => null | 'low' | 'medium', step: () => number) {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d', { alpha: false })!;
  let inFlight = false, seq = 0, timer = 0;

  ws.addEventListener('message', ev => {
    const msg = JSON.parse(ev.data as string);
    if (msg.type === 'decision') inFlight = false;     // backend replies once per frame
  });

  const tick = () => {
    const res = shouldSend();                          // gate.ts (hands) decides
    if (!res || inFlight || ws.readyState !== WebSocket.OPEN || ws.bufferedAmount > 0) return; // drop frame
    const s = 640 / Math.max(video.videoWidth, video.videoHeight);
    canvas.width = Math.round(video.videoWidth * s); canvas.height = Math.round(video.videoHeight * s);
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    inFlight = true;
    const mySeq = ++seq;
    canvas.toBlob(async blob => {
      if (!blob) { inFlight = false; return; }
      const header = new TextEncoder().encode(JSON.stringify({ seq: mySeq, step: step(), res, ts: Date.now() }));
      const buf = new Uint8Array(4 + header.length + blob.size);
      new DataView(buf.buffer).setUint32(0, header.length);   // [len][json][jpeg]
      buf.set(header, 4); buf.set(new Uint8Array(await blob.arrayBuffer()), 4 + header.length);
      ws.send(buf);
      setTimeout(() => { if (seq === mySeq) inFlight = false; }, 2000); // watchdog for lost replies
    }, 'image/jpeg', 0.7);
  };
  timer = window.setInterval(tick, 250);               // 4 Hz ceiling
  return () => clearInterval(timer);
}
```

---

## Test checklist before code freeze

1. Test on a mid-range Android phone, a non-Pro iPhone, and the MacBook Air (Chrome and Safari), through the Vercel URL opened by QR code.
2. Confirm the rear camera opens and `playsinline` works.
3. Confirm the wake lock holds for 5 minutes.
4. Check AudioContext resume, echo cancellation and barge-in on the loudspeaker.
5. Run with `?gate=0` and `?gate=1`.
6. Time 15 minutes of phase 2 and check the phone's temperature.
7. Run the full flow on venue Wi-Fi and on a 4G hotspot.
