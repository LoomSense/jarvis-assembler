# Live intelligence: perception, decision logic, voice

Docs checked on 2026-11-14. Doc URLs on ai.google.dev now redirect (301) to aistudio.google.com/docs/…, and this section cites the pages after the redirect. **UNVERIFIED** marks anything we could not confirm on a fetched page.

## 0. Architecture at a glance

```
Phone (browser, on stand)
 ├─ getUserMedia camera → MediaPipe HandLandmarker (on-device gate, ~every frame)
 ├─ JPEG 768px frames ──WebSocket──► Backend "perception loop"
 │                                    ├─ builds Decisions request (cached per-step questions + composite ref)
 │                                    ├─ POST /v1beta/decisions (gemini-decision-1, API key server-side)
 │                                    ├─ smoothing + per-step state machine
 │                                    └─ emits events ──WebSocket──► Phone
 ├─ Gemini Live session (gemini-3.8-live, AUDIO only, ephemeral token, client-direct)
 │     ◄─ phone injects perception events as text turns; handles tool calls locally
 └─ 3D viewer (Phase 1 output): highlight_part, step navigation
```

Perception runs on the server because Decisions has no ephemeral-token story. Ephemeral tokens "work only with the Live API" ([ephemeral tokens](https://aistudio.google.com/docs/live-api/ephemeral-tokens)). The voice session runs directly from the client to Google, which keeps an extra hop out of the audio path.

## 1. Perception with the Decisions API

### 1.1 What goes into the shared `input`

The [Decisions API docs](https://aistudio.google.com/docs/decisions-api) state that the API has **no caching**, so repeated input "counts in full on every request". We therefore send **two images**:

| # | Block | Resolution | Notes |
|---|---|---|---|
| 1 | Text: step header | – | Step number, guide page, parts joined, hardware with counts, connection points, and orientation cues taken from the plan, about 150–250 tokens |
| 2 | Image: **live frame** | `medium` (560 tok) | Cropped to the work-area ROI that was calibrated at session start |
| 3 | Text: "Image 2 is a reference sheet…" | – | Explains the composite layout |
| 4 | Image: **composite reference sheet** | `medium` (560 tok) | Left half shows the reference render of the step's correct result from the phone viewpoint. Right half is a grid of the candidate part renders, each with a large printed label (`A_side_panel_left`…), plus the hardware icons with their guide codes (e.g. `104322`) |

The composite is **pre-rendered once per step** in Phase 1 and served as a static PNG. Three separate images would cost 1,680 image tokens per request; the composite costs 1,120. Candidate parts are the step's parts, any look-alikes (mirror-image panels, the same panel with a different hole pattern), and the parts of the next step. Including the next step's parts lets us detect out-of-order work.

### 1.2 Who writes the questions

At guide-load time, **`gemini-4-hydrogen`** (thinking `medium`) reads each plan entry and outputs that step's `questions` object as JSON, using a fixed template. The output is validated against a JSON schema: keys must match `[A-Za-z0-9_-]+` and be at most 64 characters, and choice questions need 2–255 options ([limits](https://aistudio.google.com/docs/decisions-api)). It is cached per step, keyed by `guide_id/step_n`, so each step costs one Hydrogen call (about a cent). Hydrogen's main job is writing the **orientation criteria**, for example "the row of cam-lock holes faces up and the finished edge faces the user". The generic questions (hands present, image quality, view blocked) are fixed and never generated.

### 1.3 Example request (step 4: attach left side panel to base)

Field names are taken from the [docs](https://aistudio.google.com/docs/decisions-api): `type`, `instructions`, `criteria` (`true_description` and `false_description` for `noul`, an option→description map for `choice`, an ordered array for `score`).

```json
{
  "model": "gemini-decision-1",
  "input": [
    {"type": "text", "text": "Flat-pack assembly, guide page 6, STEP 4 of 12. Goal: attach the LEFT side panel (A) to the base panel (C) using 4 cam-lock bolts (104322) already pre-screwed into C, then lock with 4 cam locks (100214). Panel A must stand vertically with its pre-drilled cam-lock holes facing INWARD toward the base and the shelf-pin hole rows toward the FRONT. Image 1: live camera view of the user's work area."},
    {"type": "image", "mime_type": "image/jpeg", "data": "<base64 live frame>", "resolution": "medium"},
    {"type": "text", "text": "Image 2: reference sheet. LEFT half: what the work area should look like when step 4 is done, from roughly this camera angle. RIGHT half: labelled renders of candidate parts and hardware."},
    {"type": "image", "mime_type": "image/png", "data": "<base64 composite step4.png>", "resolution": "medium"}
  ],
  "questions": {
    "part_in_hand": {
      "type": "choice",
      "instructions": "In Image 1, which part from the reference sheet is the user currently holding or lifting?",
      "criteria": {
        "panel_A_left_side": "Tall side panel, cam holes near the bottom edge, shelf-pin holes along the front edge (left variant)",
        "panel_B_right_side": "Mirror image of A: same size, holes on the opposite face",
        "panel_C_base": "Wide horizontal base panel with 4 bolts sticking up",
        "panel_D_shelf": "Short shelf panel with no cam holes",
        "panel_E_back": "Thin fibreboard back sheet",
        "hardware_only": "User holds only small hardware (screws, bolts, cam locks) or a tool",
        "nothing_held": "Hands empty, or no hands visible",
        "other": "Holding an object not shown on the reference sheet, or cannot tell"
      }
    },
    "right_part": {"type": "noul", "instructions": "Is the user holding the LEFT side panel (A) needed for step 4?",
      "criteria": {"true_description": "The held panel matches A, including which face its holes are on", "false_description": "The held panel is a different part, the mirrored panel B, or no panel is held"}},
    "orientation_ok": {"type": "noul", "instructions": "Is the held or placed side panel oriented as step 4 requires?",
      "criteria": {"true_description": "Panel is vertical, cam-lock holes face the base panel, shelf-pin hole rows are toward the front (camera side)", "false_description": "Panel is upside down, the hole face points away from the base, or the shelf-pin holes are at the back"}},
    "hardware_in_hand": {
      "type": "choice",
      "instructions": "Which hardware item is the user holding or inserting right now?",
      "criteria": {
        "cam_lock_100214": "Round metal cam lock disc with a slot",
        "cam_bolt_104322": "Metal bolt with a threaded end and a round head",
        "wood_dowel_101350": "Small wooden peg",
        "screw_110789": "Long black screw",
        "no_hardware": "No small hardware is in the user's hands",
        "other": "Some other hardware, or cannot tell"
      }
    },
    "panel_on_base": {"type": "noul", "instructions": "Is a side panel standing on the base panel's left edge with the bolts entering its holes?"},
    "step_complete": {"type": "noul", "instructions": "Compare Image 1 with the LEFT half of Image 2. Does the work area show step 4 completed?",
      "criteria": {"true_description": "Left side panel stands on the base, flush, matching the reference result", "false_description": "Panel missing, loose, on the wrong side, or the scene differs from the reference"}},
    "next_step_part_used": {"type": "noul", "instructions": "Is the user attaching a shelf (D) or the back sheet (E) to the base before the left side panel is attached?"},
    "hands_present": {"type": "noul", "instructions": "Are one or both of the user's hands visible in Image 1?"},
    "view_blocked": {"type": "noul", "instructions": "Is the main assembly area blocked from view (by the body, a large panel held close to the camera, or the frame cropping it out)?"},
    "image_poor": {"type": "noul", "instructions": "Is Image 1 too dark, overexposed, or motion-blurred to identify parts?"},
    "frame_matches_setup": {"type": "noul", "instructions": "Does Image 1 show the same work area and camera angle as the reference result (same table and roughly the same viewpoint)?"}
  }
}
```

The `choice` keys avoid positional names and include an `other` option with an explicit description, because "keys are sorted alphabetically" ([best practices](https://aistudio.google.com/docs/decisions-api)). Every question is phrased positively. The response looks like `{"answers": {"right_part": {"type":"noul","noul":0.93,"confidence":0.93}, "part_in_hand": {"type":"choice","choice":"panel_A_left_side","probabilities":{...},"confidence":0.81}, ...}, "usage": {...}}`.

## 2. Decision logic

### 2.1 Gates and thresholds

For `noul`, the docs define `confidence = max(noul, 1-noul)`, so for those questions it adds nothing beyond the probability. For `choice`, `confidence` is the probability that the top answer is correct. The table below gives **starting** thresholds, to be tuned in §7.

| Signal | Positive when | Negative when | Else |
|---|---|---|---|
| noul (e.g. `right_part`) | `noul ≥ 0.85` | `noul ≤ 0.15` | unknown |
| choice (`part_in_hand`) | `confidence ≥ 0.75` and top key not `other` | – | unknown |
| frame usable | `image_poor < 0.3` and `view_blocked < 0.3` | – | frame discarded, not counted in windows |

**Cross-checks:** we only flag `wrong_part` when `right_part` is negative **and** `part_in_hand` confidently names a different panel. If the two disagree, the result is `unknown`, which can lead to `unsure_show_part`.

### 2.2 Temporal smoothing

We keep a ring buffer of the last M usable frames per signal. An event fires when **N of M agree**:

| Event | N/M at 4 Hz | Approx. dwell |
|---|---|---|
| `wrong_part` | 4 of 5 | ~1.25 s |
| `flip_needed` (orientation negative while holding the right part) | 5 of 6 | ~1.5 s |
| `wrong_hardware` | 4 of 5 | ~1.25 s |
| `out_of_order` | 6 of 8 | ~2 s |
| `step_complete` | 8 of 10, **and** `hands_present` negative or the panel stable for ≥ 2 s | ~2.5 s |
| `unsure_show_part` | holding detected (MediaPipe) but ≥ 8 consecutive `unknown` on `part_in_hand` | ~2 s |

**Hysteresis:** once a state is entered it needs **opposite** evidence (e.g. `right_part ≤ 0.15` for 4 of 5) to leave it. Absent evidence is not enough.

### 2.3 Per-step state machine

```
WAITING_FOR_PART ──(part_in_hand∈{step parts}, 3/4)──► PART_IN_HAND
     ▲   │ wrong part 4/5 → emit wrong_part         │ orientation neg 5/6 → emit flip_needed
     │   │ next-step part used 6/8 → out_of_order   │
     │   └───────────────────────────────────────── ▼
     │                                         PLACING (panel_on_base 3/4)
     │ part put down (nothing_held 6/8)              │ step_complete 8/10 + stable
     └───────────────────────────────────────── STEP_COMPLETE ─► emit step_complete,
                                                     load step n+1 questions + composite
```

Each event is `{type, step, guide_page, part, expected, observed, evidence:{q:prob}, ts}`.

### 2.4 Cooldowns (no nagging)

- The same event type for the same step is spoken at most once every **20 s**. A second occurrence shortens the phrasing ("Still the right panel, remember").
- There is a global speech cooldown of **6 s** between unsolicited utterances.
- `step_complete` is never suppressed, because confirmations matter.
- After the user says "I know" / "το ξέρω", that event type is muted for that step.
- Positive confirmations ("right part") are spoken at most once per step.

### 2.5 Early warning versus after the fact

| Detectable **before** the mistake | Detectable only **after** |
|---|---|
| Wrong panel picked up (`part_in_hand`, `right_part`) | Panel placed on the wrong side or edge (`step_complete` neg + `panel_on_base`) |
| Mirror panel B instead of A, if hole faces are visible | Cam locks not turned or tightened (often invisible) |
| Panel held upside down or with the hole face reversed (`orientation_ok` while holding) | Dowel missing inside a joint |
| Wrong hardware in hand (`hardware_in_hand`) | Screw in the wrong hole of the correct panel |
| Out-of-order: next step's part brought to the base | Over-tightening or damage |

For the "after" cases, the voice agent says "check" rather than "wrong", e.g. "Step 4 on page 6 shows the panel on the left; please check."

## 3. Throughput, latency, cost

Assumptions: step text 250 tok, questions about 700 tok (11 questions; whether question tokens are billed is **UNVERIFIED**, but they count toward context per the docs, so we include them). Image tokens are 280/560/1120 per image for low/medium/high (task brief). Pricing is $0.10 per 1M input tokens (task brief; the docs page defers to the pricing page). At 4 Hz the load is 240 RPM and 14,400 requests per hour.

| Config | Tokens/req | TPM @4 Hz | $/hour @4 Hz | Max Hz on Free (50k TPM) |
|---|---|---|---|---|
| 3 images low (frame + ref + parts) | 1,790 | 429,600 | $2.58 | 0.47 |
| 2 images low (frame + composite) | 1,510 | 362,400 | $2.17 | 0.55 |
| **2 images medium (recommended)** | **2,070** | **496,800** | **$2.98** | 0.40 |
| 3 images medium | 2,630 | 631,200 | $3.79 | 0.32 |
| frame high + composite medium | 2,630 | 631,200 | $3.79 | 0.32 |

Rate limits for `gemini-decision-1` ([rate limits](https://aistudio.google.com/docs/rate-limits)): Free 200 RPM / 50k TPM / 10k RPD; Tier 1 1,500 / 1M / 50k; Tier 2 5,000 / 4M / unlimited. Tier 1 requires linked billing. Tier 2 requires $100 paid and 3 days. Limits are **per project**.

Conclusions:
- **The free tier cannot run 4 Hz.** It fails on RPM (240 > 200) and on TPM by about 10×. We need **Tier 1**, which covers one live user at 4 Hz medium (~50% of TPM). At 2 users we hit the TPM cap. RPD of 50k is about 3.5 h at 4 Hz per day per project, which is enough for the demo.
- **Adaptive rate:** 1 Hz while MediaPipe sees no hands (the scene is static), 4 Hz while hands are present, and an immediate request when a hand closes on an object. Expected average is about 2.5 Hz, roughly $1.9/h.
- **Latency budget:** capture and encode ~30 ms, upload ~40–80 ms, and Decisions 150–250 ms (Google's claim from the brief; the docs page gives no numbers, **UNVERIFIED**). End to end is about 300 ms per frame. At 4 Hz we need **~2 requests in flight**, so the code pipelines requests and drops stale responses by frame sequence number. The time from an action to a spoken warning is about 1.25 s smoothing + 0.3 s + Live speech onset (**UNVERIFIED**, ~0.5–1 s), so about 2–2.5 s total.
- We always send the composite, because there is no caching and a single image is cheapest. The step text is short, so it is not worth stripping.

## 4. Voice agent (Gemini Live)

### 4.1 Audio-only session: reasoning confirmed

The [capabilities page](https://aistudio.google.com/docs/live-api/capabilities) says: "Audio-only sessions are limited to 15 minutes, and audio plus video sessions are limited to 2 minutes", and video is max "1 frame per second". Both limits rule out Live video for 4 Hz perception. Vision therefore goes through Decisions, and **Live is audio-only** with `responseModalities: ["AUDIO"]`.

### 4.2 Connection: client-direct with an ephemeral token

We recommend client-direct. The backend calls `POST /v1beta/auth_tokens` (`client.authTokens.create`) with `uses: 1`, `expireTime` = now+60 min, `newSessionExpireTime` = now+1 min, and `liveConnectConstraints` that lock `model` and the config (system instruction, tools, `sessionResumption`). The browser then connects with `new GoogleGenAI({apiKey: token.name, httpOptions:{apiVersion:"v1beta"}})` ([ephemeral tokens](https://aistudio.google.com/docs/live-api/ephemeral-tokens)). Three reasons:
- Audio does not take an extra hop, which keeps barge-in snappy.
- The same token can be reused for resumption reconnects inside `expireTime`.
- The system prompt stays server-locked.

A server relay is only worth it if we need server-side audio logging, so we skip it for the hackathon.

### 4.3 Injecting perception events

Per the [capabilities page](https://aistudio.google.com/docs/live-api/capabilities):
- `sendClientContent({turns, turnComplete})` sends ordered turns. `turnComplete: false` adds context **without forcing a response**, and `turnComplete: true` "unconditionally interrupts generation".
- `sendRealtimeInput({text})` is "optimized for responsiveness at the expense of deterministic ordering".

Our policy:

| Event priority | Message | Effect |
|---|---|---|
| Ambient state (step changed, part confirmed silently) | `sendClientContent({turns:[{role:"user",parts:[{text:"[PERCEPTION] …"}]}], turnComplete:false})` | Model knows, stays quiet |
| Warning (`wrong_part`, `flip_needed`, `wrong_hardware`, `out_of_order`, `unsure_show_part`) and `step_complete` | same with `turnComplete:true`, **only if the user is not speaking** (local VAD); otherwise queue ≤ 3 s | Model speaks now |

Example injected text: `[PERCEPTION event=wrong_part step=4 page=6 expected="left side panel A" observed="right side panel B" p=0.93] Tell the user briefly, cite step 4 page 6.`

Proactive audio is "permanently enabled on Gemini 3.8 Live models" (same page). This helps the model decide not to reply to ambient updates. Whether it actually stays silent on `turnComplete:false` context is **UNVERIFIED** and is tested in §7.

### 4.4 Function declarations

Tools are declared at setup as `tools:[{functionDeclarations:[…]}]`. The client answers with `sendToolResponse({functionResponses:[{id,name,response}]})` ([tools](https://aistudio.google.com/docs/live-api/tools)). The tools page says "Live API does not handle tool responses automatically", and it does not show the name of the args field (we assume `args`, **UNVERIFIED**). Navigation tools are `NON_BLOCKING` and respond with `scheduling: "WHEN_IDLE"`. The value list is inconsistent across pages: the tools page lists `INTERRUPT`, while the capabilities page says `INTERRUPTED`. We use the SDK enum `FunctionResponseScheduling` rather than strings.

```json
[
 {"name":"get_current_step","description":"Current step: number, guide page, parts, hardware with codes and counts, instruction text, live perception state.","parameters":{"type":"object","properties":{}}},
 {"name":"next_step","description":"Advance to the next step. Only when the user says they finished or perception reported step_complete.","behavior":"NON_BLOCKING","parameters":{"type":"object","properties":{}}},
 {"name":"previous_step","description":"Go back one step.","behavior":"NON_BLOCKING","parameters":{"type":"object","properties":{}}},
 {"name":"go_to_step","description":"Jump to a step by number.","behavior":"NON_BLOCKING","parameters":{"type":"object","properties":{"step":{"type":"integer","minimum":1}},"required":["step"]}},
 {"name":"highlight_part","description":"Highlight a part or hardware item in the 3D view and return its description.","behavior":"NON_BLOCKING","parameters":{"type":"object","properties":{"part_id":{"type":"string","description":"Part label or hardware code from get_current_step"}},"required":["part_id"]}},
 {"name":"repeat_instruction","description":"Return the full text of the current step's instruction to read back.","parameters":{"type":"object","properties":{}}},
 {"name":"show_part_request","description":"Ask the perception loop for a high-resolution check of the object the user holds up to the camera. Returns the identified part or 'unsure'.","parameters":{"type":"object","properties":{"expected_part_id":{"type":"string"}}}}
]
```

`show_part_request` triggers a one-off Decisions call with the frame at `high` and `part_in_hand` only, after a 1 s countdown chime.

### 4.5 System instruction (draft)

```
You are an assembly coach for the furniture "{product}". Speak in the user's language: Greek or English only; match the last language the user spoke.
Ground truth = the assembly guide plan. Every correction must name the step number and guide page ("Step 4, page 6 …").
You receive [PERCEPTION …] messages from a camera system. Never claim to see anything that is not in a PERCEPTION message or tool result.
Speak only when: the user talks to you, a PERCEPTION message ends a turn, or a step completes. For ambient PERCEPTION context, stay silent.
If unsure (perception says unknown/unsure), never guess: ask the user to hold the part up to the camera and call show_part_request.
Keep utterances under 2 sentences. One correction at a time. No small talk while the user works.
For "which screw/which part" questions, call get_current_step and answer with the hardware code and count, then highlight_part.
```

### 4.6 Sessions longer than 15 minutes

The [session management page](https://aistudio.google.com/docs/live-api/session-management) gives three facts: connections last "around 10 minutes", resumption handles are "valid for 2 hr", and `contextWindowCompression: {slidingWindow:{}}` extends sessions to "an unlimited amount of time". Our plan:
- Enable both `sessionResumption:{}` and compression.
- Store the latest `newHandle` from every `SessionResumptionUpdate` where `resumable` is true.
- On `goAway` (`timeLeft`) or a socket close, reconnect with `sessionResumption:{handle}` using the same token.
- After any reconnect, re-inject a `[STATE] step=n …` context turn (`turnComplete:false`) as a safety net.

### 4.7 Barge-in and language

When the user interrupts, the server sends `serverContent.interrupted = true` and pending calls are cancelled. The client must flush its audio playback queue immediately. We set `automaticActivityDetection` with `silence_duration_ms: 600`, and `start_of_speech_sensitivity: START_SENSITIVITY_LOW` because a workshop is noisy (drill and hammer sounds). Native-audio models "don't support explicitly setting the language code", so we restrict the language in the system instruction ([capabilities](https://aistudio.google.com/docs/live-api/capabilities)). Greek is listed among the supported languages per the brief. The quality of Greek speech is **UNVERIFIED** and is tested in §7.

## 5. Alternatives

### 5.1 ElevenLabs Agents

- The docs live at `elevenlabs.io/docs/eleven-agents/…`, and the client-tools page calls the product "ElevenLabs Conversational Agent" ([client tools](https://elevenlabs.io/docs/agents-platform/customization/tools/client-tools)). The exact branding is **UNVERIFIED**.
- The web SDK is `@elevenlabs/react` (it re-exports `@elevenlabs/client`). It provides `useConversation`, `startSession` (with `signedUrl` for WebSocket or `conversationToken` for WebRTC, minted server-side), `clientTools`, **`sendContextualUpdate` (adds context without triggering a response)**, `sendUserMessage`, and `sendUserActivity` ([React SDK](https://elevenlabs.io/docs/eleven-agents/libraries/react)). `sendContextualUpdate` maps cleanly onto our ambient and warning split.
- Price: $0.08/min, with burst pricing at $0.16/min above the concurrency limit. LLM cost for "Speech Engine" is not stated on the page ([pricing](https://elevenlabs.io/pricing/api)).
- The ~400 ms voice-to-voice figure comes from a third party only ([CallSphere](https://callsphere.ai/blog/vw2c-elevenlabs-conversational-vs-openai-realtime-cost-2026)) and is **UNVERIFIED**.
- Greek support is **UNVERIFIED**: the language page returned 403. The Multilingual and Flash voice models historically include Greek.

**Choose ElevenLabs** if Gemini Live speaks over the user after ambient context, if Greek voice quality is poor, or if tool-calling over audio is unreliable. The trade-off is about $4.80/h against roughly $0.3–1.4/h for Live audio, plus a second vendor.

### 5.2 Cheap deterministic fallback

Event → a templated phrase per language (`"Βήμα {n}, σελίδα {p}: γύρισε το πάνελ ανάποδα."`) → `gemini-3.8-flash-lite-tts`, with phrases pre-rendered per step at guide load. If that is unavailable, use the browser's `speechSynthesis` (whether a Greek voice exists depends on the device, **UNVERIFIED**). Questions are answered with push-to-talk: `gemini-3.5-transcribe-live` → `gemini-4-hydrogen` (low thinking) with the plan → TTS. This path has no hallucination risk for corrections and also serves as the demo-day safety net.

## 6. Failure modes

| Failure | Detection | Mitigation |
|---|---|---|
| Low light / blur | `image_poor`, plus on-device luminance and Laplacian variance check | Drop the frame; after 5 s of poor frames, say once "I can't see well, more light please" |
| Hands occluding | `view_blocked`, MediaPipe hand area > 40% ROI | Frame excluded from windows; never fire `step_complete` while blocked |
| Near-identical panels (A vs B mirror, shelf variants) | Close `part_in_hand` probabilities (top-2 margin < 0.2) | Composite shows distinguishing features with arrows; emit `unsure_show_part` → `show_part_request` at `high` |
| Camera angle drift | `frame_matches_setup ≤ 0.15` for 10 s, or ORB homography vs calibration frame | Pause warnings; UI overlay "re-align phone" with ghost outline |
| Decision request timeout (> 800 ms) or 5xx | Client timer | Drop that frame (it does not count in windows); after 3 s with no successes the UI shows a grey "vision paused" badge and the voice suppresses all warnings; exponential backoff starting at 250 ms |
| 429 rate limit | HTTP 429 | Lower the rate to 1 Hz for 30 s |
| Safety 400 (`error.code` ∈ `safety`, `image_safety`, …) ([docs](https://aistudio.google.com/docs/decisions-api)) | HTTP 400 with `error` | Treat as an unusable frame; if 3 in a row, lower the frame to `low` and tighten the ROI crop (e.g. a person in frame); never retry the same frame |
| Live socket drops | `onclose` / `goAway` | Resume with handle (§4.6); fallback TTS path speaks queued events meanwhile |

## 7. Evaluation plan for tonight

1. **Record** 5 clips of 20–40 s with the phone on the stand at the demo angle and on the real product (or a cardboard mock):
   - (a) correct step 1 → complete
   - (b) wrong panel picked up
   - (c) right panel upside down, then flipped
   - (d) wrong hardware
   - (e) bad light plus hand occlusion
   Record a Greek and an English voice question over clip (a).
2. **Label** at 4 Hz with a tiny script (frame index → ground truth for each question). That is about 600 frames, roughly 30 minutes of work.
3. **Replay offline:** `eval.py` extracts frames at 4 Hz and sends the exact production request. It records each answer, `usage.total_input_tokens` and latency, and runs each config: {low, medium} × {composite, 3 images}. Cost is about 4 × 600 × 2k tokens ≈ $0.50.
4. **Metrics:**
   - Per question: accuracy, plus precision and recall at each threshold, and calibration (bucketed probability vs. empirical rate).
   - Latency p50/p95.
   - Per event after smoothing: false alarms per minute (target **0** on clip a), detection delay on b/c/d (target < 2 s), and the `step_complete` lag.
5. **Tune:** pick each noul threshold as the lowest value with precision ≥ 0.95 on negatives that trigger speech. Then choose the smallest N/M that gives zero false events across all clips. Questions whose AUC is below 0.8 get reworded criteria; re-run Hydrogen with the failure examples.
6. **Voice smoke test:** inject 20 ambient `turnComplete:false` messages and count how often Live speaks unprompted (target 0). Inject 10 warnings and check that each cites the step and page. Test barge-in by talking over a warning.
