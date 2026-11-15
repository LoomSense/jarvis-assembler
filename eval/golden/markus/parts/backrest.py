"""Backrest with headrest and steel mounting bracket (one pre-assembled piece), guide pages 12-14 (steps 7-9).
Local frame = seat sub-assembly frame: the bracket plate's front face at y=back_y (touches the mechanism's rear
face), seat front towards -Y."""
from pipeline.cad.lib import curved_panel, cushion, board, drill, union, frame

PARAMS = {"back_y": 260, "back_w": 480, "back_h": 850, "back_z0": 30}


def build(p):
    by = p["back_y"]
    panel = curved_panel(p["back_w"], p["back_h"], 50, 25, p["back_z0"]).translate((0, 270, 0))
    head = cushion(0, 236, p["back_z0"] + p["back_h"] - 150, 320, 52, 140, corner=30, crown=25)
    plate = board(-40, 150, -78, 80, by + 30 - 150, 8, chamfer=0)        # under the mechanism's rear arm
    strut = board(-40, by + 20, -78, 80, 10, 140, chamfer=0)               # up into the back panel
    solid = union(panel, head, plate, strut)
    frames = {}
    for i, (x, y) in enumerate(((-22, 230), (22, 230), (0, 180)), 1):
        solid = drill(solid, (x, y, -78), (0, 0, -1), 6.5, 9)
        frames[f"mech_{i}"] = frame((x, y, -70), (0, 0, 1))
        frames[f"scr_{i}"] = frame((x, y, -78), (0, 0, -1))
    return solid, frames
