"""Writes plan.json for the KNAGGLIG box (guide AA-1159337-7). This is the
hand-checked plan the agent's extraction is scored against, and the input the
3D pipeline is developed on. Every dimension is an estimate: the guide prints
none, so they are scaled from the published overall size 46 x 31 x 25 cm."""
import json
from pathlib import Path

GUIDE = "eval/guides/ikea/knagglig-box__AA-1159337-7.pdf"
dims = {"L": 460, "W": 310, "H": 250, "t": 12, "slat_h": 70, "row_gap": 17, "z0": 6, "bot_t": 10,
        "batten_w": 20, "batten_h": 20, "up_x": 20, "up_y": 28, "screw_dz": 18}
est = {"estimated": True, "basis": "scaled from published overall size 460 x 310 x 250 mm"}

parts = [
    {"id": "bottom_panel", "name": "Bottom panel (pre-assembled)", "count": 1, "script": "parts/bottom_panel.py",
     "material": "pine", "cite": {"page": 3, "label": None, "note": "step 1, lying flat"}, "dims": est},
    {"id": "side_panel", "name": "Long side panel (pre-assembled)", "count": 2, "script": "parts/side_panel.py",
     "material": "pine", "cite": {"page": 3, "label": None, "note": "step 1, standing"}, "dims": est},
    {"id": "end_slat", "name": "End slat", "count": 4, "script": "parts/end_slat.py",
     "material": "pine", "cite": {"page": 5, "label": None, "note": "step 5"}, "dims": est},
    {"id": "end_slat_handle", "name": "End slat with handle cut-out", "count": 2, "script": "parts/end_slat.py",
     "params": {"handle": True}, "material": "pine", "cite": {"page": 6, "label": None, "note": "step 6"}, "dims": est},
]
hardware = [
    {"code": "108363", "kind": "screw", "name": "Long screw", "count": 4, "script": "parts/screw.py",
     "params": {"length": 60, "shank_d": 4.5, "head_d": 9.0, "head_h": 3.5}, "material": "steel",
     "cite": {"page": 2, "label": "108363"}, "dims": est},
    {"code": "108560", "kind": "screw", "name": "Short screw", "count": 24, "script": "parts/screw.py",
     "params": {"length": 30, "shank_d": 3.5, "head_d": 7.0, "head_h": 2.8}, "material": "steel",
     "cite": {"page": 2, "label": "108560"}, "dims": est},
]

steps, used = [], {"108363": 0, "108560": 0}


def screws(code, targets):
    out = []
    for t in targets:
        used[code] += 1
        out.append({"instance": f"{code}#{used[code]}", "mates": [{"moving": "seat", "fixed": t}]})
    return out


def step(n, page, title, add, flip=False, view=(-55, 28)):  # view = camera azimuth, elevation
    hw = {}
    for a in add:
        code = a["instance"].split("#")[0]
        if code in used:
            hw[code] = hw.get(code, 0) + 1
    parts_added = [a["instance"].split("#")[0] for a in add if a["instance"].split("#")[0] not in used]
    steps.append({"n": n, "title": title, "cite": {"page": page, "label": str(n)}, "add": add,
                  "parts_added": parts_added,   # PLAN section 4.2 field, read by eval/score_plan.py
                  "hardware_used": hw, "flip_whole_assembly": flip, "view_az_el_deg": list(view)})


def panel(i, pilots):
    return {"instance": f"side_panel#{i}", "mates": [
        {"moving": "bolt_out_1", "fixed": f"bottom_panel#1.{pilots[0]}"},
        {"moving": "bolt_out_2", "fixed": f"bottom_panel#1.{pilots[1]}"}]}


def slat(inst, row, left, right):
    """left/right = (panel instance, end face) that the slat's L and R screw pairs land on."""
    m = []
    for side, (pi, e) in (("L", left), ("R", right)):
        for ab in "ab":
            m.append({"moving": f"out_{side}_{ab}", "fixed": f"{pi}.end{e}_r{row}{ab}"})
    return {"instance": inst, "mates": m}


def slat_screws(inst):
    return screws("108560", [f"{inst}.in_{s}_{ab}" for s in "LR" for ab in "ab"])


step(1, 3, "Stand a side panel against the end of the bottom panel",
     [{"instance": "bottom_panel#1", "base": True}, panel(1, ("pilot_A1", "pilot_A2"))], view=(125, 28))
step(2, 3, "Fix it with two long screws", screws("108363", ["side_panel#1.bolt_in_1", "side_panel#1.bolt_in_2"]), view=(-55, 20))
step(3, 4, "Turn it over and add the second side panel", [panel(2, ("pilot_B2", "pilot_B1"))], flip=True, view=(-55, 28))
step(4, 4, "Fix it with two long screws", screws("108363", ["side_panel#2.bolt_in_1", "side_panel#2.bolt_in_2"]), view=(125, 20))
E1 = (("side_panel#2", 2), ("side_panel#1", 1))   # end at x = 0
E2 = (("side_panel#1", 2), ("side_panel#2", 1))   # end at x = L
step(5, 5, "Stand the box on end and screw on two end slats",
     [slat("end_slat#1", 1, *E1), slat("end_slat#2", 2, *E1)] + slat_screws("end_slat#1") + slat_screws("end_slat#2"),
     view=(-140, 25))
step(6, 6, "Screw on the handle slat",
     [slat("end_slat_handle#1", 3, *E1)] + slat_screws("end_slat_handle#1"), view=(-140, 25))
step(7, 7, "Turn the box over and screw two end slats onto the other end",
     [slat("end_slat#3", 1, *E2), slat("end_slat#4", 2, *E2)] + slat_screws("end_slat#3") + slat_screws("end_slat#4"),
     flip=True, view=(-40, 25))
step(8, 8, "Screw on the second handle slat",
     [slat("end_slat_handle#2", 3, *E2)] + slat_screws("end_slat_handle#2"), view=(-40, 25))

plan = {"title": "KNAGGLIG box", "guide": GUIDE, "guide_id": "AA-1159337-7", "units": "mm",
        "overall_mm": {"L": 460, "W": 310, "H": 250, "estimated": False, "basis": "published product size"},
        "cover_view_az_el_deg": [-128, 24], "dims": dims, "parts": parts, "hardware": hardware, "steps": steps, "flags": []}
for h in hardware:
    assert used[h["code"]] == h["count"], (h["code"], used[h["code"]])
Path(__file__).with_name("plan.json").write_text(json.dumps(plan, indent=1))
print("steps", len(steps), "hardware used", used)
