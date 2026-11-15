"""Turn the plan's mates into a world transform for every part instance.

    python -m pipeline.assemble.placement <plan.json> <parts_dir> <out placements.json>

The plan says WHICH features mate; this code works out WHERE each part ends up.
Two frames mate when their origins coincide and their axes are opposite.
For every instance it reports the residual (how far the mated points are from
coinciding). A residual above the tolerance means the parts as modelled do not
fit together, and that is reported per mate so the part scripts can be fixed.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

ARM = 50.0            # mm: lever arm used to turn an axis into a second point
TOL_MM = 0.5          # max distance between mated origins
TOL_DEG = 2.0         # max angle between a mated axis and its target


def kabsch(P: np.ndarray, Q: np.ndarray):
    """Rigid transform (R, t) minimising |R P + t - Q|."""
    cp, cq = P.mean(0), Q.mean(0)
    H = (P - cp).T @ (Q - cq)
    U, _, Vt = np.linalg.svd(H)
    d = np.sign(np.linalg.det(Vt.T @ U.T))
    R = Vt.T @ np.diag([1.0, 1.0, d]) @ U.T
    return R, cq - R @ cp


def rot_between(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Smallest rotation taking unit vector a onto unit vector b."""
    v, c = np.cross(a, b), float(a @ b)
    if np.linalg.norm(v) < 1e-9:
        if c > 0:
            return np.eye(3)
        p = np.cross(a, [1.0, 0, 0]) if abs(a[0]) < 0.9 else np.cross(a, [0, 1.0, 0])
        p /= np.linalg.norm(p)
        return 2 * np.outer(p, p) - np.eye(3)          # half turn about a perpendicular
    K = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + K + K @ K * (1 / (1 + c))


def _fit(om, am, of, af):
    """Transform taking the moving frames (origins om, axes am) onto the fixed ones (of, af)."""
    if len(om) == 1:                                    # one mate: free to spin about its axis (fasteners)
        R = rot_between(am[0], -af[0]); t = of[0] - R @ om[0]
    else:
        R, t = kabsch(np.vstack([om, om + ARM * am]), np.vstack([of, of - ARM * af]))
    d = np.linalg.norm((om @ R.T + t) - of, axis=1)
    a = np.degrees(np.arccos(np.clip(np.sum((am @ R.T) * -af, axis=1), -1, 1)))
    approach = af.mean(0)
    n = np.linalg.norm(approach)
    return R, t, d, a, (approach / n if n > 1e-6 else af[0])


def _matrix(R, t):
    M = np.eye(4); M[:3, :3] = R; M[:3, 3] = t
    return M


