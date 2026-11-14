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


def solve(plan: dict, parts_dir: Path) -> dict:
    info = {p.stem.replace(".mates", ""): json.loads(p.read_text()) for p in parts_dir.glob("*.mates.json")}
    hardware = {h["code"] for h in plan["hardware"]}
    world: dict[str, tuple[np.ndarray, np.ndarray]] = {}
    out, errors = {}, []

    def frame_world(ref: str):
        inst, name = ref.split(".")
        if inst not in world:
            raise KeyError(f"`{ref}`: {inst} is not placed yet (steps out of order?)")
        fr = info[inst.split("#")[0]]["frames"].get(name)
        if fr is None:
            raise KeyError(f"`{ref}`: part has no frame `{name}`")
        R, t = world[inst]
        return R @ np.array(fr["origin"]) + t, R @ np.array(fr["axis"])

    for step in plan["steps"]:
        for add in step["add"]:
            inst = add["instance"]
            pid = inst.split("#")[0]
            if pid not in info:
                errors.append(f"step {step['n']}: no built part `{pid}`")
                continue
            frames = info[pid]["frames"]
            if add.get("base"):
                R, t, resid, ang, approach = np.eye(3), np.zeros(3), 0.0, 0.0, np.array([0.0, 0, 1])
            else:
                try:
                    pairs = [(frames[m["moving"]], frame_world(m["fixed"]), m) for m in add["mates"]]
                except KeyError as e:
                    errors.append(f"step {step['n']} {inst}: {e}")
                    continue
                om = np.array([p[0]["origin"] for p in pairs]); am = np.array([p[0]["axis"] for p in pairs])
                of = np.array([p[1][0] for p in pairs]); af = np.array([p[1][1] for p in pairs])
                if len(pairs) == 1:                     # fasteners: free to spin about their axis
                    R = rot_between(am[0], -af[0]); t = of[0] - R @ om[0]
                else:
                    R, t = kabsch(np.vstack([om, om + ARM * am]), np.vstack([of, of - ARM * af]))
                d = np.linalg.norm((om @ R.T + t) - of, axis=1)
                cosang = np.clip(np.sum((am @ R.T) * -af, axis=1), -1, 1)
                a = np.degrees(np.arccos(cosang))
                resid, ang = float(d.max()), float(a.max())
                for (_, _, m), di, ai in zip(pairs, d, a):
                    if di > TOL_MM or ai > TOL_DEG:
                        errors.append(f"step {step['n']} {inst}: `{m['moving']}` misses `{m['fixed']}` "
                                      f"by {di:.2f} mm / {ai:.1f} deg")
                approach = af.mean(0)
                n = np.linalg.norm(approach)
                approach = approach / n if n > 1e-6 else af[0]
            world[inst] = (R, t)
            M = np.eye(4); M[:3, :3] = R; M[:3, 3] = t
            out[inst] = {"part": pid, "kind": "hardware" if pid in hardware else "part", "step": step["n"],
                         "matrix_mm": np.round(M, 6).tolist(), "residual_mm": round(resid, 4),
                         "axis_error_deg": round(ang, 3), "approach": np.round(approach, 6).tolist()}
    return {"instances": out, "errors": errors, "tolerance": {"mm": TOL_MM, "deg": TOL_DEG}}


if __name__ == "__main__":
    plan_path, parts_dir, dest = map(Path, sys.argv[1:4])
    res = solve(json.loads(plan_path.read_text()), parts_dir)
    dest.write_text(json.dumps(res, indent=1))
    worst = max((v["residual_mm"] for v in res["instances"].values()), default=0)
    print("RESULT " + json.dumps({"instances": len(res["instances"]), "worst_residual_mm": worst, "errors": res["errors"]}))
    sys.exit(1 if res["errors"] else 0)
