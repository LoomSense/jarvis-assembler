"""Generates plan.json (kept so the plan can be regenerated; the pipeline only reads plan.json)."""
import json
from pathlib import Path

dims = {"L": 460, "W": 310, "H": 250, "t": 12, "post": 22, "batten_t": 12, "batten_w": 18,
        "side_slat_w": 70, "side_gap": 8, "end_slat_w": 74, "end_gap": 8,
        "end_screw_inset": 17, "end_screw_edge": 18}
est = {"estimated": True, "basis": "scaled from the 46 x 31 x 25 cm overall size and the drawings' proportions"}
parts = [
    {"id": "side_panel", "name": "Long side panel (pre-assembled: 3 slats on 2 corner posts)", "short": "side panel",
     "el": "το πλαϊνό πάνελ", "el_pron": "το", "count": 2, "script": "parts/side_panel.py", "params": {},
     "material": "pine", "cite": {"page": 3, "label": None, "note": "step 1, lying flat with the posts up"}, "dims": est},
    {"id": "bottom_panel", "name": "Bottom panel (pre-assembled: 4 boards on 2 battens)", "short": "bottom panel",
     "el": "το πάνελ του πάτου", "el_pron": "το", "count": 1, "script": "parts/bottom_panel.py", "params": {},
     "material": "pine", "cite": {"page": 3, "label": None, "note": "step 1, standing, pushed against the side panel"},
     "dims": est},
    {"id": "end_slat", "name": "End slat", "short": "end slat",
     "el": "τη σανίδα", "el_pron": "την", "count": 4, "script": "parts/end_slat.py", "params": {},
     "material": "pine", "cite": {"page": 5, "label": None, "note": "step 5, the two plain slats"}, "dims": est},
    {"id": "handle_slat", "name": "End slat with handle cut-out", "short": "slat with the handle",
     "el": "τη σανίδα με τη λαβή", "el_pron": "την", "count": 2, "script": "parts/handle_slat.py", "params": {},
     "material": "pine", "cite": {"page": 6, "label": None, "note": "step 6, the slat with the cut-out"}, "dims": est},
]
hardware = [
    {"code": "108363", "kind": "screw", "name": "Long pan-head screw", "short": "long screw", "el": "μακριά βίδα",
     "count": 4, "script": "parts/screw_pan.py", "params": {"length": 60, "shank_d": 4.5, "head_d": 9, "head_h": 3.5},
     "material": "steel", "cite": {"page": 2, "label": "108363"}},
    {"code": "108560", "kind": "screw", "name": "Short countersunk screw", "short": "short screw", "el": "κοντή βίδα",
     "count": 24, "script": "parts/screw_csk.py", "params": {"length": 30, "shank_d": 3.5, "head_d": 8, "head_h": 3},
     "material": "steel", "cite": {"page": 2, "label": "108560"}},
]

ns = {}


def nxt(k):
    ns[k] = ns.get(k, 0) + 1
    return f"{k}#{ns[k]}"


def long_screws(names):
    return [{"instance": nxt("108363"), "mates": [{"moving": "seat", "fixed": f"bottom_panel#1.hole_{n}"}]}
            for n in names]


ORDER = ["L_lo", "L_hi", "R_hi", "R_lo"]          # around the slat, as a person would


def slat(pid, row, end):
    """row 0..2 counted from the bottom panel; end 'near' = box end at X 0, 'far' = box end at X L."""
    inst = nxt(pid)
    if end == "near":
        lp, rp = "side_panel#1.endface_A", "side_panel#2.endface_B"
    else:
        lp, rp = "side_panel#2.endface_A", "side_panel#1.endface_B"
    lo, hi = 2 * row + 1, 2 * row + 2
    add = {"instance": inst, "mates": [{"moving": "in_L_lo", "fixed": f"{lp}_{lo}"},
                                       {"moving": "in_L_hi", "fixed": f"{lp}_{hi}"},
                                       {"moving": "in_R_hi", "fixed": f"{rp}_{hi}"},
                                       {"moving": "in_R_lo", "fixed": f"{rp}_{lo}"}]}
    screws = [{"instance": nxt("108560"), "mates": [{"moving": "seat", "fixed": f"{inst}.hole_{o}"}]} for o in ORDER]
    return add, screws


