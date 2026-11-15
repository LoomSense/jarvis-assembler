# Assembly Guide Agent

Upload a flat-pack furniture guide (PDF). An agent reads it, builds every part in 3D, assembles them step by step in the guide's own order, and checks its work against the guide's drawings. A browser page (`client/`) plays the result as a 3D walkthrough, one action at a time. On a Meta Quest 3, a native app loads the same guide, asks the server which step the real assembly is at from a camera frame, and shows and speaks the next action in Greek or English.

Status on Sun 15 Nov, 14:55: the demo runs on the native Android app, `quest-android/` (Kotlin, Meta Spatial SDK); `quest-unity/` (Unity 6.3, Meta XR SDK) is the backup. The demo guide is `drawer`, built today for the team's own drawer from tape measurements and phone photos (6 steps, 17 actions). Nothing is printed or stuck on the furniture. On a Quest 3 the app finds each board on the floor by its measured size, locks the guide onto the real drawer front with an on-device detector (median size error 5 to 6 mm on trusted frames), reads which way round the front lies from its groove, and moved through four cam bolts and a side panel without a button press. Not proven on real frames: a part the wrong way round, a wrong small part; steps 4 to 6 of the guide have not run on the headset. The WebXR page (`?mode=xr`) is a desktop preview, not the demo path. What is logged and what is not: [docs/SUBMISSION.md](docs/SUBMISSION.md).

Built at the Gen AI Summit Hackathon, Athens, 14–15 Nov 2026.

- For the judges, with evidence and limits: [docs/SUBMISSION.md](docs/SUBMISSION.md); video script: [docs/DEMO_VIDEO.md](docs/DEMO_VIDEO.md)
- The plan: [docs/PLAN.md](docs/PLAN.md)
- Who is doing what, and what is done: [docs/PROGRESS.md](docs/PROGRESS.md)
- Status and the native apps' brief: [docs/HANDOFF.md](docs/HANDOFF.md); headset testing: [docs/QUEST_TESTING.md](docs/QUEST_TESTING.md)
- What we did not build ourselves: [CREDITS.md](CREDITS.md)

## Run it

