#!/usr/bin/env python3
"""Score an extracted plan.json against a hand-written expected_plan.json (PLAN §9, AGT-04).

    python eval/score_plan.py eval/golden/knagglig/expected_plan.json jobs/<id>/plan.json [--json]

Exit 0 when the three gate metrics pass: part recall >= 0.9, exact counts >= 0.8,
step-order Kendall tau >= 0.8. Standard library only.
"""
import json, re, sys
from collections import Counter
from itertools import combinations

GATES = {"part_recall": 0.9, "count_exact": 0.8, "step_order_tau": 0.8}
STOP = {"the", "a", "of", "with", "and", "pre", "assembled", "on", "x", "part"}


def load(path):
    d = json.load(open(path))
    return d.get("plan", d)  # accepts GET /plan responses too


def tokens(*texts):
    return {t for s in texts for t in re.findall(r"[a-z0-9]+", str(s).lower())} - STOP


def base(pid):  # "A#2" -> "A"
    return str(pid).split("#")[0]


def jaccard(a, b):
    return len(a & b) / len(a | b) if a | b else 1.0


def greedy(pairs, floor):
    """pairs: [(score, i, j)] -> best one-to-one {i: j} with score >= floor."""
    out, used = {}, set()
    for s, i, j in sorted(pairs, key=lambda p: -p[0]):
        if s >= floor and i not in out and j not in used:
            out[i] = j; used.add(j)
    return out


def dims_sim(ratio, pred_part):
    """Compare sorted edge proportions (largest = 1 within the part). Scale-free, so estimated mm are fine."""
    vals = sorted((float(v["value"] if isinstance(v, dict) else v) for v in (pred_part.get("dims_mm") or {}).values()), reverse=True)[:3]
    if not ratio or len(vals) < 3 or not vals[0] or not ratio[0]:
        return 0.0
    return max(0.0, 1 - sum(abs(a / ratio[0] - b / vals[0]) for a, b in zip(ratio, vals)) / 2)


def match_parts(exp, pred, anonymous=False):
    pairs = []
    for i, e in enumerate(exp):
        names = [tokens(e["id"]), tokens(e.get("name", ""))] + [tokens(a) for a in e.get("aliases", [])]
        for j, p in enumerate(pred):
            pt = tokens(p.get("id", ""), p.get("name", ""))
            s = max(jaccard(n, pt) for n in names) if pt else 0.0
            s += 0.25 * (e["count"] == p.get("count")) + 0.05 * (e.get("shape") == p.get("shape"))
            if anonymous:  # dataset parts have no names: match on count and on proportions
                s = 0.3 * (e["count"] == p.get("count")) + 0.5 * dims_sim(e.get("dims_ratio"), p)
            pairs.append((s, i, j))
    # 0.3 = some name overlap, or equal count plus equal shape. Mapping is printed so a human can check it.
    return greedy(pairs, 0.3)


def step_items(step, rename=None, hardware=True):
    c = Counter()
    for p in step.get("parts_added", []):
        b = base(p); c["part:" + (rename.get(b, "?" + b) if rename is not None else b)] += 1
    for code, q in (step.get("hardware_used") or {}).items() if hardware else ():
        c["hw:" + str(code)] += int(q)
    return c


def multiset_sim(a, b):
    keys = set(a) | set(b)
    if not keys:
        return 1.0
    return sum(min(a[k], b[k]) for k in keys) / sum(max(a[k], b[k]) for k in keys)


def kendall_tau(xs, ys):
    n = len(xs)
    if n < 2:
        return 1.0
    conc = disc = 0
    for i, j in combinations(range(n), 2):
        s = (xs[i] - xs[j]) * (ys[i] - ys[j])
        conc += s > 0; disc += s < 0
    return (conc - disc) / (n * (n - 1) / 2)


def ratio(a, b):
    return a / b if b else 1.0


