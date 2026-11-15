"""POST /api/perception/{job_id}/panel: the drawer front on the table from one frame. No network, no model.

The frames are the committed renders in tools/markerless/sample (ground truth in frames.jsonl). The detector
is only measured on renders: these tests pin the route and the numbers in tools/markerless/FRONT_PANEL.md.
"""
from __future__ import annotations

import base64
import json
import math
from pathlib import Path

import pytest

from tests.test_vision import app_client, jpeg_b64

SAMPLE = Path(__file__).resolve().parent.parent / "tools" / "markerless" / "sample"
FRAMES = {r["id"]: r for r in map(json.loads, (SAMPLE / "frames.jsonl").read_text().splitlines())}


@pytest.fixture()
def client(tmp_path):
    with app_client(tmp_path) as c:
        c.job = c.app.state.store.create(b"%PDF-1.4\n", "malm-desk-drawer.pdf")
        yield c


def body(frame_id: str, **extra) -> dict:
    """The request an app would send for a sample frame. The renders' world IS the table frame (z up, table at
    z = 0), so `T_world_cam` is `cam_in_table`."""
    f = FRAMES[frame_id]
    K = f["K"]
    return {"image": base64.b64encode((SAMPLE / "img" / f"{frame_id}.jpg").read_bytes()).decode(),
            "fx": K[0][0], "fy": K[1][1], "cx": K[0][2], "cy": K[1][2],
            "cam_in_table": [v for row in f["T_world_cam"] for v in row], **extra}


def errors(out: dict, frame_id: str) -> tuple[float, float]:
    gx, gy, gyaw = FRAMES[frame_id]["obj_xy_yaw"]
    dyaw = (out["yaw"] - gyaw) % math.pi
    return 1000 * math.hypot(out["x"] - gx, out["y"] - gy), math.degrees(min(dyaw, math.pi - dyaw))


@pytest.mark.parametrize("frame_id", ["s00_f010", "s02_f012"])          # office light, dim lamp
def test_trusted_pose_of_the_panel_alone(client, frame_id):
    r = client.post(f"/api/perception/{client.job.id}/panel", json=body(frame_id))
    assert r.status_code == 200, r.text
    out = r.json()
    assert set(out) == {"trusted", "reason", "x", "y", "yaw", "corners_px", "ms"}
    assert out["trusted"] is True and out["reason"] == "ok"
    mm, deg = errors(out, frame_id)
    assert mm < 10 and deg < 2, (mm, deg)                                # stated: p50 4.6 mm, p90 10.8 mm, 0.4 deg
    assert 0 <= out["yaw"] < math.pi
    assert len(out["corners_px"]) == 4 and all(0 <= u < 1280 and 0 <= v < 960 for u, v in out["corners_px"])


def test_prev_yaw_picks_the_nearer_of_the_two_orientations(client):
    first = client.post(f"/api/perception/{client.job.id}/panel", json=body("s00_f010")).json()
    flipped = client.post(f"/api/perception/{client.job.id}/panel",
                          json=body("s00_f010", prev_yaw=first["yaw"] + math.pi - 0.2)).json()
    assert flipped["trusted"] and flipped["yaw"] == pytest.approx(first["yaw"] + math.pi)
    assert (flipped["x"], flipped["y"]) == pytest.approx((first["x"], first["y"]))


@pytest.mark.parametrize("frame_id", ["s00_f023",                        # a hand and forearm over the panel
                                      "s07_f010",                        # the box of step 4, not the panel alone
                                      "s04_f020"])                       # step 2: the sides stand on the front
def test_untrusted_when_it_is_not_a_clean_panel(client, frame_id):
    out = client.post(f"/api/perception/{client.job.id}/panel", json=body(frame_id)).json()
    assert out["trusted"] is False and out["reason"] != "ok"


def test_untrusted_without_a_table_in_view(client):
    b = body("s00_f010")
    b["image"] = jpeg_b64(120, size=(1280, 960))                         # a flat grey picture: nothing white
    out = client.post(f"/api/perception/{client.job.id}/panel", json=b).json()
    assert out["trusted"] is False
    looking_up = body("s00_f010")
    looking_up["cam_in_table"] = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0.5, 0, 0, 0, 1]   # optical axis = +z, away
    out = client.post(f"/api/perception/{client.job.id}/panel", json=looking_up).json()
    assert out == {"trusted": False, "reason": "camera not looking down at the table", "x": None, "y": None,
                   "yaw": None, "corners_px": None, "ms": out["ms"]}


def test_validation_and_auth(client):
    url = f"/api/perception/{client.job.id}/panel"
    assert client.post(url, json=body("s00_f010"), headers={"X-Demo-Key": "wrong"}).status_code == 401
    assert client.post("/api/perception/no-such-job/panel", json=body("s00_f010")).status_code == 404
    assert client.post(url, json={}).status_code == 422
    assert client.post(url, json={**body("s00_f010"), "image": "not base64!!"}).status_code == 422
    assert client.post(url, json={**body("s00_f010"), "fx": 0}).status_code == 422
    assert client.post(url, json={**body("s00_f010"), "cam_in_table": [1.0] * 15}).status_code == 422
    good = body("s00_f010")["cam_in_table"]
    column_major = [good[c * 4 + r] for r in range(4) for c in range(4)]     # what Unity's Matrix4x4 gives by index
    r = client.post(url, json={**body("s00_f010"), "cam_in_table": column_major})
    assert r.status_code == 422 and "column-major" in r.json()["detail"]
    scaled = [v * 2 if i in (0, 1, 2, 4, 5, 6, 8, 9, 10) else v for i, v in enumerate(good)]
    assert client.post(url, json={**body("s00_f010"), "cam_in_table": scaled}).status_code == 422
