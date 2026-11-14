# Credits

Everything in this repository that we did not build ourselves during the hackathon. Add a line when you add a dependency, a snippet, a model or an asset.

## Models and services

| What | Used for | Provider |
|---|---|---|
| `gemini-4-carbon` | Reading the guide and extracting the plan | Google (Gemini API) |
| `gemini-4-hydrogen` | Writing and fixing CadQuery scripts, writing the per-step questions | Google (Gemini API) |
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
| PyMuPDF | Rasterising guide pages and reading their text | AGPL-3.0 | https://pymupdf.readthedocs.io |
| OpenCV | Silhouette pre-checks | Apache-2.0 | https://opencv.org |
| NumPy | Placement maths | BSD-3-Clause | https://numpy.org |
| trimesh | Overlap checks, look-alike parts | MIT | https://trimesh.org |
| Pillow | Reference sheets, preview frames | MIT-CMU | https://python-pillow.org |
| python-multipart, httpx, pytest | Uploads, tests | Apache-2.0; BSD-3-Clause; MIT | |
| three.js | 3D viewer and WebXR on the phone and headset | MIT | https://threejs.org |
| MediaPipe Tasks Vision | On-device hand gate on the phone | Apache-2.0 | https://developers.google.com/edge/mediapipe |
| uv | Python environment | Apache-2.0 or MIT | https://docs.astral.sh/uv |
| gitleaks | Secret scan before pushing | MIT | https://gitleaks.io |

## Prior work reused

- Internal 3D scaffolding from a team member's prior work, adapted (camera framing, viewer skeleton, highlight and fade, lighting, easing).
- Look and feel derived from the brand reference design system (`docs/design/BRAND.md`, `docs/design/tokens.css`).

## Test material

- **Assembly guides** in `eval/guides/`: PDFs published by IKEA, Sauder and Teknik, downloaded from the manufacturers' and retailers' sites (sources listed in `eval/guides/README.md`). They are copyrighted by their manufacturers and are used for testing and the demo only.
- **IKEA-Manual dataset** (Stanford, NeurIPS 2022), used as ground truth for parts, counts and step order. Not committed. https://cs.stanford.edu/~rcwang/projects/ikea_manual

## To confirm before submission

- blender-mcp (development-time debugging only, not in the pipeline): check its licence before listing it. https://github.com/ahujasid/blender-mcp
- Licence of the IKEA-Manual dataset.
- Any client-side package added after this file was written (`client/package.json`).
