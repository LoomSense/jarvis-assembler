# Assembly Guide Agent: instructions for coding agents

Read these two files before starting any task:
- `docs/PLAN.md`: the technical plan. Its §1 decisions override everything else, including `docs/research/`.
- `docs/PROGRESS.md`: shared team status. Find your person (A–E) and your task IDs there.

## You must keep docs/PROGRESS.md up to date

Follow the update protocol in `docs/PROGRESS.md` §1 every time one of your tasks changes state, a gate or test result is known, a blocker appears, or a decision is made:

1. `git pull --rebase`.
2. Edit only your person's rows in §3, the matching row in §2, the test rows you ran in §4, and your own log in §7. Never edit another person's rows.
3. Set the status (`todo`, `doing`, `blocked`, `done`, `cut`), the Athens time, and evidence (commit hash, file path, measured number). A task is `done` only when its "Done when" condition is met.
4. Blockers go in §5. Plan-changing decisions go in §6 and in `docs/PLAN.md` §1.
5. Commit with `progress: <TASK-ID> <status>`, then `git pull --rebase && git push`.

If you don't know which person you are working for, ask the human before editing `docs/PROGRESS.md`.

## Ground rules from the plan

- Never put `GEMINI_API_KEY`, `FAL_KEY` or `BOXD_API_KEY` in client code or in git. Use `.env` (ignored) and `.env.example`.
- Model IDs: `gemini-4-carbon`, `gemini-4-hydrogen`, `gemini-decision-1`, `gemini-3.8-live`. Do not substitute older models.
- Keep the contracts in `docs/PLAN.md` §4 (job folder, plan schema, manifest, events, endpoints). Changing one is a decision: log it.
- Credit third-party code in `CREDITS.md`.
- Code freeze: Sunday 15 Nov 2026, 18:00 Athens time.
