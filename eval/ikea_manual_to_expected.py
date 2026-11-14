#!/usr/bin/env python3
"""Convert IKEA-Manual annotations into expected_plan.json files for score_plan.py.

    python eval/ikea_manual_to_expected.py            # all 102 objects
    python eval/ikea_manual_to_expected.py Table/lack # one object

Reads  eval/datasets/ikea_manual/{main_data.json, parts/<Category>/<name>/NN.obj}
Writes eval/datasets/ikea_manual_expected/<Category>/<name>/expected_plan.json (git-ignored).

What the dataset gives: primitive parts with real 3D shape, which parts join in which step, the page.
What it does NOT give: hardware (screws, dowels), printed labels, part names, turn-over marks.
So these files set "anonymous_parts": true and have no "hardware" key; the scorer adapts.
Identical parts are grouped by bounding-box size, because the dataset's own equivalence field is incomplete.
Pages are 1-based like our Cite.page (checked: the smallest page_id is 1 and 35 objects have a step on
their PDF's last page). Two objects cite a page past the end of their PDF; ignore step_page_acc for those.
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "datasets", "ikea_manual")
DST = os.path.join(HERE, "datasets", "ikea_manual_expected")
TOL = 0.03  # two parts are "the same" when each sorted extent differs by under 3% of the largest extent


def extents(path):
    lo, hi = [float("inf")] * 3, [float("-inf")] * 3
    with open(path) as f:
        for line in f:
            if line.startswith("v "):
                for k, v in enumerate(map(float, line.split()[1:4])):
                    lo[k] = min(lo[k], v); hi[k] = max(hi[k], v)
    return sorted((h - l for h, l in zip(hi, lo)), reverse=True)


def convert(obj):
    cat, name, n = obj["category"], obj["name"], obj["parts_ct"]
    pdir = os.path.join(SRC, "parts", cat, name)
    ext = {i: extents(os.path.join(pdir, f"{i:02d}.obj")) for i in range(n)}
    scale = max(e[0] for e in ext.values()) or 1.0
    groups = []  # [[part index, ...]]
    for i in range(n):
        for g in groups:
            if all(abs(a - b) / scale < TOL for a, b in zip(ext[i], ext[g[0]])):
                g.append(i); break
        else:
            groups.append([i])
    cls = {i: f"p{g[0]:02d}" for g in groups for i in g}
    parts = [{
        "id": f"p{g[0]:02d}", "name": "", "aliases": [], "count": len(g), "members": g,
        "dims_ratio": [round(v / scale, 4) for v in ext[g[0]]],  # sorted, largest part edge of the product = 1
        "obj": [f"parts/{cat}/{name}/{i:02d}.obj" for i in g],
    } for g in groups]
    seen, steps = set(), []
    for k, s in enumerate(sorted(obj["steps"], key=lambda s: s["step_id"]), 1):
        new = sorted({int(p) for grp in s["parts"] for p in grp.split(",")} - seen)
        seen |= set(new)
        steps.append({
            "n": k, "cite": {"page": s["page_id"], "label": ""}, "parts_added": [cls[i] for i in new],
            "hardware_used": {}, "flip_whole_assembly": False,
            "connections": [[a, b] for a, b in s["connections"]],
        })
    return {
        "title": f"{cat}/{name}", "guide": f"eval/datasets/ikea_manual/pdfs/{cat}/{name}/0.pdf",
        "labelled": {"by": "IKEA-Manual dataset (Wang et al., NeurIPS 2022)", "page_index_base": 1},
        "anonymous_parts": True, "parts": parts, "steps": steps,
        "unused_parts": sorted(set(range(n)) - seen),
    }


def main(argv):
    data = json.load(open(os.path.join(SRC, "main_data.json")))
    want = set(argv)
    done, seen_names = 0, {}
    for obj in data:
        key = f"{obj['category']}/{obj['name']}"
        if want and key not in want:
            continue
        k = seen_names[key] = seen_names.get(key, 0) + 1  # a few names repeat in the json
        if k > 1:
            print(f"skip duplicate entry {key} #{k}"); continue
        out = os.path.join(DST, obj["category"], obj["name"]); os.makedirs(out, exist_ok=True)
        json.dump(convert(obj), open(os.path.join(out, "expected_plan.json"), "w"), indent=1)
        done += 1
    print(f"wrote {done} expected plans to {os.path.relpath(DST)}")


if __name__ == "__main__":
    main(sys.argv[1:])