def slats_step(end, rows, pid):
    adds, screws = [], []
    for r in rows:
        a, s = slat(pid, r, end)
        adds.append(a)
        screws += s
    return adds + screws


NONE = {"en": "", "el": ""}
P12 = "side panel flat on the floor with its posts up, bottom panel standing on its long edge"
P34 = "upside down: bottom panel on top with its battens up"
P56 = "standing on one end, underside of the bottom panel facing the viewer"
P78 = "standing on the other end, underside of the bottom panel facing the viewer"
steps = [
    {"n": 1, "title": "Lay a side panel flat, posts up, and push the bottom panel against the ends of its posts",
     "cite": {"page": 3, "label": "1"},
     "add": [{"instance": "side_panel#1", "base": True},
             {"instance": "bottom_panel#1", "mates": [{"moving": "seat_A1", "fixed": "side_panel#1.post_end_1"},
                                                      {"moving": "seat_A2", "fixed": "side_panel#1.post_end_2"}]}],
     "parts_added": ["side_panel", "bottom_panel"], "hardware_used": {},
     "pose": P12, "assembly_rotation_deg": [90, 0, 0], "turn_text": NONE,
     "flip_whole_assembly": False, "view_az_el_deg": [22, 25]},
    {"n": 2, "title": "Screw the bottom panel to the two posts with two long screws",
     "cite": {"page": 3, "label": "2"}, "add": long_screws(["A2", "A1"]),
     "parts_added": [], "hardware_used": {"108363": 2},
     "pose": P12, "assembly_rotation_deg": [90, 0, 0], "turn_text": NONE,
     "flip_whole_assembly": False, "view_az_el_deg": [22, 25]},
    {"n": 3, "title": "Turn the assembly so the bottom panel is on top and fit the second side panel under it",
     "cite": {"page": 4, "label": "3"},
     "add": [{"instance": "side_panel#2", "mates": [{"moving": "post_end_1", "fixed": "bottom_panel#1.seat_B1"},
                                                    {"moving": "post_end_2", "fixed": "bottom_panel#1.seat_B2"}]}],
     "parts_added": ["side_panel"], "hardware_used": {},
     "pose": P34, "assembly_rotation_deg": [180, 0, 0],
     "turn_text": {"en": "Turn the assembly so the side panel stands up and the bottom panel is on top.",
                   "el": "Γύρισε την κατασκευή ώστε το πλαϊνό πάνελ να στέκεται όρθιο και ο πάτος να είναι από πάνω."},
     "flip_whole_assembly": True, "view_az_el_deg": [18, 24]},
    {"n": 4, "title": "Screw the bottom panel to the second side panel's posts with two long screws, from above",
     "cite": {"page": 4, "label": "4"}, "add": long_screws(["B2", "B1"]),
     "parts_added": [], "hardware_used": {"108363": 2},
     "pose": P34, "assembly_rotation_deg": [180, 0, 0], "turn_text": NONE,
     "flip_whole_assembly": False, "view_az_el_deg": [18, 24]},
    {"n": 5, "title": "Stand the box on one end and screw two end slats to the posts, flush with the side panels",
     "cite": {"page": 5, "label": "5"}, "add": slats_step("near", [0, 1], "end_slat"),
     "parts_added": ["end_slat", "end_slat"], "hardware_used": {"108560": 8},
     "pose": P56, "assembly_rotation_deg": [0, 90, 0],
     "turn_text": {"en": "Stand the box on one end, with the underside of the bottom panel facing you.",
                   "el": "Στήσε το κουτί όρθιο στη μία του άκρη, με το κάτω μέρος του πάτου προς το μέρος σου."},
     "flip_whole_assembly": True, "view_az_el_deg": [160, 27]},
    {"n": 6, "title": "Screw on the slat with the handle, cut-out towards the other two slats",
     "cite": {"page": 6, "label": "6"}, "add": slats_step("near", [2], "handle_slat"),
     "parts_added": ["handle_slat"], "hardware_used": {"108560": 4},
     "pose": P56, "assembly_rotation_deg": [0, 90, 0], "turn_text": NONE,
     "flip_whole_assembly": False, "view_az_el_deg": [160, 27]},
    {"n": 7, "title": "Turn the box onto its other end and screw two end slats to the posts",
     "cite": {"page": 7, "label": "7"}, "add": slats_step("far", [0, 1], "end_slat"),
     "parts_added": ["end_slat", "end_slat"], "hardware_used": {"108560": 8},
     "pose": P78, "assembly_rotation_deg": [0, -90, 0],
     "turn_text": {"en": "Turn the box over onto its other end.",
                   "el": "Γύρισε το κουτί ανάποδα, ώστε να πατάει στην άλλη του άκρη."},
     "flip_whole_assembly": True, "view_az_el_deg": [-20, 27]},
    {"n": 8, "title": "Screw on the second slat with the handle, cut-out towards the other two slats",
     "cite": {"page": 8, "label": "8"}, "add": slats_step("far", [2], "handle_slat"),
     "parts_added": ["handle_slat"], "hardware_used": {"108560": 4},
     "pose": P78, "assembly_rotation_deg": [0, -90, 0], "turn_text": NONE,
     "flip_whole_assembly": False, "view_az_el_deg": [-20, 27]},
]
flags = [
    {"ref": "guide", "kind": "input_mismatch",
     "message": "FACTS.md says the numbered steps are on pages 3-6 and pages 7-8 are back matter, but pages 7 and 8 "
                "carry numbered steps 7 and 8 (steps 5 and 6 repeated on the other end, after the turn arrow on "
                "page 6). The plan follows the guide: 8 steps. Steps 1-6 alone use only 12 of the 24 screws 108560."},
    {"ref": "step:1", "kind": "unclear_drawing",
     "message": "Read as: the lying 3-slat panel is a long side (slats down, posts up) and the standing 4-board "
                "panel with two battens is the bottom, battens outside as feet. The long screws 108363 pass through "
                "batten and board into the end grain of the posts."},
    {"ref": "step:3", "kind": "animation_limit",
     "message": "The guide slides the second side panel in sideways under the bottom panel. The animation moves a "
                "part along its mate axis, so here the panel arrives along its posts (vertically) instead."},
    {"ref": "step:5", "kind": "unclear_drawing",
     "message": "The info box shows the end slats flush with the outer faces of the side panels, so they are "
                "modelled full width, covering the ends of the side slats. How far they reach down over the ends of "
                "the bottom boards cannot be read from the drawing; modelled down to the boards' underside."},
    {"ref": "step:5", "kind": "estimated_position",
     "message": "Screws 108560 are placed 17 mm from the slat ends, off the post centre line, so they clear the "
                "long screw 108363 inside the post. The guide gives no hole positions."},
    {"ref": "parts", "kind": "estimated_dims",
     "message": "The guide has no dimensions: 12 mm boards, 22 mm square posts, slat widths, gaps and screw sizes "
                "are estimates scaled from 46 x 31 x 25 cm."},
]
plan = {"title": "KNAGGLIG box", "guide_id": "AA-1159337-7", "units": "mm",
        "overall_mm": {"L": 460, "W": 310, "H": 250}, "cover_view_az_el_deg": [-135, 22], "dims": dims,
        "parts": parts, "hardware": hardware, "steps": steps,
        "final_turn_text": {"en": "Turn the box the right way up.", "el": "Γύρισε το κουτί στην κανονική του θέση."},
        "flags": flags}
Path(__file__).with_name("plan.json").write_text(json.dumps(plan, indent=1, ensure_ascii=False))
