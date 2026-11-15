"""Two pages for software that wants to use the service without a person: other agents, scripts, integrations.

    GET /llms.txt           plain text, no key: what the service does and how to call it (HTTP, MCP, command line)
    GET /api/openapi.json   with the demo key: the OpenAPI description of every route, generated from the code

The interactive /docs page stays off; the schema is behind the key like every other /api route.
"""
from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, PlainTextResponse

router = APIRouter()

LLMS = """# Assembly Guide Agent

> Turns a flat-pack furniture assembly guide into a 3D step-by-step walkthrough: every part is modelled, the
> steps are animated in the guide's own order, and a phone or a headset plays them. A guide is found from what
> is on the box (a product name, an article number, a barcode, the link in a QR code) or uploaded as a PDF.

Base URL: {base}

## Access

Every /api/ and /files/ request needs the demo key: header `X-Demo-Key: <key>`, or `?key=<key>` where a header
cannot be set (links for a browser, images, event streams). Ask the operator for the key. Without it the answer
is 401 `{{"detail": "Missing or wrong demo key"}}`. Errors are JSON: `{{"detail": "<what went wrong>"}}`.

## The shortest path: from a product to a finished guide

1. `POST /api/catalog/open` with `{{"q": "<name, article number, barcode or link>"}}`
   - 200 `{{"job_id", "ready", "product"}}`. `ready: true`: the guide exists, go to step 4.
   - 409: several guides match; `detail.items` lists them. Send the `article` or `guide_id` of one.
   - 404: no guide found. Upload the PDF: `POST /api/jobs` (multipart `file=guide.pdf`) returns `{{"job_id"}}`.
2. `GET /api/jobs/<job_id>/events/poll?since=<seq>&wait=20` returns `{{"events": [...], "last_seq"}}`; repeat with
   the new `last_seq`. Events have `type` and `text`. A known product takes about a minute, a new guide several.
3. When an event has `type: "approval_required"`: read `GET /api/jobs/<job_id>/plan` (parts, hardware, steps,
   flags), then `POST /api/jobs/<job_id>/plan/approve` with `{{}}`. Keep polling until `type: "done"` or `"error"`.
4. The result:
   - `GET /files/<job_id>/manifest.json`: parts, hardware, steps and the actions of every step, in Greek and English
   - `GET /files/<job_id>/walkthrough.glb`: the animated 3D model (one clip per action)
   - for a person: `{base}/?job=<job_id>&key=<key>` (phone or laptop), add `&mode=xr` for a Meta Quest browser

## Other routes

- `GET /api/catalog?q=<text>`: search without starting anything
- `GET /api/jobs`, `GET /api/jobs/<job_id>`: the jobs and the state of one (`status`: running, waiting_approval, done, failed)
- `POST /api/jobs/<job_id>/ask` with `{{"step": 3, "question": "which screw goes here?", "lang": "en"}}`: a short
  answer about one step of a finished guide
- `GET /api/openapi.json`: every route with its request and response fields (OpenAPI 3)
- `GET /healthz`: `{{"ok": true}}` without the key

## MCP server and command line

The repository has both, as thin clients of this API (settings: `AGJ_BASE_URL={base}`, `AGJ_DEMO_KEY=<key>`):

- MCP: `python -m server.mcp_server` (stdio) or `--http --port 8765` (streamable HTTP at /mcp). Tools: find_guide,
  search_guides, create_guide, wait_for_guide, get_plan, approve_plan, get_steps, guide_links, ask_guide,
  guide_status, list_guides.
- Command line, one JSON object per command: `python -m server.cli find "KNAGGLIG" --wait`,
  `python -m server.cli build guide.pdf --wait`, then `steps`, `links`, `ask`, `status`, `list`, `search`.

## Limits

One build runs at a time; others wait in line. Uploads: PDF, or photos of the pages (JPEG, PNG, WebP). The guides
are the manufacturers' own documents; the service does not republish them.
"""


@router.get("/llms.txt", response_class=PlainTextResponse, include_in_schema=False)
async def llms_txt(request: Request) -> str:
    return LLMS.format(base=str(request.base_url).rstrip("/"))


@router.get("/api/openapi.json", include_in_schema=False)
async def openapi_json(request: Request) -> JSONResponse:
    return JSONResponse(request.app.openapi())
