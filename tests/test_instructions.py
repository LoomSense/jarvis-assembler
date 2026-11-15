"""Instruction text for manifest actions (pipeline/blender/instructions.py). No Blender needed."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from pipeline.blender.instructions import fasten_text, greek_noun, names, nu, text_for

ROOT = Path(__file__).resolve().parents[1]


def hw(code, kind, name, short, el):
    return {"code": code, "kind": kind, "name": name, "short": short, "el": el}


# MALM drawer, LACK, TEKNIK, KNAGGLIG hardware as their plans write it
CASES = [
    (hw("108560", "screw", "Short screw", "short screw", "κοντή βίδα"), 3, 8,
     "Take a short screw (108560) and screw it in here. Screw 3 of 8.",
     "Πάρε μια κοντή βίδα (108560) και βίδωσέ την εδώ. Βίδα 3 από 8."),
    (hw("118331", "bolt", "Cam bolt", "cam bolt", "βίδα έκκεντρου"), 1, 2,
     "Take a cam bolt (118331) and screw it in here. Cam bolt 1 of 2.",
     "Πάρε μια βίδα έκκεντρου (118331) και βίδωσέ την εδώ. Βίδα 1 από 2."),
    (hw("101345", "dowel", "Wooden dowel (short)", "wooden dowel", "ξύλινη καβίλια"), 1, 2,
     "Take a wooden dowel (101345) and push it into the hole here. Dowel 1 of 2.",
     "Πάρε μια ξύλινη καβίλια (101345) και σπρώξε τη μέσα στην τρύπα εδώ. Καβίλια 1 από 2."),
    (hw("120076", "cam", "Cam lock (small)", "cam lock", "κλειδαριά έκκεντρου"), 2, 2,
     "Take a cam lock (120076) and drop it into the round hole here, then turn it clockwise until it locks. "
     "Cam lock 2 of 2.",
     "Πάρε μια κλειδαριά έκκεντρου (120076) και βάλε τη στη στρογγυλή τρύπα εδώ, μετά γύρισέ τη δεξιόστροφα "
     "μέχρι να κλειδώσει. Κλειδαριά 2 από 2."),
    (hw("110519", "pin", "Plastic pin", "plastic pin", "πλαστική πρόκα"), 1, 2,
     "Take a plastic pin (110519) and press it in here. Pin 1 of 2.",
     "Πάρε μια πλαστική πρόκα (110519) και πίεσέ τη μέσα εδώ. Πρόκα 1 από 2."),
    (hw("115980", "screw", "Double-ended dowel screw", "double-ended screw", "τη διπλή βίδα"), 2, 4,
     "Take a double-ended screw (115980) and screw it in here by hand. Screw 2 of 4.",
     "Πάρε μια διπλή βίδα (115980) και βίδωσέ την εδώ με το χέρι. Βίδα 2 από 4."),
    (hw("1R", "pin", "Metal pin", "metal pin", "μεταλλικό πείρο"), 1, 4,
     "Take a metal pin (1R) and press it in here. Pin 1 of 4.",
     "Πάρε έναν μεταλλικό πείρο (1R) και πίεσέ τον μέσα εδώ. Πείρος 1 από 4."),
    (hw("42F", "dowel", "Wood dowel", "wood dowel", "ξύλινο καβιλάκι"), 1, 4,
     "Take a wood dowel (42F) and push it into the hole here. Dowel 1 of 4.",
     "Πάρε ένα ξύλινο καβιλάκι (42F) και σπρώξε το μέσα στην τρύπα εδώ. Καβιλάκι 1 από 4."),
    (hw("1F", "cam", "Hidden cam", "hidden cam", "έκκεντρο"), 1, 16,
     "Take a hidden cam (1F) and drop it into the round hole here, then turn it clockwise until it locks. Cam 1 of 16.",
     "Πάρε ένα έκκεντρο (1F) και βάλε το στη στρογγυλή τρύπα εδώ, μετά γύρισέ το δεξιόστροφα μέχρι να κλειδώσει. "
     "Έκκεντρο 1 από 16."),
    (hw("2F", "dowel", "Cam dowel", "cam dowel", "πείρο έκκεντρου"), 1, 16,
     "Take a cam dowel (2F) and screw it in here. Dowel 1 of 16.",
     "Πάρε έναν πείρο έκκεντρου (2F) και βίδωσέ τον εδώ. Πείρος 1 από 16."),
]


@pytest.mark.parametrize("d,i,n,en,el", CASES, ids=[c[0]["code"] for c in CASES])
def test_fasten_text(d, i, n, en, el):
    assert text_for("fasten", d, i, n) == {"en": en, "el": el}


def test_never_doubles_the_greek_article():
    t = fasten_text(hw("115980", "screw", "Double-ended dowel screw", "double-ended screw", "τη διπλή βίδα"), 1, 4)
    assert "μια τη" not in t["el"] and "μια την" not in t["el"]


def test_unknown_noun_falls_back_to_the_code():
    t = text_for("fasten", hw("999999", "gizmo", "Gizmo", "gizmo", "κάτι άγνωστο"), 1, 3)
    assert t["en"] == "Take a gizmo (999999) and put it in here. Piece 1 of 3."
    assert t["el"] == "Πάρε το εξάρτημα 999999 και βάλε το εδώ. Τεμάχιο 1 από 3."
    t = text_for("fasten", {"code": "123", "kind": "screw", "name": "Screw"}, 1, 2)   # no Greek name at all
    assert t["el"] == "Πάρε το εξάρτημα 123 και βίδωσέ το εδώ. Βίδα 1 από 2."


def test_press_flag_on_a_hardware_add():
    t = text_for("fasten", hw("5", "plug", "Plug", "plug", "κάτι"), 1, 1, press=True)
    assert "press it in here" in t["en"] and "πίεσέ το μέσα εδώ" in t["el"]


def test_greek_noun_gender_and_case():
    assert greek_noun("έναν μεταλλικός πείρος") == ("μεταλλικό πείρο", "m", "πείρος")
    assert greek_noun("ένα καρφάκι") == ("καρφάκι", "n", "καρφάκι")
    assert greek_noun("τάπα έκκεντρου")[1] == "f"
    assert greek_noun("το βίδα") is None                  # the plan's article disagrees with the noun
    assert greek_noun("") is None


def test_final_nu_rule():
    assert nu("Σφίξε καλά την μικρή βίδα") == "Σφίξε καλά τη μικρή βίδα"
    assert nu("Σφίξε καλά τη εξάγωνη βίδα") == "Σφίξε καλά την εξάγωνη βίδα"
    assert nu("βίδωσέ τη εδώ") == "βίδωσέ την εδώ"


def test_tighten_text():
    t = text_for("tighten", hw("115994", "screw", "Hex flange screw", "hex screw", "την εξάγωνη βίδα"), 1, 4)
    assert t == {"en": "Tighten the hex screw (115994) firmly. Screw 1 of 4.",
                 "el": "Σφίξε καλά την εξάγωνη βίδα (115994). Βίδα 1 από 4."}


def test_place_text_unchanged():
    d = {"name": "Drawer front", "short": "drawer front", "el": "την πρόσοψη του συρταριού", "el_pron": "την"}
    assert text_for("place", d, first=True) == {"en": "Lay the drawer front flat in front of you.",
                                                "el": "Ακούμπησε την πρόσοψη του συρταριού μπροστά σου."}
    assert names({"name": "Plain slat", "el": "μια απλή σανίδα"})["el"] == "απλή σανίδα"


def test_every_plan_in_the_repo_gets_text_with_its_own_code_and_count():
    plans = list(ROOT.glob("eval/golden/*/plan.json")) + list(ROOT.glob("eval/bakeoff/results/*/plan.json"))
    assert plans
    for path in plans:
        for h in json.loads(path.read_text())["hardware"]:
            t = text_for("fasten", h, 1, h["count"])
            for lang, counter in (("en", f" 1 of {h['count']}."), ("el", f" 1 από {h['count']}.")):
                assert t[lang].count(str(h["code"])) == 1 and t[lang].endswith(counter), (path, t)
            assert "screw it in" not in t["en"] or h["kind"] in ("screw", "bolt", "leveler", "dowel"), (path, t)
