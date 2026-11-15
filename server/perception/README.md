# Perception socket: protocol and how to run it

`WS /ws/perception/{job_id}`: camera frames in, guidance events out (PLAN §4.5, §6). This file is enough to write a browser client. The code map is in `server/perception/__init__.py`.

**One stack.** What is sent to Decision 1, how many requests run at once and how answers become the plan's events come from `pipeline/perception/` and `pipeline/check/decisions.py`, where they were measured against the real API ([what comes from where](#what-comes-from-where)). This package is the server around them: the socket, the sessions, the current action, confirmations, sentences, the headset rules, failure handling and a simulated mode.

**This server has not yet called the real API** (the machine it was written on has no key). The pipeline side has, from another machine. What that leaves open is in [Unproven](#unproven-until-this-server-runs-with-a-key).

## Read this first

1. **Use a job built through the server, and built after the refs stage began to write question files** (code pulled Sun 15 Nov, about 02:00). Two reasons:
   - A folder without `guide.pdf` (for example `jobs/local-fresh`) is not a job for the server: the socket answers `unknown_job`.
   - Only those jobs have `refs/step-NN-questions.json` (the benchmarked questions) and the sheet with the added parts in orange. An older job, such as `1115-0022-2d4ab6`, still works: its questions are made in memory from the same template on its old sheet, the server log says so, and `status.questions.origin` is `"fallback"` instead of `"file"`. That combination was never benchmarked. For a current job run `make smoke`; it prints the new id.
2. **You can build and test a client with no key.** Start the server with scripted answers, then tell it what the person "holds":
   ```sh
   PERCEPTION_SIMULATE=true make dev
   ```
   ```js
   ws.send(JSON.stringify({type: "sim", holding: "108560"}));               // the short screw
   ws.send(JSON.stringify({type: "sim", holding: "108363"}));               // the long screw
   ws.send(JSON.stringify({type: "sim", holding: null}));                   // empty hands
   ws.send(JSON.stringify({type: "sim", step_complete: true, hands: false}));
   ws.send(JSON.stringify({type: "sim", reset: true}));
   ```
   Frames must still be sent: an answer is produced per frame. Every server message then carries `sim: true`. All script fields are in the [`sim` row](#client-to-server-json-text) below.
3. **The label template uses part ids the manifest does not have.** `eval/phase2/labels.template.json` says `end_frame`, `side_slat`, `handle_slat`; the manifest has `bottom_panel`, `side_panel`, `end_slat`, `end_slat_handle`. Real labels must use the manifest ids, or `p2.py score` counts every `part_in_hand` answer as a mismatch.

## Changed on Sun 15 Nov, after the measurements

Everything is additive on the wire; a client written against the earlier version keeps working. What a client notices:

| | Before | Now |
|---|---|---|
| `hello.limits.max_unacked` | 2 | 5 (4 requests at once, and the newest frame waits). **Follow `hello.limits`.** |
| `hello.limits.ack_timeout_ms` | 2000 | 9000. A request takes 0.6 to 1.1 s at the median and up to 2.6 s at p95, so most acks come after about a second. |
| `ack.dropped: "busy"` | sent at once when 2 calls were in flight | sent to the frame that was waiting when a newer one arrived |
| Same warning while the thing is still held | repeated every 20 s | said once; again only after it was put down, and 20 s later |
| `step_complete` | hands out of view or 2 s stable, after something was picked up | hands out of the picture for 2 s (or the hand summary, see below); no pick-up needed |
| `flip_needed` | about the held part | about how the assembly stands ("Turn the assembly upside down. Step 3, page 4.") |
| `wrong_hardware` on a step that uses no hardware | never | sent, with `expected: null` ("Step 1 does not need it.") |
| `realign_camera` | after 10 s of "camera moved" | off by default |
| `hint` in the frame header | `low`, `medium`, `high` for the frame | `high` is honoured, `low` means `medium`; the value applies to both images of the request |
| New fields | | `event.votes`, `status.questions`, `status.frames.in_flight`, `hello.limits.in_flight`; `ack.dropped` values `no_questions`, `billing`; `error` code `no_questions` |

**This departs from PLAN decision D7** (at most 2 un-acked frames on the client, at most 2 Decision 1 calls in flight on the server, a 2 s client watchdog). With 2 in flight the measured delay from a change to `step_complete` was 3.7 to 5.5 s; with 4 it was 2.6 to 3.8 s. The server now runs 4, the client may have 5 un-acked, and the watchdog is 9 s. PLAN §6.5's 800 ms decision timeout is replaced by 4 s for the same reason: 800 ms is below the measured median. Both need an entry in the decisions log.

## Wire protocol as built

**[plan]** = PLAN §4.5. **[added]** = an addition. Nothing in the plan's protocol was changed.

### Connect

`wss://HOST/ws/perception/{job_id}?role=display|camera&key=DEMO_KEY`

| Query | Values | |
|---|---|---|
| `role` | `display` (default), `camera` | [plan] Both roles may send frames, controls and hand summaries. One headset socket can do everything. |
| `key` | demo key | [plan] |
| `source` | `headset`, `fixed` | [added] Default source of this socket's frames: `headset` for `display`, `fixed` for `camera`. A phone that is also the display should pass `source=fixed`. |
| `device` | label, max 40 chars | [added] |
| `answers` | `1` | [added] Each ack carries the frame's answers (used by `replay.py`). |

Refusals:
- Wrong or missing key: the handshake is refused (HTTP 403; a browser sees a failed connection, close 1006). ASGI-level clients see close code 4401.
- Otherwise the socket is accepted, one `{type:"error", code, message}` is sent, then it closes: 4400 `unknown_role`, 4404 `unknown_job`, 4409 `not_built` (no manifest).

### Order of calls

1. Open the socket. The server sends `hello`, then `status`. When vision is unavailable (no key and simulation off) it also sends an `event` `vision_paused` with `reason:"unavailable"`.
2. Send `{type:"start", step, action}`. The server broadcasts `status`.
3. Send frames (binary) and, from a headset, `hands` at about 10 per second on the same socket. Keep at most `hello.limits.max_unacked` frames un-acked and send at most `hello.limits.max_hz` a second; free a slot after `hello.limits.ack_timeout_ms` without an ack.
4. Send `{type:"action", index}` when the current action changes, and `{type:"step", n, action}` when the step changes. After `step_complete` the server uses no more frames until a `step` arrives.
5. `{type:"stop"}` ends guidance; the socket stays open.

`seq` should increase per socket; it may restart after `start` or `step`.

### Client to server, JSON text

| Message | Fields | |
|---|---|---|
| `start` | `step:int`, `action?:int` | [plan] + `action` [added]. Also resets cooldowns. |
| `step` | `n:int` (`step` also accepted), `action?:int` | [plan] + `action` [added] |
| `show_part` | `expected?:string` (part id or hardware code) | [plan] The next frame is sent at `high` with only the "what is held" questions (and, since Sun 15 Nov, the yes/no `right_hardware`); the answer comes as event `show_part_result`. Waits 8 s for a frame. **Send that one frame at the camera's full size** (1280 x 960, not the 768 px stream size): see "Real headset frames". |
| `stop` | | [plan] |
| `hands` | `seq:int`, `hand_at_target:bool`, `still:bool`, `raised:bool`, `ts:number`, `present?:bool` | [plan] + `present` [added]: `false` when no hand is tracked |
| `action` | `index:int` (0-based into the step's manifest `actions`), `step?:int` | [added] |
| `mute` | `event?:string` | [added] "I know". Without `event`: the last warning given. Lasts for the step. |
| `unmute` | `event?:string` | [added] |
| `sim` | `holding:string\|null`, `step_complete`, `orientation_ok`, `next_step_part`, `view_blocked`, `image_poor`, `setup_ok`, `unsure` (bools), `hands:bool\|null`, `confidence:number`, `latency_ms:number`, `fail:null\|"timeout"\|"server_error"\|"network"\|"rate_limit"\|"safety"\|"auth"\|"billing"\|"bad_request"`, `reset:true` | [added] Simulated mode only. Example: `{type:"sim", holding:"108363"}`. `on_base` is still accepted and has no effect. |

### Client to server, binary frame

`[uint32 header_len][JSON header, UTF-8][JPEG bytes]` [plan]

- **`header_len` is big-endian** (network order), which is what `DataView.setUint32(0, n)` writes. A little-endian length is tolerated when the big-endian reading is impossible, but big-endian is the contract.
- The header is at most 4096 bytes; the JPEG at most 1,500,000 bytes and must start with `FF D8 FF`.

| Header field | Type | |
|---|---|---|
| `seq` | int ≥ 0, required | [plan] |
| `step` | int | [plan] If present and not the current step, the frame is dropped as `stale_step`. |
| `ts` | number, capture time in ms | [plan] Not used by the server. |
| `hint` | `"low"\|"medium"\|"high"` | [plan] `high` sends the request at `high`; `low` and `medium` both send it at `medium`, the benchmarked setting (`low` saved under 100 ms when measured). The frame and the sheet go at the same resolution. |
| `source` | `"headset"\|"fixed"` | [added] Overrides the socket's default. |
| `clip`, `frame`, `t` | string, int, seconds | [added] Replay only; copied into the logs. |

```js
function packFrame(header, jpegBytes) {                    // jpegBytes: Uint8Array
  const head = new TextEncoder().encode(JSON.stringify(header));
  const buf = new Uint8Array(4 + head.length + jpegBytes.length);
  new DataView(buf.buffer).setUint32(0, head.length);      // big-endian, the DataView default
  buf.set(head, 4);
  buf.set(jpegBytes, 4 + head.length);
  return buf;
}
const ws = new WebSocket(`wss://${host}/ws/perception/${jobId}?role=display&key=${key}`);
ws.onmessage = (e) => { const msg = JSON.parse(e.data); /* hello | status | ack | event | error | sim */ };
// after {type:"hello"}: keep msg.limits, then ws.send(JSON.stringify({type:"start", step: 2, action: 0}));
canvas.toBlob(async (blob) => {
  const jpeg = new Uint8Array(await blob.arrayBuffer());
  ws.send(packFrame({seq: ++seq, step: 2, ts: Date.now(), hint: "medium", source: "headset"}, jpeg));
}, "image/jpeg", 0.7);
```

This snippet was run as written (under Node with the `ws` package) against the server on uvicorn, before and after the change above: frames were acked and the event arrived.

### Server to client, JSON text

In simulated mode every message carries `sim: true`. The field is absent otherwise.

**`ack`**, one per frame, to its sender only: `{type, seq, latency_ms}` [plan] + `used:bool`, `dropped:string|null`, `decision_ms:int|null`, `answers?:object` [added].
- A frame the server will not look at is acked at once. A frame it looks at is acked when its request ends, usually after about a second. Its events, if any, come after the ack; events always come in the order the frames were sent.
- `latency_ms` is server time from receiving the frame to the ack; `decision_ms` is the Decision 1 round trip alone.
- For a frame whose header cannot be read, `seq` is `null`.

| `dropped` | Meaning |
|---|---|
| `busy` | all requests were in flight and a newer frame took this one's place in the waiting slot |
| `stale` | the step changed while the frame waited or was looked at |
| `stale_step`, `not_started`, `step_done` | header step is not current; no `start` yet; step already complete |
| `rate`, `gated`, `rate_limit_wait`, `backoff` | faster than `max_hz`; hand summary says not worth a call; inside the 1 Hz window after a 429; backing off after failures |
| `other_source` | another camera fed the session within the last 2 s |
| `bad_frame`, `dark` | not a decodable JPEG; mean brightness under 22 of 255, no call spent |
| `no_questions` | this step has no questions and none can be made (see `error` `no_questions`) |
| `timeout`, `server_error`, `network`, `rate_limit`, `safety`, `auth`, `billing`, `bad_request`, `error` | the call for this frame failed that way |
| `vision_unavailable` | no key and no simulation, or the API refused the key or the project's credit (retried every 30 s) |

**`event`**, to every device: `{type, event, step, page, expected, observed, evidence:{question: prob}, ts}` [plan], plus [added]:

| Added field | Type | |
|---|---|---|
| `id` | int | running number |
| `action` | int\|null | current action index |
| `expected_id`, `observed_id` | string\|null | part id or hardware code; `expected` / `observed` are display strings such as `"long screw 108363"` |
| `p` | number\|null | main probability |
| `say` | `{en, el}` | ready sentence for speech synthesis |
| `perception` | string | line to inject into the voice model |
| `speak`, `turn_complete` | bool | false for `vision_paused` and `vision_resumed` (badge only) |
| `repeat` | int | times this event was already given on this step |
| `seq` | int\|null | the sender's frame that triggered it |
| `latency_ms` | int\|null | frame receipt to event, server side |
| `votes` | string | how many frames agreed, e.g. `"4 of last 5 frames"` |
| `reason`, `message` | string | `reason:"unavailable"` with `message` on `vision_paused`; `reason:"hand_summary"` on a `step_complete` that the hand summary supported |
| `match` | bool\|null | on `show_part_result` |
| `reason` on `show_part_result` | string | [added Sun 15 Nov] only when `observed_id` is null: `image_poor`, `hand_may_be_empty` (the model gives "nothing in the hand" 0.1 or more), `not_confirmed` (the choice and the yes/no `right_hardware` disagree), `not_recognised`. `evidence` also carries `image_poor`, `no_hardware` and `right_hardware`. |

- Events [plan]: `wrong_part`, `flip_needed`, `wrong_hardware`, `out_of_order`, `step_complete`, `unsure_show_part`, `vision_paused`.
- Events [added]: `right_part`, `right_hardware`, `more_light`, `realign_camera` (off by default), `vision_resumed`, `show_part_result`.
- An event is sent only when it may be spoken: the cooldowns and mutes are applied on the server. For continuous UI (ring colour, badge) use `status`.

Example, as produced:
```json
{"type":"event","event":"wrong_hardware","step":2,"page":3,"expected":"long screw 108363","observed":"short screw 108560",
 "evidence":{"hardware_in_hand.108560":0.93,"right_hardware":0.07},"ts":1794702198777,"id":1,"action":0,
 "expected_id":"108363","observed_id":"108560","p":0.93,
 "say":{"en":"That's the short screw, 108560. Step 2 needs the long screw, 108363. Page 3.",
        "el":"Κρατάς κοντή βίδα, 108560. Το βήμα 2 θέλει μακριά βίδα, 108363. Σελίδα 3."},
 "perception":"[PERCEPTION event=wrong_hardware step=2 page=3 expected=\"long screw 108363\" observed=\"short screw 108560\" p=0.93] Tell the user briefly, cite step 2 page 3.",
 "speak":true,"turn_complete":true,"repeat":0,"seq":4,"latency_ms":43,"votes":"4 of last 4 frames"}
```
The confirmation reads "Good. That's the long screw, 108363." / «Ωραία. Κρατάς μακριά βίδα, 108363.»

**`hello`** [added], once on connect: `{type, protocol:1, job_id, session, role, device, source, vision:"live"|"simulated"|"unavailable", reason:string|null, title, steps:int[], step:int|null, action:int|null, devices:{role:count}, limits:{max_unacked:5, in_flight:4, max_hz:4, ack_timeout_ms:9000, max_frame_bytes, long_edge_px:768, jpeg_quality:0.7}, frame:{layout, byte_order:"big-endian"}}`. The numbers in `limits` are today's defaults; a client reads them from the message.

**`status`** [added], at most 2.5 per second when something changed, at least every 2 s, and at once after a control message:

| Field | Values |
|---|---|
| `step`, `action`, `page` | current, or null |
| `state` | `IDLE`, `WAITING_FOR_PART`, `PART_IN_HAND`, `PLACING`, `STEP_COMPLETE` |
| `expected` | null, or `{kind:"part"\|"hardware"\|null, id, name, action_type:"place"\|"fasten"\|"turn"\|null, actions:int, text:{en,el}\|null}` |
| `holding` | `{kind:"part"\|"hardware"\|"none"\|"unknown", id, name, confidence}` |
| `verdict` | `right`, `wrong`, `none`, `unknown` |
| `hands_present` | bool\|null (from the frames) |
| `view` | `ok`, `blocked`, `poor`, `dark`, `moved`, `none` |
| `vision` | `live`, `simulated`, `paused`, `unavailable`; `reason` says why when unavailable |
| `source` | `headset`, `fixed`, null |
| `rate_hz` | frames per second the server wants now (0 while the hands move) |
| `hands` | null, or `{present, at_target, still, raised, hz}` from fresh summaries |
| `muted`, `show_part`, `devices` | string[], bool, `{role:count}` |
| `voice_state` | `"[STATE step=N]"` for the voice model, or null |
| `questions` | null until the step's questions are read, then `{origin:"file"\|"fallback", written_by:"gemini-4-hydrogen"\|"template"}` |
| `frames` | `{used, dropped, decision_ms_p50, in_flight}` |

**`error`** [added]: `{type, code, message}`. Codes: `bad_json`, `unknown_type`, `unknown_step`, `unknown_action`, `not_started`, `nothing_to_mute`, `not_simulated`, `bad_script`, `bad_frame`, `bad_tuning`, `no_questions`, `unknown_job`, `unknown_role`, `not_built`.

**`sim`** [added]: `{type, script}`, to every device after a debug message.

### Rules that differ between `headset` and `fixed` frames

| Rule | `fixed` (phone on a stand) | `headset` |
|---|---|---|
| `view_blocked` ≥ 0.3 | whole frame discarded | only the questions about the assembly are left out; what is in the hand still counts |
| `flip_needed` | 5 of 6 | 6 of 7 |
| `out_of_order` | 6 of 8 | 7 of 8 |
| `step_complete` window | 8 of 10 | 10 of 12 |
| Hand gate on frames | none (the phone gates itself) | on, see below |
| `wrong_part`, `wrong_hardware`, confirmations | 4 of 5, 4 of 5, 3 of 4 | same |
| `step_complete`, hands | out of the picture for 2 s | same; in practice a headset needs the hand summary, because hands stay in its picture |
| "Camera moved" pause (`frame_matches_setup`) | off | off |

The `fixed` column is the pipeline's measured logic unchanged. The `headset` differences are this server's and have not been measured.

`step_complete` and the hand summary: when a hand was at the target in the current action and has been away from it for 0.5 s (`hand_at_target` true, then false), `step_complete` no longer waits for the hands to leave the picture. It still needs its window of frames. Such an event carries `reason:"hand_summary"`.

"Camera moved" is off for both because the question asks whether the frame shows the assembly "from roughly the same side" as the reference render, and a real camera is rarely on that side (the benchmark frames were not). With the rule on, a correct setup could pause every warning. Turn it on with `PERCEPTION_TUNING='{"fixed": {"check_setup": true}}'` once it has been looked at with a real camera.

Hand gate (headset frames, fresh summaries only):
- `raised`: full rate.
- Present and `still`: 4 Hz.
- Moving: nothing.
- `present:false` in the first 3 s after hands were seen: full rate. After that: 1 Hz.
- No summary for 1 s: no gating.

The simplest client sends frames steadily at 4 Hz and lets the server ack the gated ones at once. Turn the gate off with `PERCEPTION_TUNING='{"headset": {"hand_gate": false}}'`.

### Behaviour worth knowing

- **Hands first.** What is held (`part_in_hand`, `hardware_in_hand`, `right_part`) is read only when `hands_present` is 0.5 or more. Measured: with nobody in the picture the model still named a part in 44 of 64 frames.
- **Expectation.** With an `action` index, `fasten` expects that action's code and `place` that action's part. Without one, the step's parts and hardware.
- **`wrong_hardware`.** 4 of the last 5 frames confidently (0.75) name a code that is not right now. The added yes/no `right_hardware` overrules a single frame when it clearly says "yes". On a step that uses no hardware, any code is reported, with `expected: null`.
- **`wrong_part`.** The choice confidently names a part outside the step and `right_part` is a clear "no" (PLAN cross-check).
- **Said once.** A warning is given once and not again while the same thing stays in the hand. After it was put down (the window emptied) and 20 s passed, it can be given again (`repeat: 1`). Any two utterances are 6 s apart; `step_complete` is never held back.
- **Confirmations.** Once per step per item; given again after a correction for that kind. They obey the 20 s same-event rule, so a second, different part picked within 20 s is not confirmed.
- **`step_complete`.** A frame votes for it when `step_complete` is 0.45 or more and `orientation_ok` is 0.4 or more (both measured; other yes/no answers need 0.85). It needs 8 of the last 10 frames and hands out of the picture for 2 s. When the display reports actions, frames vote only during the last action. Measured: it stays weak on some steps (step 3 of the KNAGGLIG box scored 0.28 when finished), so **the display must always offer its own way to advance** (a pinch, "next").
- **`step_complete` when the step's hardware does not show on the sheet** [added Sun 15 Nov, 15:00]. The sheet's left half is taken from the front; hardware that goes into a side (the cam locks of MALM drawer step 3, the runner screws of step 7) covers 0 pixels of it, so the question "are the orange parts attached?" was asked about a picture with no orange in it. Step 3 never fired (0.44 when done), and step 7 fired on a frame that merely looked like the sheet (0.69 on its "before" frame). Now, for any job:
  - `pipeline.perception.questions.added_px` counts the pixels by which `refs/step-NN-added.png` differs from `step-NN-ref.png`; under 64 the step is "hidden".
  - A hidden step with a close-up (`refs/step-NN-detail.png`, written by `python -m pipeline.perception.details <job>`, also part of the refs stage) sends it as **Image 3** (two more input blocks, about 560 tokens) and asks whether those places look like its AFTER or its BEFORE half. Without a close-up the question asks for the hardware itself in the live frame and "cannot tell" is a no.
  - Every `step_complete` question also says that steps are done in order (a frame further along than the sheet means yes).
  - A question file written before this (`step_complete_v` missing) is brought up to date in memory when it is loaded; the job folder is not written. The other questions are untouched.
  - Measured on the same 55 pairs as before (MALM drawer renders, real calls, threshold 0.45; identical requests return identical scores, so one run each):

    | | correct of 55 | false "done" | step 3 done / frame before | step 7 done / "before" frame |
    |---|---|---|---|---|
    | before | 46 | 1 | 0.44 / 0.34 | 0.55 / 0.69 |
    | after, with close-ups | 50 | 0 | 0.46 / 0.42 | 0.60 / 0.34 |
    | after, no close-ups (a job where `details` was not run) | 45 | 0 | 0.40 / 0.34 | 0.31 / 0.18 |

    Step 3 clears the line by 0.01 with the frame before it 0.03 under: on real frames expect it to miss often. Asked alone, as `/locate` asks it, the same 55 pairs give 49 correct and 0 false "done" (step 3 done 0.58, step 7 done 0.47 with the frame before it at 0.44, also 0.01 from the line). A job needs its close-ups to gain anything: **run `python -m pipeline.perception.details jobs/<id>` once on jobs built before this** (about 50 s, Blender, no model call), then reconnect. Renders only; hands, real light and a real camera are not measured.
- **`flip_needed`** is about how the assembly stands, from `orientation_ok` ≤ 0.15. Measured to separate poorly; it was not retuned.
- **`unsure_show_part`.** 8 frames in a row with hands in view and a part that is not recognised, including a confident `other` (a tool).
- **Slow answers.** A request gets 4 s; without an answer by then the frame is acked `timeout` and does not count. The pipeline's client tries a dead connection a second time first, so that ack can take 8 s. After 3 s of failures without a success: `vision_paused`.
- **Logs.** `jobs/<job>/perception/<session>/{frames,events,hands}.jsonl`. The job's own `events.jsonl` (build feed) is not touched.

## What comes from where

| | Comes from | This package |
|---|---|---|
| Questions per step | `refs/step-NN-questions.json`, written by the refs stage (`pipeline/perception/questions.py`) | adds one yes/no, `right_hardware`, about the hardware needed right now. Nothing else is added; the plan's `panel_on_base` is not asked. |
| Request for a frame | `pipeline.perception.request.build`: header, frame, sheet note, sheet as JPEG, both at `medium` | passes it on unchanged (`questions.build_ask`) |
| Sending it | `pipeline.check.decisions.ask`, one kept-open connection per worker thread | reads the kind of a failure from its error text (`decider.classify`) |
| Requests at once | `pipeline.perception.stream.Stream`: K workers, only the newest frame waits, answers released in frame order | K = 4; one Stream per session, kept across steps so its connections stay open |
| `wrong_part`, `wrong_hardware`, `flip_needed`, `out_of_order`, `step_complete`, `unsure_show_part` | `pipeline.perception.logic.Watcher`, fed the API's answers unchanged, with its own thresholds, windows, hands gating and cooldowns | `logic.Coach`: the current action, the headset rules, the hand summary, mute, `right_hardware`; and `right_part`, `right_hardware`, `more_light` |
| Numbers | `pipeline/perception/logic.py` `Config` | `server/perception/config.py`; `PERCEPTION_TUNING` overrides both |

For a fixed camera, with no action reported and confirmations off, the server's events are exactly the Watcher's. `tests/test_perception.py` (the tests named `test_no_drift_…`) holds it to that and fails when the pipeline side changes in a way the server depends on.

Two replay tools, on purpose: `python -m pipeline.perception.replay <job> labels.json runs/x [--cached]` talks to the API directly, one frame after the other, and can re-run stored answers to tune thresholds. `eval/phase2/replay.py` sends the frames through this socket and measures the whole path.

## Unproven until this server runs with a key

Measured by the data owner on the pipeline side, with real requests on renders (no hands, one guide): latency (p50 0.64 to 1.1 s, p95 1.4 to 2.6 s), the delay to `step_complete` at 2, 4 and 8 requests at once, the `step_complete` and `orientation_ok` values, no 429 in about 400 requests. Details: `docs/PROGRESS.md`, log lines Sun 00:45 to 01:55.

Still open:
- **This server's path end to end with the real API.** A test sends frames through the socket and the pipeline's client with a fake connection, and checks that each request is the pipeline's own plus the one added question. No real call was made from here.
- Whether Decision 1 tells the long 108363 from the short 108560 in a real frame, in a hand, from a head or a stand. No measurement has hands in it.
- The added question `right_hardware`: unmeasured. If it misbehaves: `PERCEPTION_TUNING='{"ask_right_hardware": false}'`.
- The headset rules, the hand gate and the hand-summary `step_complete`: no headset frame or hand summary has been seen (gate G2).
- Template wording on the orange sheet. The measurements used wording written by `gemini-4-hydrogen` (`AGJ_QUESTIONS=model`); the refs stage's default is `template`.
- The texts of 429, 5xx, 402 and invalid-key replies. The kinds are read from the HTTP status; a 402 was seen once (credit used up) and is handled as "unavailable, try again in 30 s".
- Binary WebSocket frames through the tunnel or boxd; only local uvicorn was tried.
- The Greek sentences were not reviewed by a native speaker.

First commands on a machine with `GEMINI_API_KEY` in `.env`:
```sh
# 1. a job with the benchmarked sheet and model-written questions (two terminals; the second prints the job id)
AGJ_QUESTIONS=model make dev
make smoke
# 2. one real request as the server sends it, 5 times: every answer, its verdict, warm latency
.venv/bin/python eval/phase2/try_decision.py jobs/<job_id> 2 <photo-of-a-screw-in-hand.jpg> --action 0 --repeat 5
# 3. frames through the socket (needs frames from `p2.py frames <clip.mp4>`), then the score
.venv/bin/python eval/phase2/replay.py --job <job_id> --run runs/x --step 2 --action 0 eval/phase2/clips/d_wrong_hardware
.venv/bin/python eval/phase2/p2.py score eval/phase2/labels.json runs/x/frames.jsonl runs/x/events.jsonl
```
- `try_decision.py --dry-run` prints the request without sending it and needs no key.
- `replay.py` defaults to `--source fixed`; pass `--source headset` for headset clips. `--labels labels.json` gives each clip its step instead of `--step`.
- Without `--base` it runs the app in-process; with `--base http://localhost:8000` it uses the running server.
- It refuses to write a run when the server is simulated, unless `--allow-simulated` is given.

## One-picture endpoints: identify, locate and parts [added Sun 15 Nov]

Three plain REST calls: which product is this, how far along is it (both before guidance starts), and where the next parts lie in the pile. All need the demo key. Body: `{"image": "<base64 JPEG, no data: prefix>", "lang": "en"|"el"}` (`lang` optional, default `en`), at most 2 MB decoded. A missing, non-base64, non-JPEG or unreadable image is 422; a larger one 413.

**`POST /api/identify`** (`server/identify.py`, `server/routes/identify.py`): a flat-pack box, a label, a finished piece or loose parts.
```json
{"seen": {"kind": "box|label|furniture|parts|unknown", "name": "MALM", "type": "desk", "article": "002.141.57", "text": "..."},
 "match": {"job_id": "malm-drawer", "product": "MALM desk drawer", "ready": true},
 "query": "002.141.57", "confidence": 0.93, "message": "This looks like the MALM desk drawer. Its guide is ready.",
 "matched_by": "article|doc|name|picture|none", "latency_ms": 3200, "model": "gemini-4-hydrogen"}
```
- One `gemini-4-hydrogen` call (thinking `low`, JSON schema): the camera picture (long edge 1280), then a 320 px picture of each guide built here, newest first, at most 6 (`product.jpg` from the catalog, else `renders/cover.png`, else the last `renders/step_NN.png`). The model reads the text and may point at one of the pictures; the matching is done in the server: article number, then AA- document number, then name (and type, when several guides share a name), then the picture.
- Candidates: `catalog.items()` (built and known guides) plus job folders with a manifest and a walkthrough but no `status.json` (put on a host by hand).
- `match` is `null` when no guide built here matches. `query` is what to pass to `POST /api/catalog/open`: the article number read, else "NAME type", else the document number, else the matched guide's own article or id; `null` when nothing was read or matched.
- **No invented numbers.** The prompt never shows the known guides' numbers, and an article or document number is kept only when its digits are in the `text` the model read.
- Measured (real calls, Sun 15 Nov, from a laptop): MALM guide page 1 → `name: "MALM"`, matched `malm-drawer` by name; the KNAGGLIG cover render (no text) → matched `knagglig`. 3.0 to 4.0 s each.
- A model failure is 502.

**`POST /api/perception/{job_id}/locate`** (`server/perception/locate.py`, route in `routes.py`): which step the real assembly is at.
```json
{"next_step": 6, "action": 0, "done_through": 5, "confidence": 0.49,
 "steps": [{"n": 1, "complete": false, "p": 0.168}, {"n": 2, "complete": true, "p": 0.748}, "..."],
 "message": "Steps 1–5 look done. Next: step 6.", "latency_ms": 1438}
```
- One Decision 1 request per step, 6 at once: the benchmarked request (`questions.build_ask`) with only its `step_complete` question. `complete` is `p ≥ 0.45` (the Watcher's `done_yes`).
- `done_through` is the most likely "steps 1..n done, the rest not" over all n (likelihood of the answers, with 0.45 as the midpoint; ties go to the earlier step). **Not** a strict "1..n all complete" run: that was tried first and failed on real calls, because the parts an early step adds get hidden by later ones (step 1 of the MALM drawer scored 0.17 on the finished drawer). So a step can say `complete: false` and still be inside `done_through`. An isolated high answer after low ones is read as noise. `next_step` is `done_through + 1`, or the last step when all are done. `action` is always 0. `confidence` is the chosen split's share of all splits.
- A failed request for one step reads as `p: 0.5` with `error: "<kind>"`. When every request fails: 503 (key or credit: `auth`, `billing`), 429 (`rate_limit`), 504 (`timeout`), else 502.
- 404 unknown job, 409 no manifest yet (the socket's 4404 and 4409).
- Measured (real calls on the MALM drawer's own renders, not photos): step 1, 5 and 7 renders → `done_through` 1, 5, 7. 0.75 to 1.5 s for 7 requests.

**`POST /api/perception/{job_id}/parts`** (`server/perception/pile.py`, route in `routes.py`): find the part in the pile, so a headset can highlight what to pick up next.
```json
{"image": "<base64 JPEG>", "want": ["drawer_side_left", "120076"], "step": 2, "lang": "el"}
```
```json
{"found": [{"id": "drawer_side_left", "kind": "part", "box_2d": [83, 47, 300, 452], "confidence": 0.99, "label": "Αριστερό πλαϊνό"}],
 "missing": ["120076"], "message": "Αριστερό πλαϊνό: πάνω αριστερά.", "step": 2,
 "latency_ms": 4175, "model": "gemini-4-hydrogen", "simulated": false}
```
- `want` (optional, at most 12, part ids and hardware codes, codes may be numbers): what to look for. Without it: the step's `pickParts` then its hardware codes, for `step` if given, else the step a display last reported on the socket (`GET .../position`), else the first step. `step` is echoed (null when `want` was given without one). An unknown id or a step not in the guide is 422.
- `box_2d` is Gemini's own convention: `[ymin, xmin, ymax, xmax]`, integers 0–1000, normalised to the picture sent (scale by the picture's height and width). To draw it in the headset, unproject the box centre with the camera intrinsics of that frame.
- `label` is the short name in `lang` (Greek from the manifest's `names.el`, article dropped), with ` · <code>` for hardware. `message` is one sentence about the first wanted item found and where it lies (thirds of the picture: "at the top left", "κάτω δεξιά"); when none is found it asks to see more of the parts; a step with nothing to pick says so and calls no model.
- One `gemini-4-hydrogen` call (thinking `low`, JSON schema with the wanted labels as an enum) per 6 wanted items, in parallel when there are more: the frame (long edge 1280), then each wanted item's `renders/part-<id>-shaded.png` (the manifest's `thumb`), cropped to the object, labelled; then up to 4 **look-alikes not wanted** (hardware of the same kind, the mirrored left/right part), labelled "NOT wanted". The text gives each item's name, kind, count in the box and size: the plan's length or dimensions, else an estimate from the render's own scale (`renders/parts.json` `px_per_mm`), so a long and a short screw differ in the prompt.
- **No invented boxes.** An item goes to `missing` when the model says it is not visible, its confidence is below 0.25, or its box is malformed or thinner than 2 units. Boxes are clamped to 0–1000 and put in min/max order in the server. One box per item: the instance easiest to pick up, or the small heap for hardware lying together.
- A model failure is 502 (429 when the model is rate limited, 503 when the key is refused). 404 unknown job, 409 no manifest yet.
- Measured (real calls, Sun 15 Nov, from a laptop): the MALM drawer's own part renders pasted at known places on a plain background (1280×960, not a photo). IoU with the pasted boxes: side panel left 0.99, right 0.95–0.98, drawer front 0.99, runner 0.94–0.95, back 0.91, cam bolt 0.94–0.95, small screw 0.93, cam lock 0.67–0.69 (box a little short). With only the right side panel and the left runner in view, the left panel and the right runner were reported `missing`, not boxed on their mirror twins; an absent plastic pin was `missing`. 4.2 to 5.8 s per call (3–4 items). Real photos, hands and clutter are **not measured yet**.

**`mode: "step"` on the same route** [added Sun 15 Nov, 15:00]: one call that finds everything the step needs. Optional; without it (or with `"mode": "pick"`) the request and the response above are unchanged, key for key. With it the model is also asked how many of each item are in view and for a box around each one, and the response gains three keys: `mode`, `items` and `all_in_view`. `found`, `missing`, `message`, `step`, `latency_ms`, `model` and `simulated` are as above.
```json
{"image": "<base64 JPEG>", "step": 2, "lang": "el", "mode": "step"}
```
```json
{"found": [{"id": "drawer_side_left", "kind": "part", "box_2d": [110, 76, 305, 439], "confidence": 0.99, "label": "Αριστερό πλαϊνό"},
           {"id": "drawer_side_right", "kind": "part", "box_2d": [443, 83, 638, 447], "confidence": 0.99, "label": "Δεξί πλαϊνό"},
           {"id": "101345", "kind": "hardware", "box_2d": [178, 616, 197, 666], "confidence": 0.98, "label": "Ξύλινη καβίλια · 101345"}],
 "missing": [], "message": "Αριστερό πλαϊνό: πάνω αριστερά.", "step": 2,
 "mode": "step", "all_in_view": true,
 "items": [
  {"id": "drawer_side_left", "code": null, "kind": "panel", "name": {"en": "Left side panel", "el": "Αριστερό πλαϊνό"},
   "label": "Αριστερό πλαϊνό", "visible": true, "box_2d": [110, 76, 305, 439], "boxes": [[110, 76, 305, 439]], "confidence": 0.99,
   "count_seen": 1, "count_needed": 1, "count_in_box": 1, "count_text": {"en": "1 of 1 in view", "el": "φαίνονται 1 από 1"}},
  {"id": "drawer_side_right", "code": null, "kind": "panel", "name": {"en": "Right side panel", "el": "Δεξί πλαϊνό"},
   "label": "Δεξί πλαϊνό", "visible": true, "box_2d": [443, 83, 638, 447], "boxes": [[443, 83, 638, 447]], "confidence": 0.99,
   "count_seen": 1, "count_needed": 1, "count_in_box": 1, "count_text": {"en": "1 of 1 in view", "el": "φαίνονται 1 από 1"}},
  {"id": "101345", "code": "101345", "kind": "hardware", "name": {"en": "Wooden dowel", "el": "Ξύλινη καβίλια"},
   "label": "Ξύλινη καβίλια · 101345", "visible": true, "box_2d": [178, 616, 197, 666],
   "boxes": [[178, 616, 197, 666], [243, 707, 301, 745]], "confidence": 0.98,
   "count_seen": 2, "count_needed": 2, "count_in_box": 2, "count_text": {"en": "2 of 2 in view", "el": "φαίνονται 2 από 2"}}],
 "latency_ms": 5706, "model": "gemini-4-hydrogen", "simulated": false}
```
(A real answer: the MALM drawer's renders pasted on a plain background, step 2.)
- `items` has **one entry for every wanted item, seen or not**, in the order asked (the step's parts, then its hardware codes; or `want`). An item not seen has `visible: false`, `box_2d: null`, `boxes: []`, `confidence: 0`, `count_seen: 0`.
- `id` is the part id or the hardware code; `code` repeats it for hardware and is `null` for a part. `kind` is `"panel"` or `"hardware"` here (the older `found[].kind` keeps `"part"` / `"hardware"`).
- `name.en` and `name.el` are the manifest's short names, first letter upper case, Greek without its article; `name.el` is `null` when the job has no Greek name. `label` is the one in `lang`, as in `found`.
- `box_2d` is the instance easiest to pick up; `boxes` is every instance the model boxed, `box_2d` first, at most 12, each `[ymin, xmin, ymax, xmax]` in 0-1000 and cleaned like `box_2d`.
- `count_seen`: how many the model counted in the picture, never less than `boxes` and never more than the box holds. `count_needed`: how many this step uses (the hardware's `qty`; for a part, its `place` actions); `null` when `want` was given without `step`. `count_in_box`: the manifest's count, or `null`. `count_text` says it in both languages (`"3 of 4 in view"`, `"φαίνονται 3 από 4"`), `null` without a needed count.
- `all_in_view`: every item is seen at least `count_needed` times (once when that is unknown).
- Still one `gemini-4-hydrogen` call per 6 wanted items. Measured (real calls, Sun 15 Nov, a laptop, the MALM drawer's renders pasted at known places on a plain 1280×960 background, not a photo): **4.5 to 6.6 s, median 5.1 s** over 6 calls with 1 to 3 wanted items; the default mode on the same pictures took 3.9 to 5.0 s. Counts were right in all 6 calls (2 of 2 dowels; 3 small screws in view of the 4 needed → `"3 of 4 in view"`; plastic pins not in the picture → 0 of 2). Box IoU: panels 0.98 to 1.0, dowels 0.89 and 0.94, small screws (44 px long) 0.57 to 0.94. One wrong answer in both modes: with the back panel absent, the left side panel (the same outline) was boxed as the back panel at confidence 0.95 to 0.98: look-alike protection covers hardware of the same kind and mirrored left/right parts, not two different panels of the same size. Real photos, hands and clutter are **not measured**.

**Simulated.** Without `GEMINI_API_KEY`, or with `PERCEPTION_SIMULATE=true`, none of these calls a model and all say `"simulated": true`: identify matches the first built guide at confidence 0.5 (`match: null` when none is built), locate answers `done_through: 0`, `next_step` the first step, every step `complete: false`; parts boxes the first wanted item at `[350, 350, 650, 650]`, confidence 0.5, puts the rest in `missing`, and says `model: null` (with `mode: "step"`, that item has `count_seen: 1` and the others 0).

## Voice endpoint for the native headset apps [added Sun 15 Nov]

**`POST /api/voice/{job_id}`** (`server/voice.py`, `server/routes/voice.py`): one utterance in, an intent and a short spoken reply out. It gives hands-free control and spoken questions with one call, so the Quest apps need no Live session. Demo key required.
```json
{"audio": "<base64 WAV, or headerless PCM16 16 kHz mono, or OGG/Opus>", "mime": "audio/wav|audio/ogg|audio/pcm",
 "lang": "el|en", "step": 2, "action": 1, "speak": false,
 "text": "<optional, instead of audio: a transcript (tests, simulate, typed input)>", "rate": 16000}
```
```json
{"transcript": "επόμενο", "intent": "next|back|repeat|where_am_i|identify|ask|none",
 "answer": "Πάρε το δεξί πλαϊνό και βάλε το στη θέση που φαίνεται.",
 "target": {"step": 2, "action": 2}, "actions": [],
 "tts": "<base64 WAV, only with speak:true>", "tts_ms": 2100,
 "latency_ms": 2300, "model": "gemini-4-hydrogen|keywords|simulated", "simulated": false}
```
- `step` is the manifest step number, `action` the manifest action index (as the headset walks it). `step` defaults to the first step, `action` to 0. An unknown step is 422.
- **One `gemini-4-hydrogen` call** (thinking `low`, JSON schema) hears the clip inline and returns the transcript, the intent and, for `ask`, a grounded answer. The model sees the same job facts as `POST /api/jobs/{id}/ask` (`pipeline.ask.facts`: parts, hardware codes and counts, every step, the current step's actions). Greek and English synonyms are in the prompt and in `server.voice.keywords` (επόμενο, πάμε, έγινε, next, done; πίσω, back; ξανά, επανάλαβε, repeat; πού είμαι, where am I; τι είναι αυτό, ποια βίδα είναι αυτή, what is this).
- **Commands are answered here, not by the model.** `next` and `back` move to the next or previous guided action (`note` actions are skipped, as in `Guide.next()` on the headset). `answer` is that action's own text, prefixed "Πάμε στο βήμα N, σελίδα P." / "On to step N, page P." when the step changes. `target` is the `{step, action}` to show, or `null` at either end (then `answer` says it is the first or the last step). `repeat` reads the current action. `where_am_i` says the step, total and page, then the current action. `identify` asks the person to hold the thing up to the camera (`target: null`). The app follows up with a frame (`/api/identify` or the socket's `show_part`). `none` has `answer: ""`: say nothing.
- **`ask`**: at most 2 sentences and 35 words, in `lang`. Any sentence that names a code (5 or more digits) the job does not have is dropped. If nothing is left, the answer becomes "Check the drawing for step N, page P." `actions` are the ask route's screen actions (`highlight_hardware`, `highlight_part`, `go_to_step`), kept only when they name the job's own codes, ids and steps.
- **Logging for R7.** The app logs the step change as `step.by: "voice"` when it applies `target`.
- **`text` instead of `audio`**: keywords decide. A command needs no model call (`model: "keywords"`). Only a question goes to the model, as typed text.
- **`speak: true`**: `answer` is synthesized the same way as `POST /api/tts` (same model, voice, cache and 30-per-minute limit) and returned as base64 WAV in `tts`. If the synthesis fails, `tts` is `null` with `tts_error`, and the app speaks with its own voice.
- **Simulated.** Without `GEMINI_API_KEY`, or with `PERCEPTION_SIMULATE=true`: `text` is matched by keywords, a question is answered with the step's hardware from the manifest ("Step 2 uses 2 x wooden dowel 101345, page 5."), audio without text is `none`, no `tts`, and `simulated: true`.
- **Errors.** 401 without the key, 404 for an unknown job, 409 when there is no manifest yet, 413 for audio over 1.5 MB decoded, 415 for another mime, 422 for bad base64, a non-Ogg body sent as `audio/ogg`, or neither `audio` nor `text`, 429 past 60 model calls a minute over all callers, 502 when the model fails.
- **Measured** (real calls, Sun 15 Nov, from a laptop): 8 clips made with the `/api/tts` voice ("επόμενο", "πήγαινε πίσω", "ποια βίδα χρειάζομαι τώρα;", "next", "where am I?", "επανάλαβε, σε παρακαλώ", "τι είναι αυτό;", "which screw do I need for this step?") against `malm-drawer` at step 2. Intent was right on 8 of 8, and all 8 transcripts were exact. Both questions were answered with the dowel 101345 and highlighted it. The model took 2.0 to 4.6 s per call (median 2.9 s). In repeat runs the same call ranged from 1.7 to 10.8 s. The time is the API's: a 430-token prompt is no faster than the full 3200-token one. Through the route with `speak: true`, the synthesis added 1.9 to 2.3 s for a new sentence (cached sentences add none). A typed `text` command takes 0 ms of model time.

## Panel endpoint: the drawer front on the table, without a marker [added Sun 15 Nov]

`POST /api/perception/{job_id}/panel` (demo key as the other routes; route in `server/routes/panel.py`, detector in `server/perception/panel.py`). It finds the MALM drawer front (white, 380 × 130 × 16 mm) lying flat on the table in one camera frame and returns its pose on the table plane. No model call: plain OpenCV, about 10 ms. It is for the native apps, mainly Unity, which has no OpenCV.

**Measured on renders only**, never on a real Quest frame (`tools/markerless/FRONT_PANEL.md`): 71 of 90 frames trusted, position error p50 4.6 mm, p90 10.8 mm, max 17 mm, yaw p50 0.2°. Use the pose only when `trusted` is true; otherwise keep the last good one.

Request:
```json
{"image": "<base64 JPEG>", "fx": 870.0, "fy": 870.0, "cx": 640.0, "cy": 480.0,
 "cam_in_table": [0.99773, -0.0547, 0.03921, -0.06248,
                  -0.06492, -0.62834, 0.77522, -0.58243,
                  -0.01776, -0.77601, -0.63047, 0.4681,
                  0.0, 0.0, 0.0, 1.0],
 "prev_yaw": 1.4}
```
Reply (this request is `tools/markerless/sample/img/s00_f010.jpg`; the true pose is 3.8 mm and 0.12° away):
```json
{"trusted": true, "reason": "ok", "x": -0.09058, "y": -0.03513, "yaw": 1.40756,
 "corners_px": [[625.15, 685.13], [668.47, 369.46], [537.3, 364.59], [427.09, 671.43]], "ms": 8.4}
```

Frame conventions:
- **Image and intrinsics.** `fx fy cx cy` are in pixels of the JPEG as sent: if the app downscales the frame, it scales all four. Pixel origin top-left, u right, v down. The image must be undistorted.
- **Camera frame.** OpenCV axes: x right, y down, z forward into the scene. Metres.
- **Table frame.** Right-handed, z along the table normal (up), origin anywhere on the table surface, x and y in the surface. Metres.
- **`cam_in_table`.** The 4 × 4 pose of the camera in the table frame, 16 numbers **row-major**: `p_table = R · p_camera + t`, so the numbers are `r00 r01 r02 tx, r10 r11 r12 ty, r20 r21 r22 tz, 0 0 0 1`. It must be the pose at the frame's exposure time. A column-major matrix, a scale, or a left-handed rotation is refused with 422.
- **Reply.** `x, y` (metres) is the panel centre in the table frame. `yaw` (radians, about table z, from table x towards table y) is the direction of the 380 mm side. A rectangle looks the same turned round, so yaw is only known modulo π: without `prev_yaw` it is in [0, π); with `prev_yaw` (the app's current yaw) the reply is the nearer of the two.
- **`corners_px`.** The four corners of the panel's **top face** (16 mm above the table) in the sent image, in the order (−190, −65), (+190, −65), (+190, +65), (−190, +65) mm in the panel's own axes (x along the 380 mm side). `null` when nothing was found.
- **From Unity** (left-handed, y up; derived, not run on a device). Work in the local axes of the table anchor, whose y is the normal. With the camera's position `p` and its `right`, `up`, `forward` in those axes, and `S(v) = (v.x, v.z, v.y)`: the rotation columns are `S(right)`, `−S(up)`, `S(forward)` and the translation is `S(p)`. In the reply, the panel centre is `(x, 0, y)` and its 380 mm side is along `(cos yaw, 0, sin yaw)` in the anchor's local axes.
- **Simpler for both apps:** ignore `x, y, yaw` and use `corners_px`. Cast a ray through each corner with the app's own camera pose and intrinsics, intersect the plane 16 mm above the app's own table, and take the centre and long side of the four hits.

Reasons when `trusted` is false: `no candidate`, `size is not 380 x 130 mm`, `not a clean rectangle (hand or object over it)`, `panel partly out of the camera view`, `low contrast against the table`, `camera not looking down at the table`, `table not in view`. In the first case and the last two, `x`, `y`, `yaw` and `corners_px` are `null`; in the others they hold the rejected candidate, for debugging only.

Errors: 401 without the key, 404 for an unknown job, 413 for an image over 2 MB, 422 for a bad image, intrinsics or pose. The job is only checked to exist: the panel size is the MALM drawer front's, fixed in `server/perception/panel.py`.

```sh
curl -s -X POST "$BASE/api/perception/malm-drawer/panel" -H "X-Demo-Key: $DEMO_KEY" -H 'Content-Type: application/json' \
  -d "{\"image\": \"$(base64 -i frame.jpg)\", \"fx\": 870, \"fy\": 870, \"cx\": 640, \"cy\": 480, \"cam_in_table\": [ ...16 numbers... ]}"
```

## Real headset frames [added Sun 15 Nov, 13:45]

What the server had kept from the day's headset sessions: `frames.jsonl` (every answered frame's scores), `events.jsonl`, `hands.jsonl`. **No pictures.** Pictures came from the Android app's own dump (199 frames, 1280 x 960, a bright office, grey carpet, the headset worn); 41 of them were labelled by eye and sent as the app sends them (768 px, nearest-neighbour, quality 70), one real call each.

**The frame gate.** A frame is discarded when the model's `image_poor` reaches a line. That line was the Watcher's `unusable` (0.3, shared with `view_blocked`); it is now the server's own `poor_at`, **0.55**. `view_blocked` keeps 0.3.

| | discarded at 0.3 | discarded at 0.55 |
|---|---|---|
| Labelled real frames, usable (32: boards in view, hands on parts, hardware in the hand; `image_poor` 0.08-0.19) | 0 | 0 |
| Labelled real frames, bad (9: 3 motion-blurred, 2 blurred and off the work, 4 sharp but of the desk; 0.16-0.43) | 5 | 0 |
| Session logs, answered headset frames in which the model saw a hand (924 of 5411) | 205 (22 %; 55 % in the 12:14 session) | 76 (8 %; 23 % in that session) |
| Session logs, all answered headset frames (5411) | 4169 (77 %) | 1791 (33 %) |
| Session logs, unbroken 5 s stretches of discarded frames (what makes `more_light`; 53 were really said) | 56 | 14 |
| Imitated unreadable frames, not real (8: dark, defocused, overexposed, motion blur; 0.37-0.92) | 8 (3 without a call) | 6 (3 without a call) |

- With the headset worn in good light the old line lost no usable frame. In the session logs it discarded 22 % of the frames in which the model saw a hand (no pictures of those exist, so how many a person would call usable is not known), and most frames without a hand in view, which scored about 0.5 (median 0.51; by the user's account the headset was lying on a desk or looking at the floor). A desk seen from the head scores 0.26 to 0.43.
- **`more_light` was the event said most often that day: 53 times, against 14 of every other kind, in a lit office.** It is now said only when the discarded frames are dim: a poor frame whose mean brightness is `dim_luma` (50) or more does not count (real frames had 82 to 120). Frames too dark to send count as before.
- `image_poor` does not catch motion blur (real blurred frames: 0.16, 0.25, 0.41) and cannot tell "no work in view" from a murky usable frame. What keeps verdicts off such frames is the rest of the logic: hand questions count only with `hands_present` at 0.5, scene questions are dropped for a headset when `view_blocked` reaches 0.3, a choice needs 0.75. Each of the 9 bad frames, held for 3 s, gave no event at either line. A client that knows the head is turning fast should not send those frames.
- The local dark check (`dark_luma`, no call spent) never fired on real frames: the headset's exposure brings a grey carpet to a mean of 82 to 120. It now also needs the brightest 2 % of the frame under `dark_peak` (60), so that a dark floor with lit parts on it is not thrown away. Unproven on a really dim room.
- The app's new 2 x 2 averaged downscale at quality 80 changes little for the gate: `image_poor` 0.11, 0.11, 0.13, 0.25 became 0.11, 0.10, 0.09, 0.22 on four real frames.

**Is the part in my hand right?** Send `show_part`, then one frame at the camera's full 1280 x 960 (`hint` is not needed; the look is always at `high`) while the hand is still.

- Right hardware, real frames, hand at about 45 to 70 cm: named in the 768 px stream in 6 of 8 frames (`118331` 0.84-0.91, `right_hardware` 0.77-0.89) and in 3 of 4 full-size looks (0.83-0.94).
- Wrong hardware (drawn hands only, no real frame exists): caught to about 25 cm in the 768 px stream and to about 50 cm in a full-size look; at 70 cm it is never called right, the answer is "show it closer".
- An empty pinching hand was once read as the expected screw (0.77, with `no_hardware` at 0.18, `right_hardware` 0.57). A code is now named only when `no_hardware` is under `show_nothing_max` (0.1), and a code needed now only when `right_hardware` reaches `show_right_min` (0.7). Five empty-hand looks (2 real, 3 drawn) answer `match: null`. By the same rule the two earlier measurements that went wrong would too: that empty hand, and a wrong plastic pin that scored 0.78 as the needed screw with `right_hardware` at 0.42 (both were measured with the stream's questions, not re-run as looks).
- A sure "not the right one" (`right_hardware` at 0.15 or less) lets a leaning other code (0.4 or more) be named, as in the stream's rule.
- So: **hold it still, about 30 to 50 cm from the headset.**

**What the real parts look like.** The reference pictures are rendered in one plain colour; the real MALM front is wood-coloured, its sides and back light grey. On real frames the front in the hands scored `drawer_front` 0.63 to 0.87 (three of six frames under the 0.75 line), and the light grey bottom panel was taken for the front (0.78, `right_part` 0.82: a false "right part"). One sentence about the real colours, appended to the request, moved the front to 0.74-0.83 (`right_part` 0.84-0.94) and the bottom panel to 0.31 (`right_part` 0.21); six frames, one call each. A job can carry that sentence as **`perception_note`** in `manifest.json` (optional, at most 400 characters, added as the last text block of every request). The better fix is the real material colour in the job's renders, which needs the refs stage again.

**Keeping frames.** `PERCEPTION_KEEP_FRAMES=<count>` keeps camera frames as they arrived under `jobs/_host/frames/<session>/<seq>-<used|poor|dark|show|bad_frame>.jpg`: one a second at most, every `show_part` look, until `count` frames or `PERCEPTION_KEEP_FRAMES_MB` megabytes (what the folder already holds counts). They join `frames.jsonl` by `seq`. Off by default. The frames show the user's room; `jobs/` is git-ignored and they must stay there.

## Settings

In `server/settings.py`, with commented lines in `.env.example`:

| Setting | Default | |
|---|---|---|
| `PERCEPTION_SIMULATE` | `false` | Answers come from the debug script; every message carries `sim:true`. Takes precedence over a key. |
| `PERCEPTION_LOGS` | `true` | Write the per-session JSONL files under the job folder. |
| `PERCEPTION_KEEP_FRAMES` | `0` | Opt-in: keep up to this many camera frames under `jobs/_host/frames/` ("Real headset frames"). |
| `PERCEPTION_KEEP_FRAMES_MB` | `200` | ...and stop at this many megabytes. |
| `PERCEPTION_TUNING` | empty | One JSON object of overrides. An invalid value is ignored, logged, and reported to each client as `error` `bad_tuning`. |

`PERCEPTION_TUNING` reaches every number:
```sh
PERCEPTION_TUNING='{"max_in_flight": 8, "watcher": {"done_yes": 0.5, "windows": {"wrong_hardware": [3, 4]}}, "headset": {"step_complete": [9, 10]}}'
```
- Top level: the fields of `Tuning` in `server/perception/config.py` (`max_in_flight`, `max_hz`, `decision_timeout_s`, `confirmations`, `ask_right_hardware`, `step_complete_only_on_last_action`, …). Added Sun 15 Nov: `poor_at` (0.55; `0.3` restores the old line), `dim_luma` (50; `0` lets every poor frame count towards `more_light`), `dark_peak` (60; `0` restores the mean alone), `show_nothing_max` (0.1), `show_right_min` (0.7).
- `watcher`: the fields of `Config` in `pipeline/perception/logic.py` (`yes`, `no`, `choice`, `done_yes`, `done_orientation`, `hands`, `unusable`, `windows`, `unsure_frames`, `hands_away_s`, `cooldown_s`, `global_cooldown_s`). Their defaults are not repeated in the server.
- `fixed`, `headset`: per-source rules; a window given there replaces the Watcher's for that source.

Requests at once: 4 uses about half of Tier 1's token budget (1M per minute) for one session; 8 was faster in one run (1.8 s to `step_complete`) and uses nearly all of it.

With neither a key nor simulation, the socket stays open for steps, actions and hands, says `vision:"unavailable"` in `hello` and `status`, sends `vision_paused` with the reason, and acks every frame `vision_unavailable`.

The model id is `gemini-decision-1`, set in `pipeline/check/decisions.py`. The server's key reaches that client through the `GEMINI_API_KEY` environment variable.
