# 3D scaffolding reuse map

Owner: 3D engineer. Checked on 2026-11-14 by reading the source (read-only). Where this file disagrees with `../PLAN.md`, **`PLAN.md` wins**.

The source is called **the 3D scaffolding reference** in this file. Its root is `$SCAFFOLD_REF` (a folder with two repos: an older app at `$SCAFFOLD_REF/<ref> 2/` and a newer monorepo at `$SCAFFOLD_REF/<ref>-engine/`). Wherever a path or identifier contains the product name, that part is written as `<ref>`. Nothing in this file names the company, its customers or its integrations.

**Short answer:** stay on **vanilla three.js r187**. About ten small, pure three.js modules are worth copying or re-implementing. The reference has **no** WebXR code, **no** `AnimationMixer` playback, **no** Blender or `bpy` code and **no** gltfpack step, so the step player, the XR mode and the whole Blender side are still ours to write.

---

## 1. Inventory

Versions: the newer platform uses three `^0.183.2`, R3F `^9.5.0`, drei `^10.7.7`, React 19 and Next 16. The configurator template uses three `0.174.0` (pinned), R3F `^9.4.0`, drei `^10.7.6` and Vite 7. The older app uses three `^0.159`, R3F 8 and drei 9.

Paths below are relative to `$SCAFFOLD_REF/<ref>-engine/` unless they say otherwise. `TPL` = `<ref>-orchestrator/templates/configurator/app/core`. `PLT` = `<ref>-platform/src`.

| Module | What it does | Stack | Quality | Verdict |
|---|---|---|---|---|
| `TPL/scene/framing.ts` | `framePerspective` / `frameOrtho` / `padBox` / `quaternionFromLookDir`: fits a camera to a Box3 along a view direction with a tight, re-centred fit | **pure three** | High. Documented, has unit tests, no renderer needed | **Reuse as-is** (drop the one `ViewDef` type import) |
| `TPL/capture/capture.ts` | Renders a named view into the live canvas and reads it back in the same task (no `preserveDrawingBuffer`); hides helpers, isolates groups, restores all state afterwards | pure three | High, tested | **Adapt** (debug or eval snapshots only) |
| `TPL/scene/Studio.tsx` | `RoomEnvironment` → PMREM (no HDR file, no CDN); key/fill/rim lights fitted to the product bounds; shadow-only floor | R3F (a thin wrapper over three) | High | **Adapt** (re-implement in vanilla, ~60 lines) |
| `TPL/scene/Stage.tsx` | Canvas shell: `frameloop="demand"`, dpr `[1,2]`, low-power detection, draw probe ("ready" after two rAFs) | R3F + zustand | High but tightly coupled | **Reference only** (copy `isLowPower()` and the two-rAF ready probe) |
| `TPL/scene/CameraRig.tsx` | Orbit through drei `CameraControls`: smooth `setLookAt`, distance limits from bounds, polar clamp above the floor | R3F + drei | High, store-coupled | **Reference only** (copy the limit formulas) |
| `TPL/measure/sceneStats.ts` | Triangle count, per-part boxes, draw calls (`gl.info.render.calls`), scene hash | pure three | High, tested | **Adapt** (a budget HUD) |
| `TPL/scene/Part.tsx` | Naming rule: `part:<id>` groups, plus helper/context flags in `userData` that captures and measurements skip | R3F | Small | **Reference only** (the same idea as our `part_<ID>_<n>`) |
| `TPL/scene/cues.tsx` | Hotspots and arrow callouts; text drawn into a `CanvasTexture` with the system font | R3F + drei `Billboard` | Medium | **Reference only** (the canvas-label technique, for XR panels) |
| `PLT/components/instant-quote/stl-viewer.tsx` (+ `viewer-camera.ts`) | Full **imperative three viewer inside a React effect**: renderer setup, RoomEnvironment, OrbitControls, ResizeObserver, a corner viewport drawn with a scissor test, complete dispose | **vanilla three** (+ three-stdlib) | High, production code | **Adapt** (skeleton of our `Viewer` class) |
| `PLT/lib/viewer/model-thumbnails.ts` | Lazy offscreen renderer; GLTFLoader + DRACO + `MeshoptDecoder`; `disposeObject()`; 3/4-iso auto-frame; IndexedDB cache | **vanilla three** | High | **Adapt** (loader setup + `disposeObject`) |
| `PLT/components/spaces/scene/gltf-decoders.ts` | Decoder wiring (Meshopt, KTX2, Draco path) with defensive try/catch | drei + three | Medium | **Reference only** (we need Meshopt only) |
| `PLT/components/spaces/scene/use-scene-ease.ts` | Frame-rate-independent exponential ease that calls `invalidate()` only while moving; dt clamp; reduced motion | R3F hook | High, tiny | **Adapt** (vanilla, ~20 lines) |
| `PLT/components/configurator/assembly-part.tsx` | Per-instance clone (SkeletonUtils) with material clones; **emissive highlight with save/restore**; opacity fade × "isolate dim" (0.15); `visible=false` when fully faded; eased TRS | R3F | High, the most relevant logic | **Adapt** (the pattern for highlight, fade and ghost) |
| `PLT/components/spaces/scene/glb-optimize.ts` | Merges meshes by material, culls specks, strips lines (draw-call reduction at load) | pure three | High | **Skip** (merging destroys our per-part nodes; do it offline) |
| `PLT/lib/spaces/cad-up.ts` | The single Z-up → Y-up boundary rotation (−90° about X) | pure three | Small | **Reference only** (the convention matters, see §4) |
| `PLT/lib/viewer/export-glb.ts`, `stl-to-glb.ts`, `lib/spaces/step-to-glb.ts` | GLTFExporter; STL → GLB in the browser; STEP → GLB on the server through OCCT WASM + gltf-transform (`dedup`, `weld`, `prune`) | three / Node | Medium | **Skip** (Blender exports our GLB) |
| `<ref>-platform/scripts/decode-draco-glb.mjs` | gltf-transform `NodeIO` script | Node | Small | **Reference only** (pattern for a GLB check script) |
| `<ref>-orchestrator/templates/configurator/scripts/render.mjs` | Headless Chrome (SwiftShader) renders every view × state to PNG + `report.json` | Node + playwright-core | High | **Reference only** (idea for a client smoke render) |
| `<ref>-orchestrator/scaffold/v1/` | Contains **only** cached `node_modules` (three 0.183.2, @tweenjs/tween.js 23.1.3, meshoptimizer 1.0.1, rapier 0.12); no source | n/a | n/a | **Skip** |
| `<ref>-quote-engine/quote_engine/kernel.py` `run_worker` + `templates/sheet_metal/analyzer_worker.py` `_limits`, `_write_preview` | Runs native CAD code in a killable child process: clean env, `python -I`, rlimits on CPU/size/files/memory, `killpg` on timeout, a JSON result contract; STEP read through OCP; BRepMesh → STL preview | Python 3.11–3.13, `cadquery-ocp` 7.8 | High, production | **Adapt** (`run_worker` + `_limits`) |
| `$SCAFFOLD_REF/<ref> 2/app/components/three/*`, `app/utils/screenshot-capture.ts` | Older viewer (CAD-SaaS GLTF renderer, nav cube, material tools, screenshot class) | R3F 8, three 0.159 | Superseded by the modules above | **Skip** |
| Nav cube (`nav-cube.ts`, `NavCube.tsx`, `nav_cube.glb`) | Orientation cube | both | Good | **Skip** (product-derived asset; not needed for a guide) |