def score(exp, pred):
    ep, pp = exp["parts"], pred.get("parts", [])
    has_hw = "hardware" in exp  # dataset-derived plans carry no hardware: do not score it
    pm = match_parts(ep, pp, exp.get("anonymous_parts", False))
    rename = {pp[j]["id"]: ep[i]["id"] for i, j in pm.items()}  # predicted id -> expected id
    part_count_ok = sum(ep[i]["count"] == pp[j].get("count") for i, j in pm.items())

    eh = {str(h["code"]): h for h in exp.get("hardware", [])}
    ph = {str(h.get("code")): h for h in pred.get("hardware", [])}
    hw_hit = set(eh) & set(ph)
    hw_count_ok = sum(eh[c]["count"] == ph[c].get("count") for c in hw_hit)

    es, ps = exp["steps"], pred.get("steps", [])
    ei = [step_items(s, None, has_hw) for s in es]; pi = [step_items(s, rename, has_hw) for s in ps]
    if not has_hw:  # hardware-only steps cannot be matched to anything in the dataset
        ps = [s for s, it in zip(ps, pi) if it]; pi = [it for it in pi if it]
    pairs = []
    for i in range(len(es)):
        for j in range(len(ps)):
            # tiny position bonus so identical steps (e.g. both sides of a box) pair up in order
            near = 1 - abs(i / max(len(es) - 1, 1) - j / max(len(ps) - 1, 1))
            pairs.append((multiset_sim(ei[i], pi[j]) + 0.01 * near, i, j))
    sm = greedy(pairs, 0.5)
    order = sorted(sm.items())
    m = len(sm)

    used_hw, used_parts = Counter(), Counter()
    for s in ps:
        for c, q in (s.get("hardware_used") or {}).items(): used_hw[str(c)] += int(q)
        for p in s.get("parts_added", []): used_parts[base(p)] += 1
    problems = [f"hardware {c}: steps use {used_hw[c]}, list says {h.get('count')}" for c, h in ph.items() if used_hw[c] != h.get("count")]
    problems += [f"part {p['id']}: steps add {used_parts[p['id']]}, list says {p.get('count')}" for p in pp if used_parts[p["id"]] != p.get("count")]

    r = {
        "part_recall": ratio(len(pm), len(ep)),
        "part_precision": ratio(len(pm), len(pp)),
        "count_exact": ratio(part_count_ok + hw_count_ok, len(ep) + len(eh)),
        "part_count_exact": ratio(part_count_ok, len(ep)),
        "hardware_recall": ratio(len(hw_hit), len(eh)),
        "hardware_precision": ratio(len(hw_hit), len(ph)),
        "hardware_count_exact": ratio(hw_count_ok, len(eh)),
        "step_recall": ratio(m, len(es)),
        "step_precision": ratio(m, len(ps)),
        "step_content_exact": ratio(sum(ei[i] == pi[j] for i, j in sm.items()), len(es)),
        "step_order_tau": kendall_tau([i for i, _ in order], [j for _, j in order]) if m else 0.0,
        "step_page_acc": ratio(sum(es[i]["cite"]["page"] == (ps[j].get("cite") or {}).get("page") for i, j in sm.items()), m),
        "flip_acc": ratio(sum(bool(es[i].get("flip_whole_assembly")) == bool(ps[j].get("flip_whole_assembly")) for i, j in sm.items()), m),
        "n_steps": {"expected": len(es), "predicted": len(ps)},
        "part_map": {ep[i]["id"]: pp[j]["id"] for i, j in pm.items()},
        "parts_missed": [ep[i]["id"] for i in range(len(ep)) if i not in pm],
        "parts_extra": [p["id"] for j, p in enumerate(pp) if j not in pm.values()],
        "hardware_missed": sorted(set(eh) - set(ph)), "hardware_extra": sorted(set(ph) - set(eh)),
        "step_map": {es[i]["n"]: ps[j].get("n") for i, j in order},
        "steps_missed": [es[i]["n"] for i in range(len(es)) if i not in sm],
        "self_consistency_problems": problems,
    }
    if not has_hw:
        for k in [k for k in r if k.startswith("hardware_")] + ["flip_acc"]:
            del r[k]
        r["not_scored"] = "hardware and flips: not annotated in this ground truth"
    return r


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    if len(args) != 2:
        print(__doc__); return 2
    r = score(load(args[0]), load(args[1]))
    r["pass"] = all(r[k] >= v for k, v in GATES.items())
    if "--json" in argv:
        print(json.dumps(r, indent=2)); return 0 if r["pass"] else 1
    for k, v in r.items():
        if isinstance(v, float):
            gate = f"  (gate >= {GATES[k]}: {'PASS' if v >= GATES[k] else 'FAIL'})" if k in GATES else ""
            print(f"{k:22s} {v:5.2f}{gate}")
    for k in ("n_steps", "part_map", "parts_missed", "parts_extra", "hardware_missed", "hardware_extra", "step_map", "steps_missed", "self_consistency_problems", "not_scored"):
        if k in r: print(f"{k:22s} {json.dumps(r[k])}")
    print("RESULT", "PASS" if r["pass"] else "FAIL")
    return 0 if r["pass"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
