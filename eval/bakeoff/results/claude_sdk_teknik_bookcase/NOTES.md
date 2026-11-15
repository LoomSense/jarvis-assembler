# Build agent on a harder guide: Teknik 2-shelf bookcase (model 5431916)

Guide: `eval/guides/other/teknik-bookcase-5431916.pdf`, 16 pages, 12 steps, cam locks and dowels.
Run on the data owner's Mac, Sun 15 Nov about 01:15, `python -m pipeline.agent.extract <pdf> jobs/hard1`.

| | |
|---|---|
| Agent session | 469 s, 66 turns, $2.85, first `./build` passed |
| Plan | 6 part types, 61 hardware pieces, 12 steps |
| Build re-run by hand at 08:20 | passes: 67 instances, 12 step clips, worst fit error 0.0 mm, GLB 5.3 MB (packed 416 kB) |

What was checked: the cover render against page 1 of the guide by eye. Proportions, the top overhanging the
sides, the fixed middle shelf, the bottom panel and the levelling feet match.

What was not checked: the 12 steps one by one against the guide, the hardware positions, the GLB check.

Open point: `run.json` says `final_build_ok: false`. The rebuild that `extract.py` runs after the session
failed, so `plan.json` and the part scripts were not copied to the job folder. The same build passes when
re-run now. At that time another session was editing `pipeline/refs.py` and `render_refs.py` in the same
checkout, which is the likely cause; this was not confirmed.

The GLB is 5.3 MB before packing, above what the other guides produced (0.2 to 2 MB).