Needs: macOS or Linux, [uv](https://docs.astral.sh/uv/), Blender 5.3 (`brew install --cask blender`), about 5 GB of disk.

```sh
make setup     # Python 3.12 venv with all packages
make env       # creates .env with a fresh DEMO_KEY; then add GEMINI_API_KEY to it
make dev       # server on http://localhost:8000 with auto-reload
make smoke     # in a second terminal: upload the golden guide, approve, wait for the GLB (about 20 s)
make test      # unit tests
make tunnel    # public HTTPS URL for phones and the headset (cloudflared)
```

`make smoke` prints a job id. Its files are under `jobs/<job_id>/` and at `/files/<job_id>/…`.

Headset (Quest 3 in developer mode, USB debugging allowed; builds are described in `quest-android/README.md` and `quest-unity/README.md`):

```sh
tools/quest/doctor.sh                                     # adb, headset, server, key, APKs
tools/quest/install.sh android                            # the demo app; unity is the backup
tools/quest/run.sh android --job drawer --lang el         # launches the app and logs to tools/quest/runs/ (malm-drawer is the fallback guide)
tools/quest/pull_recordings.sh                            # lists today's headset recordings; pull and map them for the video
```

The tools read the server URL from `jobs/_host/url.txt` and the demo key from `.env`.

## Try it (demo host on a Mac)

The demo runs on one Mac (boxd sign-ups were closed): `deploy/mac_host.sh` in the `.demo-host/` worktree keeps it awake, serves `main`, and opens a public HTTPS URL. The URL is in `.demo-host/jobs/_host/url.txt`; the demo key is `DEMO_KEY` in `.env`. Update it to the latest `main` with `deploy/redeploy_mac.sh`.

| What | Open |
|---|---|
| Phase 1 guide, KNAGGLIG box | `<URL>/?key=<KEY>&job=1115-0453-7ca85e` |
| Phase 1 guide, MARKUS office chair | `<URL>/?key=<KEY>&job=1115-0505-4dcc90` |
| Same in English | add `&lang=en` |
| WebXR page (desktop preview; not the demo path, the native Android app is) | add `&mode=xr` |
| Build a guide from a PDF | `eval/smoke.sh <URL> <KEY> <guide.pdf>`, or the upload screen at `/?mode=build` once it lands |
| Live check without a camera | `eval/phase2/replay.py --job 1115-0453-7ca85e --step 6 --base http://127.0.0.1:8000 <frames folder>` |

Known guides (KNAGGLIG, LACK side table, MARKUS) build in about a minute from their hand-checked or saved plans. Any other PDF needs the build agent: start the host with `AGJ_BUILD_AGENT=claude` (about 10 minutes and $2–3 per guide).

## Layout

| Folder | What |
|---|---|
| `server/` | FastAPI app: REST, SSE, the job runner, the Live token |
| `pipeline/` | Plan → CadQuery parts → placement and fit checks → Blender scene, animation, GLB, reference sheets |
| `client/` | Vite + three.js app for the phone and the Quest 3 (served by the server from `client/dist`) |
| `prompts/` | Prompts as files; their version is logged in the events |
| `eval/` | Golden guides and expected plans, scorers, `smoke.sh`, phase-2 clips and replay |
| `deploy/` | boxd VM deploy scripts |
| `docs/` | Plan, progress, research, brand |

## API

Every route needs the demo key: header `X-Demo-Key: <key>`, or `?key=<key>` where headers cannot be set (EventSource, WebSocket, `<img>`, QR links). `GET /healthz` without the key answers only `{"ok": true}`.

| Method | Path | Request → Response |
|---|---|---|
| POST | `/api/jobs` | multipart `file=guide.pdf`, or photos of the pages as repeated `pages` (JPEG, PNG, WebP; in page order; one photo may go in `file`), optional `size` ("62 x 60 x 135 cm") → `{job_id, stage: "ingesting"}`; 413 too large, 415 not a PDF/image, 422 PDF and photos mixed or over 60 photos |
| GET | `/api/jobs` | → `{jobs: [...]}`, newest first |
| GET | `/api/jobs/{id}` | → `{job_id, stage, status, progress, error?, last_seq, title, source}` (`source`: `pdf` or `photos`) |
| GET | `/api/jobs/{id}/events` | SSE stream of events; resumes from `Last-Event-ID` or `?since=<seq>` |
| GET | `/api/jobs/{id}/events/poll?since=<seq>&wait=20` | → `{events: [...], last_seq}`; long-poll fallback for the stream above |
| GET | `/api/jobs/{id}/plan` | → `{approved, plan, crops: [{ref, url}], pages: [url]}` |
| POST | `/api/jobs/{id}/plan/approve` | `{plan?, note?}` → `{stage: "building"}` |
| POST | `/api/jobs/{id}/retry` | `{from_stage}` → `{stage}` |
| POST | `/api/jobs/{id}/inventory` | multipart `files` (1–6 photos of everything from the box laid out on a table; ≤ 25 MB each) → `{items: [{id, kind: "part"\|"hardware", name, expected, seen, status: "ok"\|"missing"\|"extra"\|"unsure", note}], all_present, others, summary: {en, el}, photos, latency_ms, model, tokens, cost_usd, ts}`; 409 no plan yet or a check already running, 413, 415, 422 not 1–6 photos, 502 model failed |
| GET | `/api/jobs/{id}/inventory` | → the last inventory result; 404 before the first |
| GET | `/api/catalog?q=<text>` | → `{items: [{product, guide_id, article, job_id, steps?, parts?, created?, source: "built"\|"known", ready, guide?}]}`; ready guides first; `q` matches name, guide id or article number, ignoring case and accents |
| POST | `/api/catalog/open` | `{q, size?}` (name, article number like `902.891.72`, or IKEA product URL) → `{job_id, ready, product}`; `ready: true` opens a built walkthrough, `false` means a build started (follow `/api/jobs/{id}`); 404 when nothing matches |
| GET | `/files/{id}/{path}` | a file from the job folder: `walkthrough.glb`, `manifest.json`, `pages/…`, `renders/…`, `refs/…` |
| POST | `/api/jobs/{id}/ask` | `{step, question, lang?: "el"\|"en", frame?: base64 JPEG, history?: [{q, a}]}` → `{answer, actions, undo, cites: {page, step}, latency_ms, model}`; 409 no walkthrough yet, 422 unknown step or bad frame, 502 the model failed, 503 no `GEMINI_API_KEY` |
| POST | `/api/live/token` | `{job_id}` → `{token, expires_at, model, api_version}` (one per 10 s per address) |
| GET | `/healthz` | → `{ok, blender_version, cadquery_ok, job_running}` |

**Photo uploads.** Without a PDF, a person can photograph the paper guide: `server/photos.py` turns each photo upright, finds and flattens the page, cleans it to black on white and writes them as `guide.pdf` (one page per photo, ≤ 60 photos, ≤ 25 MB each), keeping the originals in `photos/page-001.jpg` …; the rest of the pipeline is unchanged.

**Parts check.** Before building, the person lays out all parts and hardware and takes 1–6 photos; `pipeline/inventory.py` sends them with the part renders and the plan's counts to `gemini-4-hydrogen` and writes `inventory.json` (photos in `inventory/`). The model may answer "not sure" for look-alike screws; then the item is `unsure` and the summary asks the person to count ("Count the 108560 screws: I see 20, the guide lists 24."). Each check also adds an `inventory` event (`text` = English summary, `text_el`, `all_present`, `image_url`, `url`). About 10–15 s and $0.02–0.03 per check (one photo, KNAGGLIG). CLI: `python -m pipeline.inventory <job_dir> photo.jpg …`.

**Ask the guide.** `POST /api/jobs/{id}/ask` answers a typed or transcribed question during assembly, in 1 or 2 short sentences in `lang` (default `el`), grounded in the job's own manifest step and its ordered actions, the plan step, the other steps, the guide's drawing of the step and, if sent, the camera frame (`pipeline/ask.py`, `gemini-4-hydrogen` at thinking `low`). `question` ≤ 500 characters; `frame` ≤ 2 MB (a `data:` URL is fine); only the last 4 `history` turns are used. `actions` are screen hints, each `{type: "go_to_step", step}`, `{type: "highlight_part", part_id}` or `{type: "highlight_hardware", code}`, and only name steps, part ids and codes the job has. `undo` is `null` unless the person describes something already done that does not match the guide; then it is the ordered list of sentences to put it right (what to unscrew or take off, latest first, then which step to do again). `cites` is the step and guide page the answer rests on. Typical latency 3 to 5 s.

**Catalog.** A guide that is not built or known yet is fetched from the manufacturer's public site (IKEA only, by article number or product page URL) at the person's request; the PDF stays on this server (`jobs/_catalog/`).

**Job state.** `stage` is one of `ingest`, `extract_plan`, `await_approval`, `parts`, `place`, `steps`, `refs`, `export`, `done`. `status` is one of `queued`, `running`, `waiting_approval`, `done`, `error`. The same stage names appear in every event and in `retry`.

**Events** (`events.jsonl`, also the SSE feed). Each SSE message has `id: <seq>` and `data: <json>`, with no `event:` name, so one `onmessage` handler receives everything:

```json
{"seq": 16, "ts": 1794690259100, "jobId": "1114-2304-61c9a6", "stage": "parts", "type": "part_status",
 "level": "success", "partId": "side_panel", "stepN": null, "text": "Built side_panel: 16 mate points", "ms": 566}
```

`type` is one of `thought`, `tool_call`, `tool_result`, `image`, `part_status`, `step_ready`, `approval_required`, `progress`, `error`, `done`, `inventory`. Images carry `image_url`. The stream sends a `: ping` comment every 15 s.

**A `make tunnel` URL (trycloudflare) buffers SSE: no event arrives until the stream ends** (measured 14 Nov). Everything else works through it. A client must fall back to `/events/poll` when the stream stays silent: the server sends `retry: 2000` at once, so a stream with no bytes after a few seconds is a buffered one. Whether the boxd URL streams SSE is not tested yet.

## Use it from an agent (MCP)

`server/mcp_server.py` is an MCP server over the HTTP API above, so Claude Code, Claude Desktop or any MCP client can turn a guide into a walkthrough. It needs a running backend (`make dev`, or the demo host).

```sh
python -m server.mcp_server                      # stdio
python -m server.mcp_server --http --port 8765   # streamable HTTP at http://127.0.0.1:8765/mcp
```

Settings: `AGJ_BASE_URL` (default `http://127.0.0.1:8000`) and `AGJ_DEMO_KEY` (else `DEMO_KEY` from `.env`). For Claude Code, copy `.mcp.json.example` to `.mcp.json` (ignored by git) and fill in the paths and the key, or run `claude mcp add assembly-guide -e AGJ_DEMO_KEY=<key> -- "$PWD/.venv/bin/python" -m server.mcp_server` from the repo root.

| Tool | What it does |
|---|---|
| `find_guide(query, size?)` | From what is on the box (product name, article number, barcode, the link of a QR code): opens a ready guide, or finds the PDF and starts the build; lists the choices when several match |
| `search_guides(query?)` | Searches the catalog without starting anything |
| `create_guide(pdf_path, size?)` | Uploads a local PDF guide; returns `job_id` |
| `guide_status(job_id)` | Stage, status, progress, error, and what to do next |
| `wait_for_guide(job_id, until="approval"\|"done", timeout_s=900)` | Long-polls the events until the plan needs approval, the walkthrough is done, or the job fails |
| `get_plan(job_id)` | Parts, hardware codes and counts, steps, flags, page links, and the raw plan |
| `approve_plan(job_id, note?)` | Approves the plan so the 3D build starts |
| `get_steps(job_id, lang="en"\|"el")` | Per step: title, guide page, parts, hardware, action sentences, check score, render and crop links |
| `guide_links(job_id)` | Phone and headset (`&mode=xr`) links for the person |
| `list_guides()` | All jobs, newest first (and the catalog when the backend has one) |
| `ask_guide(job_id, step, question, lang="en"\|"el")` | A short answer about one step of a finished guide |

Resource: `guide://{job_id}/manifest` is a finished job's `manifest.json`. File links carry `?key=` only when the backend is local; otherwise they are paths.

## Use it from a shell (command line)

`server/cli.py` is the same client as the MCP server, for scripts and for agents that run shell commands. Every command prints one JSON object on stdout and exits 0; a failure prints `{"error": "..."}` on stderr and exits 1. Settings: `AGJ_BASE_URL`, `AGJ_DEMO_KEY`.

```sh
python -m server.cli find "KNAGGLIG" --wait      # name, article number, barcode or link → steps and links
python -m server.cli build guide.pdf --wait      # a PDF on disk → steps and links (approves the plan on the way)
python -m server.cli search chair                # also: list, status, wait, plan, approve, steps, links, ask
python -m server.cli ask <job_id> 3 "which screw goes here?"
```

## For other agents (HTTP)

- `GET /llms.txt` (no key): what the service does and the shortest way to call it, in plain text.
- `GET /api/openapi.json` (with the key): every route with its request and response fields. The interactive `/docs` page is off.

## How a job runs

One job builds at a time. Every stage runs in its own process with a time limit, and writes `stages/<stage>.done` when it succeeds. After a crash or a restart the job continues from the first unfinished stage. `retry` clears the markers from one stage onward.

A stage process reports back on its standard output:

```
EVENT {"type": "thought", "text": "Part B rejected: holes on the wrong face"}
RESULT {"built": 6}
```

**Plugging in the agent** (see `server/orchestrator/stages.py`). When these exist they are used instead of the fallbacks:

- `pipeline/agent/extract.py`: `async def extract_plan(job_dir, emit)` writes `plan.json` and `crops/`.
- `pipeline/agent/parts.py`: `async def build_parts(job_dir, emit)` writes `parts/<id>.py`.

Until then a known test guide uses its hand-checked plan from `eval/golden/`, and the feed says so. Any other guide stops at `extract_plan` with a clear message.

**Phase-2 WebSocket.** The server includes `server/perception/routes.py` (`router`) when that module exists. Use `server.auth.ws_has_key` to check the demo key.

## Deploy

```sh
boxd auth login          # once, in a browser
make deploy-box          # creates or updates the VM `agj`, installs Blender, starts the service
eval/smoke.sh https://agj.boxd.sh
```

Before every push: `make secrets`.
