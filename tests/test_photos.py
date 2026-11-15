"""Photo uploads: phone photos of a paper guide become guide.pdf (server/photos.py)."""
from __future__ import annotations

import io
import json

import cv2
import numpy as np
import pymupdf
import pytest
from PIL import Image

from server import photos
from tests.test_server import GOLDEN, client, wait_for  # noqa: F401  (client is a fixture)


def page_photo(seed: int, fmt: str = "JPEG", exif_orientation: int | None = 6) -> bytes:
    """A white A4-ish page with black line drawings, skewed in perspective on a grey table."""
    rng = np.random.default_rng(seed)
    page = np.full((1400, 1000, 3), 250, np.uint8)
    for _ in range(12):
        p1, p2 = rng.integers(80, 920, 2), rng.integers(80, 1320, 2)
        cv2.line(page, (int(p1[0]), int(p2[0])), (int(p1[1]), int(p2[1])), (20, 20, 20), 4)
    cv2.rectangle(page, (300, 400), (700, 900), (0, 0, 0), 6)
    src = np.float32([[0, 0], [999, 0], [999, 1399], [0, 1399]])
    dst = np.float32([[260, 140], [1180, 220], [1240, 1500], [180, 1440]])
    photo = cv2.warpPerspective(page, cv2.getPerspectiveTransform(src, dst), (1400, 1640),
                                borderMode=cv2.BORDER_CONSTANT, borderValue=(110, 105, 100))
    photo = cv2.GaussianBlur(photo, (3, 3), 0)
    img = Image.fromarray(photo)
    if exif_orientation:            # stored on its side, the tag says "rotate 90° to view"
        img = img.transpose(Image.Transpose.ROTATE_90)
    exif = Image.Exif()
    if exif_orientation:
        exif[0x0112] = exif_orientation
    buf = io.BytesIO()
    img.save(buf, fmt, exif=exif.tobytes()) if fmt == "JPEG" else img.save(buf, fmt)
    return buf.getvalue()


def test_photo_is_turned_upright_flattened_and_cleaned():
    img = photos.open_photo(page_photo(1), "Photo 1")
    assert img.height > img.width                                # the EXIF tag was applied
    cleaned, found = photos.prepare(img)
    assert found
    h, w = cleaned.shape
    assert 1.2 < h / w < 1.6                                     # roughly the page's own proportions
    assert np.median(cleaned) > 240 and cleaned.min() < 40       # white paper, black ink
    assert np.mean(cleaned[: h // 20]) > 200                     # no grey table left at the edge


def test_three_photos_become_a_three_page_guide(client, tmp_path):
    files = [("pages", (f"IMG_{i}.jpg", page_photo(i), "image/jpeg")) for i in range(3)]
    r = client.post("/api/jobs", files=files, data={"size": "40 x 30 x 20 cm"})
    assert r.status_code == 200, r.text
    job_id = r.json()["job_id"]
    job_dir = tmp_path / "jobs" / job_id
    assert pymupdf.open(job_dir / "guide.pdf").page_count == 3
    assert sorted(p.name for p in (job_dir / "photos").iterdir()) == ["page-001.jpg", "page-002.jpg", "page-003.jpg"]
    assert (job_dir / "photos" / "page-002.jpg").read_bytes() == files[1][1][1]
    status = json.loads((job_dir / "status.json").read_text())
    assert status["source"] == "photos" and status["pages_uploaded"] == 3 and status["pages_flattened"] == 3
    assert status["size"] == "40 x 30 x 20 cm"
    # Ingest rasterises them like any guide; without a model key the plan step then stops.
    wait_for(client, job_id, lambda s: s["status"] in ("error", "waiting_approval"))
    index = json.loads((job_dir / "pages" / "index.json").read_text())
    assert len(index["pages"]) == 3 and max(index["pages"][0]["width"], index["pages"][0]["height"]) <= 2401
    assert client.get(f"/api/jobs/{job_id}").json()["source"] == "photos"


def test_one_png_in_file_works(client, tmp_path):
    png = page_photo(7, fmt="PNG", exif_orientation=None)
    r = client.post("/api/jobs", files={"file": ("page.png", png, "image/png")})
    assert r.status_code == 200, r.text
    job_dir = tmp_path / "jobs" / r.json()["job_id"]
    assert pymupdf.open(job_dir / "guide.pdf").page_count == 1
    assert (job_dir / "photos" / "page-001.png").is_file()


def test_pdf_and_photos_together_are_refused(client):
    r = client.post("/api/jobs", files=[("file", ("g.pdf", GOLDEN.read_bytes(), "application/pdf")),
                                        ("pages", ("p.jpg", page_photo(2), "image/jpeg"))])
    assert r.status_code == 422
    assert client.get("/api/jobs").json()["jobs"] == []


@pytest.mark.parametrize("junk", [b"GIF89a not ours", b"hello, plain text", b"\xff\xd8\xff broken jpeg"])
def test_a_non_image_page_is_refused(client, junk):
    r = client.post("/api/jobs", files=[("pages", ("a.jpg", page_photo(3), "image/jpeg")),
                                        ("pages", ("b.jpg", junk, "image/jpeg"))])
    assert r.status_code == 415 and "Photo 2" in r.json()["detail"]
    assert client.get("/api/jobs").json()["jobs"] == []


def test_too_many_photos_are_refused(client):
    tiny = io.BytesIO()
    Image.new("RGB", (8, 8), "white").save(tiny, "PNG")
    files = [("pages", (f"{i}.png", tiny.getvalue(), "image/png")) for i in range(photos.MAX_PHOTOS + 1)]
    r = client.post("/api/jobs", files=files)
    assert r.status_code in (413, 422) and str(photos.MAX_PHOTOS) in r.json()["detail"]


def test_nothing_sent_is_422(client):
    assert client.post("/api/jobs", data={"size": "1 x 2 x 3 cm"}).status_code == 422
