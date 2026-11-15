"""POST /api/perception/{job_id}/panel: where the drawer front lies on the table, from one camera frame.

No model call and no marker: plain image processing (server/perception/panel.py), for the native apps that
have no OpenCV. The caller sends the picture, the intrinsics of that picture and the camera pose in the table
frame; the reply is the panel's pose on the table and its four corners in the picture. Conventions and an
example are in server/perception/README.md.
"""
from __future__ import annotations

import math
import time

import cv2
import numpy as np
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from server.auth import require_key
from server.perception.imaging import MAX_IMAGE_BYTES
from server.perception.panel import detect_front_panel
from server.routes.identify import read_image

router = APIRouter()


class PanelBody(BaseModel):
    image: str = Field(min_length=4, max_length=MAX_IMAGE_BYTES * 4 // 3 + 64)   # base64 JPEG, no data: prefix
    fx: float = Field(gt=0)
    fy: float = Field(gt=0)
    cx: float
    cy: float
    cam_in_table: list[float] = Field(min_length=16, max_length=16)   # 4x4 row-major, camera pose in the table frame
    prev_yaw: float | None = None                                     # radians; picks between yaw and yaw + pi


def _pose(values: list[float]) -> np.ndarray:
    """The 4x4 rigid transform, or 422 with what is wrong (a column-major matrix is the usual mistake)."""
    if not all(math.isfinite(v) for v in values):
        raise HTTPException(422, "cam_in_table must be 16 finite numbers")
    T = np.array(values, dtype=np.float64).reshape(4, 4)
    if not np.allclose(T[3], [0, 0, 0, 1], atol=1e-4):
        raise HTTPException(422, "cam_in_table must be row-major with last row 0 0 0 1 (is it column-major?)")
    R = T[:3, :3]
    if not np.allclose(R @ R.T, np.eye(3), atol=1e-3) or np.linalg.det(R) < 0:
        raise HTTPException(422, "cam_in_table rotation must be orthonormal and right-handed (metres, no scale)")
    return T


def _detect(jpeg: bytes, body: PanelBody, T: np.ndarray) -> dict:
    img = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise HTTPException(422, "image is not a readable JPEG")
    K = np.array([[body.fx, 0, body.cx], [0, body.fy, body.cy], [0, 0, 1]], dtype=np.float64)
    t0 = time.perf_counter()
    r = detect_front_panel(img, K, T, np.eye(4), prev_yaw=body.prev_yaw)
    ms = (time.perf_counter() - t0) * 1e3
    corners = r.get("corners_px")
    return {"trusted": bool(r["trusted"]), "reason": r["reason"],
            "x": r.get("x"), "y": r.get("y"), "yaw": r.get("yaw"),
            "corners_px": [[round(u, 2), round(v, 2)] for u, v in corners] if corners else None,
            "ms": round(ms, 1)}


@router.post("/api/perception/{job_id}/panel", dependencies=[Depends(require_key)])
async def panel(job_id: str, body: PanelBody, request: Request) -> dict:
    """`{trusted, reason, x, y, yaw, corners_px: [[u, v] x 4] | null, ms}`. x, y (metres) and yaw (radians, about
    the table normal, known modulo pi) are the panel centre and its 380 mm axis in the table frame. Use the pose
    only when `trusted` is true. 404 unknown job, 413 image too large, 422 bad image or pose."""
    if request.app.state.store.get(job_id) is None:
        raise HTTPException(404, "No such job")
    jpeg = read_image(body.image)
    T = _pose(body.cam_in_table)
    return await run_in_threadpool(_detect, jpeg, body, T)
