# Submission kit (draft, Sun 15 Nov)

Draft texts for the submission form, the 1-minute video and the 3-minute pitch. Numbers are measured ones from `docs/PROGRESS.md`; replace any that change before 17:30. Nothing here claims what has not run: see "Honest limits".

## Project details (for the form)

**Name:** Assembly Guide Agent

**One line:** Upload any flat-pack furniture guide; an agent rebuilds it in 3D, step by step, and then watches you build the real thing and corrects you before you make a mistake.

**What it does (about 120 words):**
Paper assembly guides are drawings without words. Our agent reads the PDF page by page, writes the plan (parts, hardware with their codes and counts, steps in the guide's order), writes CadQuery code for every part, assembles them with fit checks that fail on a gap of half a millimetre, and fixes its own scripts until the build passes. It then compares every step it built with the guide's own drawing. The result plays on a phone as a calm 3D walkthrough, one action at a time: this board, this screw, here. While you build, a camera sends frames to a vision model that knows which screw the current action needs, and the guide says so out loud when you pick up the wrong one. On a Quest 3 the next part appears as a ghost on your real table.

**How it was built:** a build agent on the Claude Agent SDK (plan and part scripts, with a sandboxed `./build` tool), Gemini 4 Hydrogen and Carbon for reading drawings and writing per-step vision questions, Gemini Decision 1 for live frame checks, CadQuery and Blender for geometry and animation, FastAPI with SSE and WebSocket, three.js with WebXR. All built during the event; third-party code is listed in `CREDITS.md`.

**Track:** Open Track (hardware: Meta Quest 3).

## Measured so far

- Build agent from the PDF alone: KNAGGLIG box 438 s, $2.12, first build passes; LACK side table 121 s, $1.03; MARKUS office chair 12 min, $2.94 (sub-assemblies); BEKVÄM step stool. Through the server end to end: 409 s for KNAGGLIG, 143 s for LACK.
- Fit checks: 37 pieces, 0.0 mm worst residual; plan scorer gates 1.00 / 1.00 / 1.00 against a hand-labelled plan.
- Vision: one Decision 1 call about 0.5–1.1 s warm; step completion 2.6–3.8 s after the scene changes with 4 calls in flight (rendered stand-in frames). On generated photos of a hand holding a screw at step 2 (needs the long 108363): long screw named 108363 at 0.96; short screw judged "not the right one" at 0.95.

## Honest limits (say them if asked)

- Live vision has not yet been tested with a real camera on the real product; the stand-in tests use renders and generated photos.
- Nothing has run on a physical Quest 3 yet; the headset mode is tested under Meta's emulator.
- Step completion from vision is weak on some steps, so the person always keeps Next and Back.

## 1-minute video (shot list)

| Time | Shot | On screen / voice |
|---|---|---|
| 0–5 s | A pile of KNAGGLIG parts and the paper guide | "Assembly guides are drawings. No words." |
| 5–20 s | Upload the PDF on `/?mode=build`; the agent's feed scrolls (sped up): reading pages, writing parts, a fit check failing and the fix, a step render beside the guide's drawing | "An agent reads it, builds every part in 3D and checks itself against the guide." |
| 20–38 s | The phone walkthrough: a board falls into place, a screw is driven, `108560 3/8` | "Then it shows you one action at a time." |
| 38–52 s | Live guidance: hand picks the wrong screw, the ring turns, the voice says which one is needed; then the right one, "Ωραία" | "And it watches you build." |
| 52–60 s | Headset view of the ghost on the table, then the finished box | Name and "Gemini · Claude Agent SDK · CadQuery · Blender · three.js" |

## 3-minute pitch (outline)

1. **0:00–0:25** The problem: everyone has fought a flat-pack guide; the drawings are precise but say nothing.
2. **0:25–1:20** Live: drop a PDF on the build screen and show its feed running; switch to a pre-built job (KNAGGLIG or the MARKUS chair) on judges' phones by QR.
3. **1:20–2:15** Live at the table: phone or headset, the wrong screw, the spoken correction, the right one.
4. **2:15–3:00** How the agent checks itself (fit checks, drawing scores, vision questions written per step), what it costs per guide (about $1–3), what is next (any manufacturer's guides, other languages).
