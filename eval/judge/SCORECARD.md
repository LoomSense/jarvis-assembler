# Scorecard template

Copy to `eval/judge/round-N.md` (or `round-N-<app>.md` for a single-app round) and fill every cell. Criteria and anchors: `eval/judge/RUBRIC.md`. Procedure: `eval/judge/judge.md`.

```
Round:        N
Date (Athens): YYYY-MM-DD HH:MM
Judge:        <who ran it>
Commits:      webxr <hash> · unity <hash> (worktree <path>) · android <hash> (worktree <path>)
Server:       <host> · server commit <hash>
Device:       Quest 3 · Horizon OS <ver> · browser <ver if WebXR>
Artifacts:    eval/judge/round-N/  (logs, summaries, build outputs, screenshots)
```

## 1. Per app

Repeat this section for each app (`webxr`, `unity`, `android`).

### <app> · total **NN.N / 100** (previous round NN.N)

| ID | Criterion | Wt | Score | Ev. | Points | Evidence (files, commands, measured numbers) | Cap / unverified |
|---|---|---|---|---|---|---|---|
| R1 | Identify | 7 | | | | | |
| R2 | Guide load and transforms | 7 | | | | | |
| R3 | 6-DoF tracking | 20 | | | | | |
| R4 | Step and state detection | 10 | | | | | |
| R5 | On-object guidance | 12 | | | | | |
| R6 | Hands and intent | 8 | | | | | |
| R7 | Voice and conversation | 7 | | | | | |
| R8 | Zero-setup flow | 8 | | | | | |
| R9 | Performance and comfort | 8 | | | | | |
| R10 | Engineering | 7 | | | | | |
| R11 | Find parts | 6 | | | | | |
| | **Total** | **100** | | | **NN.N** | | |

`Ev.` is the evidence level E0 to E4 (RUBRIC §3). `Points` = score / 5 × weight. `Cap / unverified`: write `cap 2 (no device log)` or `cap 3 (partly device)` when a cap lowered the score, and the score the code alone would have reached, e.g. `cap 2, code 4`.

**Measured numbers** (fill what exists; `n/a` otherwise, never leave blank):

| Metric | Value | Source |
|---|---|---|
| Build | pass / fail, time | |
| Unit tests | passed / total | |
| identify p50 ms, correct k/n | | |
| job_loaded ms (malm-drawer) | | |
| max transform error mm | | |
| track rate Hz, tracked %, jitter mm, error mm/deg | | |
| step accuracy %, false completes | | |
| fps p50 / p5 | | |
| user actions / seconds to first ghost | | |
| errors in log (count, top code) | | |

**Top 3 fixes for next round** (highest points gained per hour first; format from `judge.md` §6):

1. **[R?] <title>** (+N pts if it reaches score X)
   - File: `path/to/file:line` (or the module to add)
   - Symptom: what the judge saw, with the number
   - Expected: the observable result that earns the next anchor
   - Verify: the exact command or device test, and the log line or number that must appear
2. ...
3. ...

## 2. Comparison

| ID | Criterion | Wt | WebXR | Unity | Android | Best |
|---|---|---|---|---|---|---|
| R1 | Identify | 7 | | | | |
| R2 | Guide load | 7 | | | | |
| R3 | Tracking | 20 | | | | |
| R4 | Step detection | 10 | | | | |
| R5 | On-object guidance | 12 | | | | |
| R6 | Hands and intent | 8 | | | | |
| R7 | Voice | 7 | | | | |
| R8 | Zero setup | 8 | | | | |
| R9 | Performance | 8 | | | | |
| R10 | Engineering | 7 | | | | |
| R11 | Find parts | 6 | | | | |
| | **Weighted total** | 100 | | | | |
| | Criteria still unverified (count) | | | | | |
| | Device evidence level reached (max E) | | | | | |

Key measured numbers side by side:

| Metric | WebXR | Unity | Android |
|---|---|---|---|
| Track rate Hz / tracked % / error mm | | | |
| fps p5 | | | |
| Actions to first ghost | | | |
| Step accuracy / false completes | | | |
| Build from clean checkout | | | |

## 3. Recommendation

- **Lead after this round:** <app>, by NN points; driven by <criteria>.
- **Confidence:** high / medium / low, and why (how much of the lead is device-verified vs capped).
- **What would change it:** the one or two results next round that would flip the order.
- **For the demo at 18:00:** which client to show, which fallback, and what to say about limits (no claims beyond the evidence).