def solve(plan: dict, parts_dir: Path) -> dict:
    """Place every instance. Sub-assemblies (plan `subassemblies`) are built in their own frame: an add with
    `"into": "<id>"` goes into that sub-assembly, whose `base` instance is its origin; an add with
    `"subassembly": "<id>"` and `mates` (`moving` = `<instance>.<frame>` inside it) joins it, all its pieces at
    once, to the main assembly. Matrices in the output are always final, in the main assembly's frame."""
    info = {p.stem.replace(".mates", ""): json.loads(p.read_text()) for p in parts_dir.glob("*.mates.json")}
    hardware = {h["code"] for h in plan["hardware"]}
    groups = {g["id"] for g in plan.get("subassemblies", [])}
    # every placed instance: (space, R, t) where space is "main" or a sub-assembly id not yet joined
    pose: dict[str, tuple[str, np.ndarray, np.ndarray]] = {}
    local: dict[str, np.ndarray] = {}                   # pose inside its sub-assembly, kept after the join
    out, errors, joined = {}, [], {}

    def frame_at(ref: str, space: str | None = None):
        inst, _, name = ref.partition(".")
        if inst not in pose:
            raise KeyError(f"`{ref}`: {inst} is not placed yet (steps out of order?)")
        sp, R, t = pose[inst]
        if space is not None and sp != space:
            where = "the main assembly" if sp == "main" else f"sub-assembly `{sp}`"
            raise KeyError(f"`{ref}`: {inst} is in {where}, not in the same one as the new piece")
        fr = info[inst.split("#")[0]]["frames"].get(name)
        if fr is None:
            raise KeyError(f"`{ref}`: part has no frame `{name}`")
        return R @ np.array(fr["origin"]) + t, R @ np.array(fr["axis"])

    def report(step, label, mates, d, a):
        for m, di, ai in zip(mates, d, a):
            if di > TOL_MM or ai > TOL_DEG:
                errors.append(f"step {step['n']} {label}: `{m['moving']}` misses `{m['fixed']}` by {di:.2f} mm / {ai:.1f} deg")

    for step in plan["steps"]:
        for add in step["add"]:
            if "subassembly" in add and "instance" not in add:          # join a whole sub-assembly
                g = add["subassembly"]
                members = [i for i, (sp, _, _) in pose.items() if sp == g]
                if g not in groups or not members:
                    errors.append(f"step {step['n']}: sub-assembly `{g}` is not declared or has no pieces yet")
                    continue
                try:
                    mv = [frame_at(m["moving"], g) for m in add["mates"]]
                    fx = [frame_at(m["fixed"], "main") for m in add["mates"]]
                except KeyError as e:
                    errors.append(f"step {step['n']} join {g}: {e}")
                    continue
                R, t, d, a, approach = _fit(np.array([v[0] for v in mv]), np.array([v[1] for v in mv]),
                                            np.array([v[0] for v in fx]), np.array([v[1] for v in fx]))
                report(step, f"join {g}", add["mates"], d, a)
                for i in members:
                    _, Ri, ti = pose[i]
                    pose[i] = ("main", R @ Ri, R @ ti + t)
                joined[g] = {"step": step["n"], "matrix_mm": np.round(_matrix(R, t), 6).tolist(),
                             "approach": np.round(approach, 6).tolist(), "instances": members,
                             "residual_mm": round(float(d.max()), 4)}
                continue
            inst = add["instance"]
            pid = inst.split("#")[0]
            space = add.get("into", "main")
            if space != "main" and (space not in groups or space in joined):
                errors.append(f"step {step['n']} {inst}: `into` names `{space}`, which is not an open sub-assembly")
                continue
            if pid not in info:
                errors.append(f"step {step['n']}: no built part `{pid}`")
                continue
            frames = info[pid]["frames"]
            if add.get("base"):
                R, t, resid, ang, approach = np.eye(3), np.zeros(3), 0.0, 0.0, np.array([0.0, 0, 1])
            else:
                try:
                    pairs = [(frames[m["moving"]], frame_at(m["fixed"], space), m) for m in add["mates"]]
                except KeyError as e:
                    errors.append(f"step {step['n']} {inst}: {e}")
                    continue
                R, t, d, a, approach = _fit(np.array([p[0]["origin"] for p in pairs]), np.array([p[0]["axis"] for p in pairs]),
                                            np.array([p[1][0] for p in pairs]), np.array([p[1][1] for p in pairs]))
                report(step, inst, [p[2] for p in pairs], d, a)
                resid, ang = float(d.max()), float(a.max())
            pose[inst] = (space, R, t)
            if space != "main":
                local[inst] = _matrix(R, t)
            out[inst] = {"part": pid, "kind": "hardware" if pid in hardware else "part", "step": step["n"],
                         "residual_mm": round(resid, 4), "axis_error_deg": round(ang, 3),
                         "approach": np.round(approach, 6).tolist()}
            if space != "main":
                out[inst]["subassembly"] = space
    for g in groups - set(joined):
        if any(sp == g for sp, _, _ in pose.values()):
            errors.append(f"sub-assembly `{g}` is built but never joined to the main assembly")
    for inst, (sp, R, t) in pose.items():
        out[inst]["matrix_mm"] = np.round(_matrix(R, t), 6).tolist()
        if inst in local:
            out[inst]["local_mm"] = np.round(local[inst], 6).tolist()
    return {"instances": out, "subassemblies": joined, "errors": errors, "tolerance": {"mm": TOL_MM, "deg": TOL_DEG}}


if __name__ == "__main__":
    plan_path, parts_dir, dest = map(Path, sys.argv[1:4])
    res = solve(json.loads(plan_path.read_text()), parts_dir)
    dest.write_text(json.dumps(res, indent=1))
    worst = max((v["residual_mm"] for v in res["instances"].values()), default=0)
    print("RESULT " + json.dumps({"instances": len(res["instances"]), "worst_residual_mm": worst, "errors": res["errors"]}))
    sys.exit(1 if res["errors"] else 0)
