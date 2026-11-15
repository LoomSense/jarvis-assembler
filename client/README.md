# Client (phone, laptop and Quest 3)

Vite + TypeScript + vanilla three.js `0.187.0` (pinned; PLAN §7, D14). No framework.

One page, two experiences. `mode` in the address picks one, and only that one is loaded: its code, its styles and its markup.

| Address | What it opens | Where it lives |
|---|---|---|
| `/` (no `mode`) | [The guide page](#the-guide-page-no-mode) for phone and laptop | `src/guide/`, `src/styles/page.css`, the `<template>` in `index.html` |
| `/?mode=build` | [The build screen](#the-build-screen-modebuild): drop a guide PDF, watch the agent build the 3D guide, approve its plan, open the result | `src/build/`, `src/styles/build.css` |
| `/?mode=xr` | [Quest 3 experience `xr`](#quest-3-modexr-plan-60): glass panels, step-by-step guidance, parts card, voice; built-in products or a pipeline job | `src/xr/`, `src/styles/xr.css` |

A second Quest experience built in parallel was dropped on Sun 15 Nov in favour of `src/xr/`; its live-guidance join can be ported later through `src/assist/`.

## Run

```bash
cd client
npm ci
npm run dev            # http://localhost:5173/            the guide page (opens jobs/local-fresh; add ?job=<id> for another job)
                       # http://localhost:5173/?mode=xr    desktop preview of the `xr` experience
npm run typecheck
npm run build          # type-checks, then writes client/dist, which the server serves at /
npm run preview        # serves client/dist the same way, for a last look at the built page
npm run shots          # the browser check of the guide page, see below
npm run quest          # Quest on USB (adb reverse), see the `xr` section
npm run tunnel         # a public https address for the dev server (cloudflared)
npm run dev:https      # the dev server with a self-signed certificate, for the LAN
```

The dev server (`vite.config.ts`) adds two routes, so the pages need no backend:

- `GET /files/<job>/…` serves `<repo>/jobs/<job>/…` (or `$JOBS_DIR`), also under `npm run preview`. In production FastAPI serves the same URLs behind the demo key. Only the kinds of file a page loads are served (`.json`, `.glb`, `.png`, `.jpg`, `.jpeg`, `.webp`, `.mp4`); anything else in a job folder answers 404, a path that leaves the folder 403.
- `POST /api/tts` speaks a line with Gemini TTS for the `xr` experience. The key is read from the repo-root `.env` and stays in the dev server; without a key it answers 503 and the page uses the browser's voice.

## How the three share one page

| File | Job |
|---|---|
| `index.html` | The document `?mode=xr` was written for: an empty `#app`. The guide page's markup waits in a `<template>`, where nothing of it is drawn or styled. A small script in the `<head>` makes the document dark and Greek-first for the guide page, before the first paint; `?mode=xr` gets the document untouched |
| `src/main.ts` | Reads `mode` and loads one of `src/guide/page.ts`, `src/xr/xrMain.ts` (`startXR`). Each is its own chunk in the build, with its own stylesheet, so a phone never downloads a headset experience and the styles of one page (`page.css` and `xr.css` both style `html` and `body`) never reach another |
| `src/styles/tokens.css` | A copy of `docs/design/tokens.css`, loaded for all three |
| `vite.config.ts` | The two dev routes above, HTTPS on request, the build settings |

`src/api.ts`, `src/types.ts`, `src/i18n.ts`, `src/viewer/` and `src/assist/` are shared by the guide page and the `headset` experience. `src/xr/` uses none of them: it has its own strings (`src/xr/i18n.ts`), job loader and voice.

## The guide page (no `mode`)

One full-screen scene that walks a person through a flat-pack guide, one action at a time.
A workshop at night: the assembly stands on a disc under one warm lamp, with a cool light from behind, the pieces still in the box hover in a row at the top with their counts, and the piece to pick up now lies large and sharp between two round buttons. Press Next and the piece comes down from the row and goes on. Greek by default, English with `?lang=en` or the two letters in the corner.

One WebGL context, one canvas.

The page loads `/files/<job>/manifest.json`, then the GLB beside it. In dev and in `npm run preview` a small Vite plugin (`vite.config.ts`) serves `/files/<id>/…` from `<repo>/jobs/<id>/` (or `$JOBS_DIR`), so no backend is needed. In production FastAPI serves the same URLs.

### URL parameters

| Parameter | Meaning |
|---|---|
| `job=<id>` | The job to show. Required in the built page. In dev it defaults to `local-fresh`; `?job=` (empty) shows the page without a guide. Without a job, on the real server, the page says so in one line and offers the build screen. |
| `key=<demo key>` | Repeated on every `/files/` request. Never stored. |
| `lang=el\|en` | Language. Default `el`. The switch in the corner rewrites this parameter. |
| `step=<n>` | Open at the start of step `n` (1-based) instead of at the first action. |
| `at=<n>` | Debug: open with action `n` (0-based) already done. |
| `material=<name>` | Debug: dress the boards as another product would arrive: `white`, `black`, `grey`, `oak`, `walnut`, `steel`, `plastic`. |
| `glb=plain` | Debug: try the plain GLB before the packed one. |
| `mode=xr` | Open the Quest experience instead of this page (see the top of this file). |
| `assist=1\|0` | Live guidance: `1` offers its control where it would not be offered (the dev server), `0` leaves it out. |
| `api=<url>` | Dev only: the server to talk to: its files, its jobs and the perception socket for live guidance. The built page ignores it, because it decides where camera frames go. |

`phase` and `speed`, which the first version of the page read, are still parsed by `src/api.ts` but this page ignores them.

**Which GLB.** The manifest's `glbPacked` is loaded first: it is a tenth of the size (about 0.2 MB against 2 MB). Its vertices are packed as integers, so the page writes them back as metres once after loading (`bakeQuantised` in `src/guide/materials.ts`); after that the packed and the plain model look the same. If the packed file fails to load, or lacks a node or a clip the manifest names, the plain `glb` is used.

### What is on screen

- **The row at the top.** One hovering copy per part id and per hardware code, with the number still in the box under it and, for hardware, its code. A kind that runs out leaves the row and the others close ranks.
- **The piece between the buttons.** The kind the current action uses, as large as fits. A screw is drawn large enough to read the cross in its head.
- **One line of words.** A screw shows its code and which one it is of how many in this step (`108560  3/8`). A board shows its short name. A turn of the whole assembly shows two or three words. The end shows one word. A screen reader gets the full sentence.
- **Two round buttons**, previous and next, and the arrow keys. Next during an animation finishes the action on screen at once and starts the next one. Previous sends the piece back up to the row (a turn runs backwards).
- **A hairline along the bottom edge**: how far through the actions, or, while loading, how much of the model has arrived.
- **A small azure mark** where a board will sit, while it is on its way. A screw has the model's own ring at its hole.
- **Live guidance**, where the server can be reached: one quiet round control in the top left corner, the counterpart of the language switch. See [Live guidance](#live-guidance).

The frame loop never stops and the orbit controls are never disabled: a press always takes the camera and cancels any camera move. With `prefers-reduced-motion` nothing drifts, pieces do not fall or rise, the camera does not move by itself and the finished piece does not turn; the insertion itself still plays.

### How it is put together

| File | Job |
|---|---|
| `index.html`, the `<template id="guide-page">` | The canvas, the list of counts, the seat mark, the line of words, the two buttons, the hairline, the language switch. `src/main.ts` moves it into `#app` for this page only |
| `src/guide/page.ts` | Show the markup, read the URL, show the loading line, load manifest and GLB (packed first), start the guide, own the language switch |
| `src/guide/guide.ts` | The page itself: kinds of piece, the row and the large piece, one beat per action, the fall and the rise, the camera, the words |
| `src/guide/stage.ts` | Renderer, the room (disc, lamp, rim light, dust), the two scenes drawn into one canvas, pixel budget (device pixels up to 3x within 6.2 million; fewer if the device stays under 27 frames a second) |
| `src/guide/materials.ts` | What each surface is made of, from the GLB material's name; grain, thread, finishes; the packed GLB's coordinates |
| `src/guide/words.ts` | The few words the page writes itself, and the short phrase for a turn |
| `src/guide/tool.ts` | The screwdriver |
| `src/guide/watch.ts` | Live guidance on the page: the control, the state words, the short line, what reacts. Loaded only when the control is offered |
| `src/styles/page.css` | Everything that is not 3D. Tokens come from `src/styles/tokens.css` (a copy of `docs/design/tokens.css`) |
| `src/api.ts`, `src/types.ts`, `src/i18n.ts` | URL parameters and `/files/` URLs; the manifest's shape; UI strings and manifest texts in two languages |
| `src/viewer/model.ts` | Index the GLB by manifest names; read a screw's head, tip and axis from its vertices |
| `src/viewer/steps.ts` | `StepPlayer`: poses the model for "step, action, time" (PLAN §4.3) |

`src/api.ts`, `src/types.ts`, `src/i18n.ts`, `src/viewer/model.ts` and `src/viewer/steps.ts` are shared modules. `src/assist/` is a separate module with their own authors. The page loads `src/assist/` only through `src/guide/watch.ts`, when live guidance is offered.

#### Two scenes, one canvas

`Stage.draw` renders the workshop with the orbit camera, then clears depth and renders the tray (the row and the large piece) over it with a camera that never turns. Both use the same projection, so a point of the tray has a place in the workshop.

A piece that leaves the row is the real node of the model, carried from the pose of its hovering copy to the pose where its step clip starts (`carry` in `guide.ts`). While it travels it is drawn a second time on its own layer with the tray's light mixed in (`Stage.flight`), from all of it at the row to none of it under the lamp. That is why the hand-over from the copy to the real piece shows no jump.

Lamp, rim light, a weak fill, dust and the reflected room hang on one rig that turns with the camera about the disc, like fixed studio lights over a turntable: from every side the key is up on the left. Only the lamp casts a shadow, and nothing is drawn after the two scenes: no post-processing.

#### Beats

A beat is one action of the manifest (`place`, `fasten`, `turn`), plus one closing beat that turns the finished piece the right way up. A step for which the manifest lists no actions is one beat that plays its whole clip and shows the step's title. `begin(i)` starts a beat: the fall (phase `fly`), then the step clip between the action's `t0_s` and `t1_s` (phase `clip`). `back()` takes the beat on screen back (phase `rise` or `rewind`). The page owns the clock and sets the clip time through `StepPlayer.apply`.

#### The camera

Each step is measured once before anything is shown (`measure`): the corners of every board in the finished step, of every piece where its insertion starts, the far end of the screwdriver on every screw, and the assembly at seven moments of every turn. `frameCamera` puts all of them between the row and the large piece and inside the sides of the screen, looking from the side the guide draws the step from, or from outside the face the screws enter when the guide's view has the holes on the far side. When the new view is further away the camera steps back first and swings round after, so a turning assembly has its room at once. The finished piece gets the whole screen.

#### Surfaces

The GLB carries flat colours and no textures. The pipeline names each GLB material after the plan's `material` field, or `hw_<code>` for hardware. `materials.ts` decides from that name:

| The name says | Finish |
|---|---|
| a wood (`pine`, `oak`, `birch`, `beech`, `ash`, `walnut`, `bamboo`, `wood`, `plywood`, …) | The GLB's colour with a grain drawn in the shader along the long axis of each slat |
| a plastic | Soft: the GLB's colour, no metal, little reflection |
| a metal, or the GLB says metallic | Metal |
| anything else (`white laminate`, `black paint`, …) | A coated board: the GLB's colour, smooth, with a slight sheen |

Hardware is plated metal in the GLB's colour. A thread is drawn only on hardware whose manifest `kind` is `screw`, and only those get the screwdriver. The warmth of the picture comes from the lamp and the disc, not from the material: check with `?material=white` and `?material=black`. A very dark board picks up a little of the cool light on the faces that turn away, so its shape reads against the dark room.

The warm hairline at the bottom takes the colour of the warmest hardware plating in the manifest (`hardware[].color`) and falls back to a neutral warm.

#### Words

`src/guide/words.ts` holds the few words the page writes itself. A turn is shown by the pose the step ends in (`upside_down`, `upright`, `on_side`, `on_end`, `on_end_a`, `on_end_b`, `on_other_end`). A pose that is not in that table falls back to the first clause of the manifest's own action text. Names of parts and hardware come from the manifest through `displayName` in `src/i18n.ts`.

### Live guidance

The camera watches the hands, the server says what it sees (`server/perception/README.md`), a voice says the sentence and the page shows the few words that still work with the sound off. The camera, the socket and the speech are `src/assist/`; `src/guide/watch.ts` joins that module to the page. Nothing in it ever presses Next.

**Where the control is offered.** Only where its socket can exist, and only in a browser with a camera API:

- an API base was given: `VITE_API_URL` at build time, or `?api=` (read in dev only);
- or this is the built page and the host it came from answers as the server (`GET /healthz` gives `{ok: true}`; a static host such as `npm run preview` does not, so there the control is absent);
- or `?assist=1` forces it. `?assist=0` leaves it out anywhere.

The dev server has no perception socket, so in plain dev the control is absent. The module's code (about 25 kB) is fetched only when the control is offered; the camera and the socket open only on a tap. A page whose control is never touched does nothing for guidance.

**What the person sees.**

| | |
|---|---|
| Off | A small eye in the top left corner, as quiet as the two letters of the language switch. The tap is the gesture the browser wants for the camera question and for speech. |
| Watching | The same spot is the camera's round picture with a thin ring: 38 px, in the strip above the row of parts, and 52 px in a window at least 1100 px wide, where the corner is free. A tap stops it: the socket closes and the camera is released. It shows that the camera is on and what the server makes of it; it is too small to aim a camera by. |
| The ring | The module's own: every state has a stroke pattern and a small mark as well as a colour (solid: watching; thick with a check: right; dashed with an octagon: wrong; long dashes with a diamond: paused; faint with a struck circle: no vision). |
| Not watching, or not really | Two or three quiet words beside the picture: `προσομοίωση` while the answers come from the server's script (the picture is hatched and grey as well, and the voice says so once), `χωρίς εικόνα`, `δεν βλέπω τώρα`, `ξανασυνδέομαι`, `δώσε άδεια για την κάμερα`, `αυτό το βήμα δεν ελέγχεται` and so on. |
| What was seen | One short line under the page's own words, between the two buttons, with the brand's status shape before it: `Κρατάς 108560` (octagon), `Σωστό` (circle with a check), the turn's two or three words when the assembly stands the wrong way (triangle), `Όχι ακόμα: απλή σανίδα` (triangle), `Δείξε το στην κάμερα` (diamond). It is built from the event's `observed_id` and `expected_id` with the page's own names, so it stays on one line on a phone where the server's sentence took three. The page's own line moves up a little to make room; nothing else moves. The server's full sentence is in the page for a screen reader, and is spoken. An event the page does not know shows the first sentence the server wrote for it. |
| On the thing itself | While the hand holds something else than the action needs, that kind gets the octagon beside its count in the row, and the lamp over the piece between the buttons comes up: this one. A right pick lifts the lamp once. |
| Step complete | A second ring around Next, breathing slowly (still with reduced motion). No words, and the page never advances by itself: that signal is weak, and the person decides. |

The voice reads the server's sentence without the six-digit hardware codes (`speakCodes: false`): a code read aloud takes about ten seconds, and the screen shows it. A hint about another step or action than the one on screen is not shown. If guidance cannot work (no vision on the server, camera refused, no connection, a job the server did not build), the words beside the picture say so and the guide works exactly as without it.

### Things learned from the real GLB that the code relies on

- Parts not placed yet sit at scale 0.0001 and the step clips have no scale tracks for them. `prepareModel` gives every part its real scale once and `StepPlayer` shows or hides it with `visible`.
- A step clip touches only that step's nodes. Earlier parts are put in the pose of the last key of the clip that placed them.
- The ring (`markerNode`) is moved and scaled by the step clips but not turned, so `StepPlayer` faces it along the screw being driven.
- When `StepPlayer` moves to another step it clears the positions its clip drives only after the mixer has taken them as its starting values, and a mixer does not write a value equal to its starting one. At the first moment of a step that begins with a turn the assembly would therefore sit at the origin. `pose()` in `guide.ts` writes those values once per step. The same would be fixed for every user of `StepPlayer` by clearing the positions before `action.play()` in `StepPlayer.activate`.

### The browser check

```
node scripts/shots.mjs [outDir] [baseUrl] [job] [--quick]
```

Defaults: `client/.shots` (ignored by git), `http://127.0.0.1:5173/`, no job (the dev server opens `local-fresh`). It drives the installed Chrome through `puppeteer-core` at 1512×864 (device scale 2) and 390×844 (device scale 3), saves screenshots and a `report.json`, and exits with 1 when anything fails:

- every action forward at full speed, measuring on every sample that no piece is cut by the side of the screen or runs into the row or into the large piece; then every action back;
- a drag during a fall keeps producing frames and turns the camera; Next during an animation starts the next action; Previous moves the piece; a press cancels a camera move;
- reduced motion: no fall, no camera move, the insertion plays;
- English, the language switch, the demo key on every `/files/` request, the fallback to the plain GLB, the loading line, the error line;
- `?material=white` and `?material=black`;
- no console error or warning, page error or failed request that a step did not ask for.

It cuts the page off from the dev server's hot-reload socket, so a file saved by someone else does not reset a run. `window.__guide` holds what the check reads (`cur`, `phase`, `frames`, `beats`, `kinds`, `extent()`, `next()`, `prev()`, `begin(i)`, `finishNow()`).

#### Live guidance

```
# from the repo root: a server with scripted answers, and (optional) one with no vision at all
PERCEPTION_SIMULATE=true .venv/bin/python -m uvicorn server.app:create_app --factory --host 127.0.0.1 --port 8137 --log-level warning
GEMINI_API_KEY= PERCEPTION_SIMULATE=false .venv/bin/python -m uvicorn server.app:create_app --factory --host 127.0.0.1 --port 8138 --log-level warning

# from client/: the built page as the server serves it ...
node scripts/assist.mjs <outDir> http://127.0.0.1:8137/ "" 1115-0022-2d4ab6 --novision=http://127.0.0.1:8138
# ... or the dev page, with the server as its API base
node scripts/assist.mjs <outDir> http://127.0.0.1:5173/ http://127.0.0.1:8137 1115-0022-2d4ab6 --novision=http://127.0.0.1:8138
```

Defaults: `client/.shots/assist`, `http://127.0.0.1:5173/`, the page's own origin, job `1115-0022-2d4ab6`. The warning log level keeps the demo key, which travels in URLs, out of the server's log. The script reads `DEMO_KEY` from the environment or from `<repo>/.env` and never prints it. Chrome runs with a fake camera, speech at volume 0, and it checks at both sizes:

- the control is offered and, untouched, opens no camera, no socket and says nothing;
- one tap starts it: frames flow and are acked; simulated mode is marked on screen and said once;
- the step and the action follow Next and Previous on the server;
- a wrong screw gives the mark, the words and the sentence exactly once, with no code read aloud; the right one gives the confirmation; the other language works while it watches;
- step complete cues Next and the page stays where it is; stop closes the socket and releases the camera;
- no vision on the server, no connection, failing answers, a refused camera: each is said in words and the guide walks on.

`window.__watch` holds what it reads (`assist`, `status`).

### Departures from `docs/design/BRAND.md`

The client asked for a bare scene: no sections, no pills, no titles, two buttons. So:

- No top bar, cards, chips, glass bars, step counter or guide crop (§4 "Walkthrough"). The guide's own drawing and page numbers are not shown, and neither is the `estimated` tag (§1, principle 1).
- The two buttons are round hairline rings with an icon and an `aria-label`, not pills with a verb.
- Words are set in a warm paper white (`#f6ecdb`), not pure white. On black it is 17:1; the quiet tone is 8:1. Sizes are the scale's 21, 15 and 12.5 px; the codes use the label size without capitals.
- A second accent: the warm hairline, taken from the product's own hardware. Azure stays the one cool accent and is used only as light (the rim light, the ring at a hole, the seat mark).
- Dark only. The page is a night scene and ignores the light theme.
- Live guidance shows a state as a ring pattern, a mark and two or three lower-case words beside the camera's picture. The brand's "vision paused" pill and badges are not used. The status colours and shapes are the brand's.
- Motion in the scene runs on its own clock (falls of 0.6 to 0.85 s, camera moves of 1.1 s, cubic in and out). A seated piece dips by under 2 mm once, without overshoot. The finished piece turns slowly on its disc until it is touched. Interface transitions use the brand's easing and durations.

### Known gaps

- The manifest gives part names, hardware names and step titles in English only. `src/i18n.ts` carries a small Greek table for the names of the sample product; a name it does not know is shown in English.
- The manifest does not carry a part's material. The page reads the name of the GLB material instead, and the pipeline's material table knows only `pine` and `steel` today (any other name gets pine's colour).
- Only a hardware `kind` of `screw` gets the screwdriver and a thread. Other kinds (dowel, cam lock, nail) fall and seat with the same motion, without a tool.
- The room is sized from the footprint of the assembly. It was tuned on a crate about 0.4 m long; a wardrobe has not been tried.

## The build screen (`?mode=build`)

What a judge watches: a flat-pack guide (PDF) goes in, the agent reads it and builds the 3D guide, live. The same dark room as the guide page, a single column of light words, the newest line brightest.

1. **Find, drop or pick.** One quiet line to type in ("όνομα, κωδικός ή link"): as you type, the matches of `GET /api/catalog` (a built guide opens its ready screen, a known one says it will be built); Enter or a choice calls `POST /api/catalog/open`, which opens a built guide or starts its build and the feed follows it. 404 and 502 each get one short line under it. Under that, one round drop target (tap to choose on a phone, with a camera button) that takes one PDF or photos of the guide's pages, in the order chosen; photos add up, a PDF replaces them. Once something is chosen: the photos as small pictures, an optional size field and one round button that sends it (`POST /api/jobs`, `file` or repeated `pages`, and `size`). Under it all, the recent jobs from `GET /api/jobs`, one per guide: a finished one opens its ready screen, any other its feed. The logic is in `src/build/intake.ts`, apart from the markup.
2. **The agent at work.** Every event of `GET /api/jobs/{id}/events`, as it comes: the agent's thoughts as lines, tool calls as compact rows (paths in monospace), the pages and renders it opens as small pictures (a `Looking at <file>.png` is shown from `/files/<id>/agent/<file>`), the images the stages make, a rejected build in orange with the fit problems, then its fix. At the top: the stage and the elapsed time, both from the events. No percentages. The pipeline's fixed sentences are said in Greek (`src/build/words.ts`); the agent's free thoughts stay in its English.
3. **Approval.** When the job really waits (`approval_required` and `GET /api/jobs/{id}` says `waiting_approval`): the plan in brief (title, kinds of part, pieces, steps, hardware codes and counts, the flags the agent raised) and one round button, Approve.
4. **Done.** One round button opens the ready screen for the job (`/?mode=ready&job=<id>&key=…&lang=…`); until that screen exists, the unknown mode falls through to the guide page.

The stream first; if it has said nothing for 4 s while `GET …/events/poll?wait=0` has events it did not bring (a cloudflared quick tunnel buffers SSE), the page closes it and long-polls `…/events/poll?since=<seq>&wait=20` from then on.

| Parameter | Meaning |
|---|---|
| `job=<id>` | Show this job's feed (live while it runs, the whole story when it is done). Without it: the drop target. |
| `key=<demo key>` | Sent on every request (header, or `?key=` for the stream and pictures). Never stored. |
| `lang=el\|en` | Language. Default `el`; the two letters in the corner switch it, the feed included. |
| `auto=1` | For a demo: approve the plan by itself 2 s after showing it, and open the guide 2.6 s after it is ready. A job that was already finished when the page opened does not leave by itself. |
| `transport=poll\|sse` | Debug: start with the long poll, or never leave the stream. |
| `api=<url>` | Dev only, as above. |

**Without spending on the agent.** `node scripts/build-stub.mjs` serves the job API on port 8788 and replays a recorded run (`jobs/1115-0022-2d4ab6/events.jsonl`, time compressed; it waits for Approve; key `stub`). Open `http://127.0.0.1:5173/?mode=build&api=http://127.0.0.1:8788&key=stub`. `BUFFER_SSE=1` makes its stream silent like a quick tunnel; `FIX=1` adds a rejected build and its fix (a rendering test: the recorded run had none). It also answers the catalog (`KNAGGLIG box` built, `MARKUS office chair` and a stub `BEKVÄM step stool` known; `999.999.99` answers 502, other text that matches nothing 404) and takes photo uploads (`GET /stub/last` says what the last upload carried).

**The check.** With two stubs running (`node scripts/build-stub.mjs &` and `PORT=8789 BUFFER_SSE=1 node scripts/build-stub.mjs &`), `node scripts/build-shots.mjs [outDir]` drives Chrome at 1512×864 and 390×844 through drop, upload, feed, approval, done and the guide it opens; then the long-poll fallback with `?auto=1` end to end, English with reduced motion, the recent list, the guide page's empty state, no key and no server; and at both sizes the search (matches, 404, 502, a known guide that starts its build, Enter on a built one), and with `PHOTOS=<folder with page-1.jpg … page-4.jpg and page.heic>` photos in order with a size, HEIC refused, a PDF with photos refused. It fails on any console error, page error or failed request.

**Against a real server from the dev page**, the server must allow the dev origin: `CORS_ORIGINS=http://127.0.0.1:5173`. The built page is served by the server itself and needs nothing.

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
| `judge=0` | Turn off the hand judge (`handJudge.ts`): the ghost then changes colour only from live guidance and the buttons. |
| `sim=1` | No real furniture: placed parts are solid, a "Show mistake" button plays the wrong-part warning. Defaults the product to the LACK side table. |
| `product=lack\|knagglig` | Built-in hand-made product when no `job` is given. |
| `lang=el` | Greek UI, step text and voice. |
| `voice=0` | No speech. Otherwise Gemini TTS via the dev server's `POST /api/tts` (key in the repo-root `.env`, never sent to the browser), falling back to the browser's voice. |
| `auto=0` | No hands-free confirm (hand held still on the part for 1.5 s = Next). |
| `debug=1` | Tracking diagnostics (`planes · hit-test · anchor · hands`) in the panel footer: evidence for XR-01. |
| `hands=0` | Hide the hand joint spheres. |

To build a job: `.venv/bin/python -m pipeline.run eval/golden/knagglig/plan.json jobs/knagglig` (needs Blender 5.3 and the pipeline venv, see `pipeline/README.md`). Guide crops for the built-in products: `scripts/demo-crops.sh`, `scripts/lack-crops.sh` (git-ignored: the guides are IKEA's).

### Files

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
| `src/xr/handChecks.ts` | `hand_at_target`, `still`, `raised` at 10 Hz, sent to the perception socket through `live.ts` |
| `src/xr/handJudge.ts` | Green or red from hand tracking alone: green when a hand is at the ghost, red when a hand stays where another part goes (a different screw's hole, a later board's place). No camera or server needed |
| `src/xr/dims.ts` | A part's size as text (`≈ 436 × 282 × 30 mm`, `≈ ⌀5 × 55 mm`), shown on the panel, on the name tag and by the hand |
| `src/xr/demoAssembly.ts` | Hand-made KNAGGLIG box and LACK side table (sizes estimated), guide tips |

## Headset check page

`/xr-check.html` measures in one visit what the Quest gives a web page (features granted, hands, planes, anchors, hit test, depth, camera frames before, during and after an `immersive-ar` session, microphone) and posts the report to `POST /api/diag` when opened with `?key=`. Run under emulation only so far.