Nothing in either repo uses `@react-three/xr`, `navigator.xr`, `renderer.xr`, `AnimationMixer`/`useAnimations`, exploded views, `bpy`, CadQuery's high-level API (only raw `OCP`), or gltfpack.

---

## 2. Client stack decision: keep vanilla three.js r187

**Decision: no React and no R3F.** The reference does not make R3F materially faster for us:

1. **Most of what is reusable is already pure three.js.** The useful logic is in `framing.ts`, `capture.ts`, `sceneStats.ts`, `model-thumbnails.ts` and `stl-viewer.tsx`, which are all framework-free. The R3F components are thin JSX shells around the same three calls (Studio, ease) or are coupled to zustand stores, the configurator product schema and Next routes (`Stage`, `CameraRig`, `AssemblyPart`). Copying them would mean bringing the stores along.
2. **There is no WebXR head start.** `@react-three/xr` is not used anywhere, so R3F would add a layer and a version-compatibility question over `renderer.xr` without any code to reuse. Quest work (hands, hit-test, anchors, in-world panels) is the riskiest part of phase 2, and plain three.js examples map onto it 1:1.
3. **The two things we play most are absent:** step clips (`AnimationMixer`, `LoopOnce`, `clampWhenFinished`) and per-step camera tweens. We write these either way, and they are simpler without React re-render traps (see `02-mobile-client.md` §1).
4. **Team familiarity is covered both ways.** The author wrote a complete imperative three viewer (`stl-viewer.tsx`) next to the R3F code, so vanilla is familiar ground.
5. **Versions:** the reference pins three 0.174/0.183 with drei 10 and React 19. Whether drei 10 works with r187 is **UNVERIFIED**. Vanilla three has one pinned dependency.

