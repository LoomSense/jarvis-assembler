# Bake-off: which model runs the build loop (guide → plan → part scripts → fix)

Run Sat 14 Nov 2026, about 21:15–21:50, on the KNAGGLIG guide. Both sides got the same brief (`pipeline/agent/BRIEF.md`), the same 8 page images and a clean workspace made by `setup.sh` with no hand-made plan or parts in it.

| | Claude (headless Claude Code, `claude -p`) | Gemini 4 Hydrogen (`run_gemini.py`, one call per round) |
|---|---|---|
| Finished | Yes, first run | No, 4 attempts |
| Fit checks | Pass: 37 pieces, 0.0 mm worst residual, no overlaps | No files written |
| `eval/score_plan.py` vs `expected_plan.json` | PASS: 1.00 on all gates, `flip_acc` 0.88 | None |
| Time | 504 s, 32 turns | 330 s, 336 s, 314 s, 12 s (all failed) |
| Cost | $2.74 reported by the run | Not measured |

Gemini failures: in runs 1–3 the model used its whole 65,533-token output limit on thinking and returned 3 tokens (`finishReason: MAX_TOKENS`); `thinkingBudget: 24000` was ignored. In run 4, with `thinkingLevel: "low"`, it returned 280 tokens that were not valid JSON; the text was not saved. This shows that one large request does not work for Hydrogen. It does not show that a loop of smaller calls would fail: that was not tried.

Caveats:
- The Claude side used the local Claude Code login on one laptop, not the Agent SDK with an API key. The SDK wraps the same engine.
- `FACTS.md` wrongly said the steps are on pages 3–6. Claude followed the guide (8 steps, pages 3–8) and flagged it.
- Claude compared only the renders for steps 2, 4, 6, 8 and the cover with the guide.
- Claude's step 1 reading (the lying panel is a side, the standing one the bottom) differs from Hydrogen's earlier reading of the same drawing. Not settled without the real product.

Claude's output is in `results/claude/` (`plan.json`, `parts/`, `steps.png`). To rerun:

    eval/bakeoff/setup.sh <workspace> jobs/knagglig/pages "<facts>"
    .venv/bin/python -m eval.bakeoff.run_gemini <workspace> gemini-4-hydrogen 5
    (cd <workspace> && claude -p "Read BRIEF.md and FACTS.md in this directory and do the task they describe." --permission-mode acceptEdits --allowedTools Read Write Edit Glob Grep "Bash(./py:*)")
