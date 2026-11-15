#!/bin/sh
# Re-run every experiment in REPORT.md (Mac CPU, ~5 min in parallel).
# Needs data/synth from gen_dataset.py (see README in REPORT.md).
cd "$(dirname "$0")"
PY=${PY:-../../.venv/bin/python}
[ -x "$PY" ] || PY=python3
mkdir -p out
D=data/synth
$PY eval_edges.py $D out/a_track.json            > out/a_track.log 2>&1 &
$PY eval_edges.py $D out/a_track_clean.json --clean --no-prior-noise > out/a_track_clean.log 2>&1 &
$PY eval_edges.py $D out/a_track_3dof.json --three-dof > out/a_track_3dof.log 2>&1 &
$PY eval_edges.py $D out/a_track_scale2.json --model-scale 1.02 > out/a_track_scale2.log 2>&1 &
$PY eval_edges.py $D out/a_single.json --single --stride 3 > out/a_single.log 2>&1 &
wait
$PY eval_keypoints.py $D out/b_orb.json --det orb > out/b_orb.log 2>&1
$PY eval_keypoints.py $D out/b_sift.json --det sift > out/b_sift.log 2>&1
tail -n 11 out/*.log