Optional: `camera-controls` (the vanilla library that drei's `CameraControls` wraps) gives `setLookAt(..., true)` smooth transitions and `fitToBox`. It is an acceptable add if OrbitControls plus our own ease feels clunky. Compatibility with r187 is **UNVERIFIED**, and it must be credited.

---

## 3. Reuse plan (into the hackathon repo)

| Hackathon path | Source | Change |
|---|---|---|
| `client/src/viewer/framing.ts` | `TPL/scene/framing.ts` | Copy. Replace `cameraForView(view: ViewDef, …)` with `cameraForBox(box, dir, fov, padding)`. Use it as a **fallback** when a manifest step has no camera: frame the union box of that step's visible `part_*` nodes. |
| `client/src/viewer/viewer.ts` | `stl-viewer.tsx` lines ~94–330 | Keep: renderer setup (`setPixelRatio(min(dpr,2))`, `setSize(w,h,false)`, CSS 100%, `touchAction:none`), ResizeObserver plus redraw in the same task, the dispose list. Replace the always-on rAF loop with **render-on-demand** (below). Drop STL, nav cube and the metal material. |
| `client/src/viewer/studio.ts` | `TPL/scene/Studio.tsx` | Re-implement in vanilla: PMREM `RoomEnvironment` (`environmentIntensity` 0.85), hemisphere 0.35, key 1.6 / fill 0.45 / rim 0.6 placed at `radius*4` along fixed directions. On phones: no shadows, or a 1024 shadow map with the `ShadowMaterial` floor at opacity 0.16. Copy `isLowPower()` from `Stage.tsx`. |
| `client/src/viewer/loader.ts` | `model-thumbnails.ts` `getRenderer` + `disposeObject` | GLTFLoader + `MeshoptDecoder` only (no Draco, no gstatic). Keep `disposeObject` exactly as it is. |
| `client/src/viewer/ease.ts` | `use-scene-ease.ts` | A vanilla `Eased` value: `k = 1 - exp(-min(dt,0.05)/lambda)`. Returns "still moving" so the loop knows whether to request another frame. Used for camera position/target tweens, the fade and the pulse envelope. |
| `client/src/viewer/highlight.ts` | `assembly-part.tsx` (material cloning, emissive save/restore, fade) | Clone materials per `part_*` node at load. Pick parts: emissive pulse `0.25+0.25*sin(t*4)`. Others: opacity eased to ~0.25, `transparent=true`, `depthWrite=false`. Restore the saved emissive when a step ends. Set `visible=false` once opacity < 0.001 (this also saves draw calls). |
| `client/src/viewer/stats.ts` | `sceneStats.ts` (triangles) + `gl.info.render.calls` | A `?debug=1` HUD showing triangles, draw calls and fps against the budgets (150k / 100 / 45). |
| `client/src/viewer/capture.ts` | `TPL/capture/capture.ts` + `PLT/lib/viewer/capture-snapshot.ts` | Optional. Same-task read-back, downscaled to a long edge of 1280. Not usable during an XR session. |
| `client/src/xr/panel.ts` | the canvas-label code in `stl-viewer.tsx` / `cues.tsx` | `CanvasTexture` + `SRGBColorSpace` + `MeshBasicMaterial({transparent, depthWrite:false})` on a plane. Redraw only when the step changes. |
| `client/src/xr/ghost.ts` | the fade pattern from `assembly-part.tsx` | Clone the next part with a shared ghost material (opacity ~0.35, `depthWrite:false`, emissive tint). Everything else in `client/src/xr/` is new code. |
| `pipeline/cad/run_part.py` | `kernel.py` `run_worker` + `analyzer_worker.py` `_limits` | Run **LLM-written CadQuery** in a child process: `python -I`, minimal env, `OMP_NUM_THREADS=1`, RLIMIT_CPU/FSIZE/NOFILE (and AS on Linux), `start_new_session=True` + `killpg` on timeout, result JSON written to a temp dir and moved only on success. Map stderr tails into the fix-loop prompt. |

Render-on-demand loop for `viewer.ts`, condensed from the `Stage.tsx` and `use-scene-ease.ts` ideas:

```ts
let pending = false;
export function invalidate() {
  if (pending || renderer.xr.isPresenting) return;
  pending = true;
  requestAnimationFrame(frame);
}
function frame(now: number) {
  pending = false;
  const dt = clock.getDelta();
  const busy = mixer.update(dt) && actionRunning()   // clip still playing
            | camEase.step(dt) | fadeEase.step(dt) | pulse.active;
  controls.update();
  renderer.render(scene, camera);
  if (busy) invalidate();
}
controls.addEventListener('change', invalidate);
// XR: renderer.setAnimationLoop(xrFrame) while presenting; Quest needs a continuous loop.
```

Emissive save/restore, condensed from `assembly-part.tsx`:

```ts
const saved = new WeakMap<THREE.MeshStandardMaterial, THREE.Color>();
function setGlow(m: THREE.MeshStandardMaterial, tint: THREE.Color | null) {
  if (tint) { if (!saved.has(m)) saved.set(m, m.emissive.clone()); m.emissive.copy(tint); }
  else if (saved.has(m)) { m.emissive.copy(saved.get(m)!); saved.delete(m); }
}
```

When copying, **rename** anything with the reference's prefixes (`userData` flags, object names, IndexedDB names, CSS classes, window hooks) to our own `ag*` prefix.

---

## 4. Python / Blender / CadQuery side

- **Blender: nothing exists.** No `bpy`, no headless render worker, no NLA or animation code, no glTF export settings, no thumbnail rendering in Blender. Everything in `pipeline/blender/*` is new code.
- **GLB optimisation: nothing reusable.** gltfpack is never used. gltf-transform appears only in a server-side STEP → GLB route (`dedup`/`weld`/`prune`) and a Draco-decode script. Our plan already uses `gltfpack -cc` after the Blender export. The `decode-draco-glb.mjs` `NodeIO` pattern is a fine template for an optional `pipeline/blender/check_glb.mjs` that asserts the clip names `step_NN`, the node names `part_<ID>_<n>`, the triangle total and the file size.
- **CAD worker: worth adapting** (see `run_part.py` in §3). The reference uses raw `OCP` (`cadquery-ocp>=7.8,<8`), not `cadquery.Workplane`, so only the **sandbox runner** carries over, not the modelling code. `_write_preview` (`BRepMesh_IncrementalMesh(shape, 0.2, False, 0.4, False)` + `StlAPI_Writer`) is reference only: CadQuery's `exporters.export(..., tolerance=, angularTolerance=)` does the same. Its tolerances are a sensible start for furniture-scale parts in mm, but check the triangle budget.
- **Thumbnails / check images:** the reference makes them in the browser (`model-thumbnails.ts`, `capture.ts`, `render.mjs` with headless Chrome on SwiftShader). For us, check images come from Blender. A `render.mjs`-style headless capture of our *client* is a nice-to-have smoke test (`eval/smoke.sh`), not core.
- **Up-axis:** CadQuery is Z-up, like the reference's CAD source. The reference keeps native Z-up in the GLB and rotates once at load (`cad-up.ts`). We should do the opposite: let Blender's glTF exporter apply its default +Y-up conversion, and make sure that **connection-frame JSON and manifest cameras are written in the same frame as the exported GLB**. Mixing frames is the most likely silent bug.

---

## 5. Credits

The hackathon requires crediting code we did not write during the event. Proposed `CREDITS.md` line, with no company or product name:

> **3D viewer scaffolding**: camera framing, studio lighting, render-on-demand easing, highlight/fade and the sandboxed CAD worker runner are adapted from internal 3D scaffolding in a team member's prior work. Adapted and re-licensed for this project with the owner's permission.

Also list any new third-party packages in `CREDITS.md` with their licence: three.js (MIT), and `camera-controls` (MIT) if we add it.

---

## 6. Risks of reuse

| Risk | Mitigation |
|---|---|
| **Ownership.** The reference is private, company-owned code. | The team member confirms they may reuse it before anything is copied. Otherwise re-implement from reading: the pieces are small (framing ~100 lines, ease ~20, highlight ~40). |
| **Brand leakage** through prefixes, userData keys, DB names, comments or the nav-cube asset. | Rename on copy. Never copy assets. Before commit, grep `client/` and `pipeline/` for the reference's name and prefixes. |
| **Version drift.** The reference uses three 0.174/0.183; we use 0.187. | Copy logic, not imports. Recheck the `examples/jsm` paths and `environmentIntensity`; build against `three@0.187.0`. |
| **Coupling.** R3F components depend on zustand stores, the configurator product schema, Next `'use client'` and auth-gated asset URLs (`resolveViewerAsset`, signed storage routes with credentials). | Do not copy those modules. Load GLBs from our own static or job URL with no auth. |
| **CDN dependency.** The reference's Draco decoder comes from a third-party CDN. | We use Meshopt only (bundled from `three/examples`), which works offline at the venue. |
| **Shadow cost on phones and Quest.** The reference uses a 2048 shadow map. | Shadows off on low-power devices and in XR, or a 1024 map. Check against the ≥ 45 / ≥ 72 fps targets with the stats HUD. |
| **Load-time mesh merging** (`glb-optimize.ts`) would break per-part highlighting and visibility. | Do not use it. Keep draw calls low offline (one material per part, instanced hardware, gltfpack). |
| **Capture in XR.** Same-task canvas read-back does not work inside an `immersive-ar` session. | Use capture only on the phone/desktop viewer. |
