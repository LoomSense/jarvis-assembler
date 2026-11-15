# Credits

Everything in this repository that we did not build ourselves during the hackathon. Add a line when you add a dependency, a snippet, a model or an asset.

## Models and services

| What | Used for | Provider |
|---|---|---|
| `gemini-4-carbon` | Reading the guide and extracting the plan | Google (Gemini API) |
| `claude-fable-5-2` (demo setting `AGJ_AGENT_MODEL`; code default `claude-opus-5-5`) | The build agent: reads the guide, writes the plan and the CadQuery scripts, fixes them until the fit checks pass (PLAN D20) | Anthropic (Claude Agent SDK) |
| `gemini-4-hydrogen` | Writing the per-step questions | Google (Gemini API) |
| `gemini-decision-1` | Comparing renders with the guide's drawings; checking live camera frames | Google (Gemini API) |
| `gemini-3.8-live` | The always-on voice agent | Google (Gemini API) |
| boxd | The VM that hosts the server | boxd.sh |
| fal | Image-to-3D for odd, non-board parts (optional) | fal.ai |
| Cloudflare Tunnel (`cloudflared`) | HTTPS URL for a laptop during development | Cloudflare, Apache-2.0 |

## Software

| Package | Used for | Licence | Link |
|---|---|---|---|
| CadQuery | Exact part geometry | Apache-2.0 | https://github.com/CadQuery/cadquery |
| OCP, Open CASCADE Technology | CAD kernel under CadQuery | Apache-2.0; LGPL-2.1 with exception | https://github.com/CadQuery/OCP |
| Blender 5.3 | Scene, animation, renders, GLB export. Run as a separate program, not linked into our code | GPL-3.0-or-later | https://www.blender.org |
| FastAPI, Starlette | Web server | MIT; BSD-3-Clause | https://fastapi.tiangolo.com |
| Uvicorn | ASGI server | BSD-3-Clause | https://www.uvicorn.org |
| Pydantic, pydantic-settings | Settings and request models | MIT | https://docs.pydantic.dev |
| google-genai | Gemini SDK | Apache-2.0 | https://github.com/googleapis/python-genai |
| claude-agent-sdk | Runs the build agent (Claude Code as a library) | MIT (the bundled Claude Code CLI is under Anthropic's commercial terms) | https://github.com/anthropics/claude-agent-sdk-python |
| PyMuPDF | Rasterising guide pages and reading their text | AGPL-3.0 | https://pymupdf.readthedocs.io |
| OpenCV | Silhouette pre-checks | Apache-2.0 | https://opencv.org |
| NumPy | Placement maths | BSD-3-Clause | https://numpy.org |
| trimesh | Overlap checks, look-alike parts | MIT | https://trimesh.org |
| Pillow | Reference sheets, preview frames | MIT-CMU | https://python-pillow.org |
| python-multipart, httpx, pytest | Uploads, tests | Apache-2.0; BSD-3-Clause; MIT | |
| three.js | 3D viewer and WebXR on the phone and headset; its GLB loader in `eval/glb_check` | MIT | https://threejs.org |
| gltfpack (meshoptimizer) | Compressing the GLB (`pipeline/pack.py`); the viewer needs its meshopt decoder, which ships with three.js | MIT | https://github.com/zeux/meshoptimizer |
| MediaPipe Tasks Vision | On-device hand gate on the phone | Apache-2.0 | https://developers.google.com/edge/mediapipe |
| Vite, TypeScript, @types/three | Building and type-checking the client | MIT; Apache-2.0; MIT | https://vite.dev |
| puppeteer-core | Drives the installed Chrome for the client's automated check (`client/scripts/shots.mjs`). Development only | Apache-2.0 | https://pptr.dev |
| uv | Python environment | Apache-2.0 or MIT | https://docs.astral.sh/uv |
| gitleaks | Secret scan before pushing | MIT | https://gitleaks.io |
| Meta Spatial SDK 0.15.0 and its Gradle plugin | Native Quest app (`quest-android/`): passthrough, panels, glTF, hands, MRUK, shader compile | Meta Platform Technologies SDK licence (UNVERIFIED exact name) | https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-overview |
| Meta Spatial SDK Samples, Meta Passthrough Camera API Samples | Patterns copied into `quest-android/` (manifest entries, panel registration, Camera2 vendor keys, shader includes); Gradle wrapper jar taken from StarterSample | MIT; Apache-2.0 | https://github.com/meta-quest/Meta-Spatial-SDK-Samples, https://github.com/meta-quest/Meta-Passthrough-Camera-API-Samples |
| OpenCV for Android 4.10.0 | Marker detection and PnP in `quest-android/` | Apache-2.0 | https://opencv.org |
| OkHttp, kotlinx.serialization, kotlinx.coroutines, AndroidX, Jetpack Compose | Networking, JSON, threading, panel UI in `quest-android/` | Apache-2.0 | https://square.github.io/okhttp |
| Unity 6.3 LTS (6000.3.28f1), URP, OpenXR, XR Management, TextMeshPro, Newtonsoft JSON (Unity) | Native Quest app `quest-unity/` | Unity Personal licence / Unity Companion License | https://unity.com |
| Meta XR Core SDK, MR Utility Kit, Interaction SDK (209.0.0) | Passthrough, passthrough camera, hands, scene, anchors in `quest-unity/` | Meta Platform Technologies SDK License | https://developers.meta.com/horizon/documentation/unity/ |
| glTFast, meshopt decompress (Unity) | Runtime GLB loading in `quest-unity/` | Apache-2.0; Unity Companion License | https://github.com/Unity-Technologies/com.unity.cloud.gltfast |
| NativeWebSocket (endel) | Perception socket client in `quest-unity/` | Apache-2.0 | https://github.com/endel/NativeWebSocket |
| jp.keijiro.apriltag (Keijiro Takahashi, wraps AprilTag 3 from the APRIL lab) | Marker detection in `quest-unity/` | BSD-2-Clause (UNVERIFIED for the wrapper) | https://github.com/keijiro/jp.keijiro.apriltag |
| Inter (font) | Latin + Greek UI text in `quest-unity/` (`Assets/AG/UI/Fonts/`, licence file next to it) | SIL OFL 1.1 | https://github.com/rsms/inter |
| OpenPnP OpenCV Java bindings 4.9.0-0 (`org.openpnp:opencv`), OkHttp MockWebServer | `quest-android/` JVM unit tests only: marker tracker on the synthetic replay set, perception socket against a scripted server | BSD-3-Clause (packaging) + Apache-2.0 (OpenCV); Apache-2.0 | https://github.com/openpnp/opencv, https://square.github.io/okhttp |
| 1€ filter (Casiez, Roussel, Vogel, CHI 2012) | Algorithm behind `quest-android` `OneEuroPoseFilter` (own implementation) | paper | https://gery.casiez.net/1euro/ |

## Prior work reused

- Internal 3D scaffolding from a team member's prior work, adapted (camera framing, viewer skeleton, highlight and fade, lighting, easing).
- Look and feel derived from the brand reference design system (`docs/design/BRAND.md`, `docs/design/tokens.css`).

## Test material

- **Assembly guides** in `eval/guides/`: PDFs published by IKEA, Sauder and Teknik, downloaded from the manufacturers' and retailers' sites (sources listed in `eval/guides/README.md`). They are copyrighted by their manufacturers and are used for testing and the demo only.
- **IKEA-Manual dataset** (Stanford, NeurIPS 2022), used as ground truth for parts, counts and step order. Not committed. https://cs.stanford.edu/~rcwang/projects/ikea_manual

## To confirm before submission

- blender-mcp (development-time debugging only, not in the pipeline): check its licence before listing it. https://github.com/ahujasid/blender-mcp
- Licence of the IKEA-Manual dataset.
- `client/public/xr-check.html` (the headset check page) loads three.js 0.187.0 from the jsDelivr CDN at run time instead of from the build.
- Fonts: the client loads Google Sans and JetBrains Mono from Google Fonts at run time (`client/src/styles/tokens.css`). Check their licence terms before listing them above.
| AprilRobotics apriltag-imgs (tag41_12_00000.png) | 41h12 marker image in `tools/quest/marker/vendor/` | BSD-2-Clause | https://github.com/AprilRobotics/apriltag-imgs |
