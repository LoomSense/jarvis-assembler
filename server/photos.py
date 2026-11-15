"""Photos of a paper guide → one PDF the pipeline reads like any other guide.

Each photo is turned upright (EXIF), the page is found and flattened when the detection is confident, and the result
is cleaned to black line drawings on white paper, like a scan. One photo is one PDF page, sized so the ingest step's
200 dpi rasterisation gives back the cleaned image pixel for pixel.
"""
from __future__ import annotations

import io

import cv2
import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

MAX_PHOTOS = 60
MAX_PHOTO_MB = 25
LONG_SIDE = 2400          # px of the cleaned page
PDF_DPI = 200             # must match RASTER_DPI in server/orchestrator/pipeline_cli.py
DETECT_SIDE = 900         # px; the page is looked for on a smaller copy

Image.MAX_IMAGE_PIXELS = 120_000_000    # ~ a 108 MP phone photo; bigger raises DecompressionBombError

try:                                    # HEIC/HEIF only when the optional plugin is installed
    from pillow_heif import register_heif_opener  # type: ignore

    register_heif_opener()
    HEIF = True
except ImportError:
    HEIF = False


class PhotoError(ValueError):
    """A photo the server cannot use. `status` is the HTTP code to answer with."""

    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


def sniff(data: bytes) -> str | None:
    """The file extension for an image we accept, from its first bytes; None if it is not one."""
    head = data[:16]
    if head.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "webp"
    if head[4:8] == b"ftyp" and head[8:12] in (b"heic", b"heix", b"hevc", b"heim", b"heis", b"mif1", b"msf1"):
        return "heic"
    return None


def open_photo(data: bytes, label: str) -> Image.Image:
    kind = sniff(data)
    if kind is None:
        raise PhotoError(415, f"{label} is not a JPEG, PNG, WebP or HEIC image")
    if kind == "heic" and not HEIF:
        raise PhotoError(415, f"{label} is HEIC, which this server cannot read; send it as JPEG "
                              "(on iPhone: Settings > Camera > Formats > Most Compatible)")
    try:
        img = Image.open(io.BytesIO(data))
        img.load()
    except Image.DecompressionBombError:
        raise PhotoError(413, f"{label} has too many pixels")
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError):
        raise PhotoError(415, f"{label} could not be read as an image")
    return ImageOps.exif_transpose(img).convert("RGB")


# ---- page detection ------------------------------------------------------------------------------

def order_corners(pts: np.ndarray) -> np.ndarray:
    """Top-left, top-right, bottom-right, bottom-left."""
    pts = pts.reshape(4, 2).astype(np.float32)
    s, d = pts.sum(1), np.diff(pts, axis=1).ravel()
    return np.array([pts[s.argmin()], pts[d.argmin()], pts[s.argmax()], pts[d.argmax()]], dtype=np.float32)


def find_page(rgb: np.ndarray) -> np.ndarray | None:
    """Corners of the page (the largest bright quadrilateral) in `rgb`'s pixels, or None when not sure."""
    h, w = rgb.shape[:2]
    scale = DETECT_SIDE / max(h, w)
    small = cv2.resize(rgb, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA) if scale < 1 else rgb
    scale = min(scale, 1.0)
    gray = cv2.GaussianBlur(cv2.cvtColor(small, cv2.COLOR_RGB2GRAY), (5, 5), 0)
    # Paper is the bright region: Otsu splits it from the background; closing removes the drawings inside it.
    _, mask = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8))
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return None
    c = max(contours, key=cv2.contourArea)
    area = cv2.contourArea(c)
    frame = small.shape[0] * small.shape[1]
    if not 0.15 * frame < area < 0.97 * frame:          # no page, or the page fills the frame already
        return None
    quad = cv2.approxPolyDP(cv2.convexHull(c), 0.02 * cv2.arcLength(c, True), True)
    if len(quad) != 4 or not cv2.isContourConvex(quad):
        return None
    if cv2.contourArea(quad) < 0.85 * area or area < 0.85 * cv2.contourArea(quad):   # a poor fit: not a sheet
        return None
    inside = np.zeros_like(mask)
    cv2.fillConvexPoly(inside, quad.reshape(4, 2), 255)
    if cv2.mean(gray, mask=inside)[0] < cv2.mean(gray, mask=255 - inside)[0] + 25:   # page must be brighter
        return None
    return order_corners(quad) / scale


def flatten(rgb: np.ndarray, corners: np.ndarray) -> np.ndarray:
    tl, tr, br, bl = corners
    width = round(max(np.linalg.norm(tr - tl), np.linalg.norm(br - bl)))
    height = round(max(np.linalg.norm(bl - tl), np.linalg.norm(br - tr)))
    target = np.array([[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]], dtype=np.float32)
    page = cv2.warpPerspective(rgb, cv2.getPerspectiveTransform(corners, target), (width, height),
                               flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    m = max(2, round(0.008 * max(width, height)))        # trim the sliver of table the outline leaves at the edges
    return page[m:height - m, m:width - m]


# ---- cleanup -------------------------------------------------------------------------------------

def clean(rgb: np.ndarray) -> np.ndarray:
    """Grayscale, even out the lighting, then stretch so paper is white and ink is black (greys survive)."""
    h, w = rgb.shape[:2]
    scale = LONG_SIDE / max(h, w)
    if scale < 1:
        rgb = cv2.resize(rgb, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
    # The paper's brightness everywhere: a dilation wipes out thin dark lines, a median smooths what is left.
    k = max(15, (max(gray.shape) // 60) | 1)
    paper = cv2.medianBlur(cv2.dilate(gray, np.ones((k, k), np.uint8)), 21)
    flat = cv2.divide(gray, paper, scale=255)
    black = float(np.percentile(flat, 0.5))
    white = 235.0 if black < 200 else 250.0
    out = np.clip((flat.astype(np.float32) - black) * 255.0 / max(white - black, 1.0), 0, 255)
    out = 255.0 * (out / 255.0) ** 1.4              # darken mid greys a little: thin lines stay solid
    return out.astype(np.uint8)


def prepare(img: Image.Image) -> tuple[np.ndarray, bool]:
    """The cleaned page and whether the page outline was found and flattened."""
    rgb = np.asarray(img)
    corners = find_page(rgb)
    if corners is not None:
        rgb = flatten(rgb, corners)
    return clean(rgb), corners is not None


def to_pdf(photos: list[bytes]) -> tuple[bytes, list[str], int]:
    """(PDF bytes, the extension of each photo, how many pages were flattened). Raises PhotoError."""
    import pymupdf

    if not photos:
        raise PhotoError(422, "Send at least one photo")
    if len(photos) > MAX_PHOTOS:
        raise PhotoError(422, f"Send at most {MAX_PHOTOS} photos; got {len(photos)}")
    doc = pymupdf.open()
    kinds, flattened = [], 0
    for i, data in enumerate(photos, start=1):
        label = f"Photo {i}"
        if len(data) > MAX_PHOTO_MB * 1024 * 1024:
            raise PhotoError(413, f"{label} is larger than {MAX_PHOTO_MB} MB")
        img = open_photo(data, label)
        kinds.append(sniff(data) or "jpg")
        page_img, found = prepare(img)
        flattened += found
        ok, png = cv2.imencode(".png", page_img)
        if not ok:
            raise PhotoError(422, f"{label} could not be converted")
        h, w = page_img.shape
        page = doc.new_page(width=w * 72 / PDF_DPI, height=h * 72 / PDF_DPI)
        page.insert_image(page.rect, stream=png.tobytes())
    return doc.tobytes(garbage=3, deflate=True), kinds, flattened
