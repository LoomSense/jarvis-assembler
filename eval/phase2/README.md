# Phase 2 ground truth and scoring

Covers LIV-04 (record and label clips) and the scoring half of LIV-05, which decides gate G4 (Sunday 11:00).
One tool, `p2.py`, standard library plus ffmpeg.

## 1. Record (needs the physical product)

Five clips, 20–40 s each, with the camera chosen at gate G2, fixed at the demo angle and demo lighting.
Start every clip with 3 s of empty hands so the first segment is easy to label.
The scripts for the KNAGGLIG box are in `labels.template.json`:

| Clip | Kind | What happens | Expected event |
|---|---|---|---|
| `a_correct` | correct | step 1 done right | `step_complete`, and **no** warning |
| `b_wrong_panel` | mistake | side slat picked up instead of the end frame | `wrong_part` |
| `c_upside_down` | mistake | handle slat held the wrong way, then turned | `flip_needed` |
| `d_wrong_hardware` | mistake | short screw 108560 where 108363 is needed | `wrong_hardware` |
| `e_bad_light` | hard | dim light, hands over the work | none |

Put the videos in `clips/` (git-ignored, they are large and show people).

## 2. Label

```
python eval/phase2/p2.py frames eval/phase2/clips/a_correct.mp4   # writes clips/a_correct/f00012_t003.00.jpg ...
cp eval/phase2/labels.template.json eval/phase2/labels.json       # then edit the times
python eval/phase2/p2.py check eval/phase2/labels.json
```

Labels are **segments**: `t0` is the time of the first frame where something changed (read it from the frame file name), and a segment lists only the fields that changed. About 5 segments per clip instead of 600 frame labels.

- yes/no questions: `true`, `false`, or `null` when the question does not apply or a person cannot tell from the frame. `null` frames are not scored for that question.
- `part_in_hand`: a part id from `parts`, or `hardware_only`, `nothing_held`, `other`.
- `hardware_in_hand`: a code from `hardware`, or `no_hardware`, `other`.
- `events`: `t` is the **first moment the mistake is visible**, not when a person would speak. Detection delay is measured from it.
- `kind: correct` clips may only expect `step_complete`; any other event on them is a false alarm.

## 3. What the replay must write (contract for `replay.py`, LIV-05)

Two JSONL files per run in `runs/<name>/`:

```
frames.jsonl  {"clip":"a_correct","frame":12,"t":3.0,"latency_ms":212,
               "answers":{"right_part":0.93,"hands_present":0.99,"part_in_hand":{"end_frame":0.81,"other":0.05,"nothing_held":0.02}}}
events.jsonl  {"clip":"b_wrong_panel","t":4.25,"event":"wrong_part","step":1,"latency_ms":410}
```

`frame` is the index from `p2.py frames` (frame k is at k / fps seconds). Yes/no answers are the probability of yes. `latency_ms` on a frame is the Decision 1 round trip; on an event it is frame capture to event delivery.

## 4. Score

```
python eval/phase2/p2.py score eval/phase2/labels.json runs/x/frames.jsonl runs/x/events.jsonl
```

Exit 0 only when every G4 gate passes:

| Gate | Rule |
|---|---|
| Event accuracy | hits / (expected events + false alarms) ≥ 0.70 |
| False alarms on `correct` clips | 0 |
| Detection delay | median ≤ 2 s (max is printed too) |
| Decision latency | p50 ≤ 250 ms, p95 ≤ 600 ms |
| Event latency | p50 ≤ 500 ms |

A predicted event is a hit when it has the expected type and lands between 0.5 s before and 5 s after the labelled time. Per question it prints accuracy at the PLAN §6.2 thresholds, the share left undecided, AUC (rewrite the question below 0.8) and the lowest threshold that gives 0.95 precision.
