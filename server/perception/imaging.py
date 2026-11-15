"""Cheap local checks on a frame before a decision call is spent on it (PLAN §6.5). Needs Pillow."""
from __future__ import annotations

import io

try:
    from PIL import Image, ImageStat
except ImportError:                      # the server still runs; the local checks are skipped
    Image = None


PEAK_SHARE = 0.02                        # the peak is the brightness the brightest 2 % of the picture reach


def probe(jpeg: bytes) -> tuple[int, int, float, float] | None:
    """`(width, height, mean brightness, peak brightness)`, both 0-255. The peak is what the brightest 2 % of
    the picture reach. None when the bytes are not a readable image. Without Pillow the frame is taken as
    readable and bright."""
    if Image is None:
        return 0, 0, 255.0, 255.0
    try:
        im = Image.open(io.BytesIO(jpeg))
        width, height = im.size
        im.draft("L", (64, 64))              # decode at an eighth of the size: about a millisecond
        grey = im.convert("L")
        hist = grey.histogram()
        left, peak = sum(hist) * PEAK_SHARE, 0
        for level in range(255, -1, -1):
            left -= hist[level]
            if left <= 0:
                peak = level
                break
        return width, height, float(ImageStat.Stat(grey).mean[0]), float(peak)
    except Exception:                        # noqa: BLE001 - truncated or not an image
        return None


def too_dark(info: tuple, dark_luma: float, dark_peak: float) -> bool:
    """Dark means dark all over: a low mean and nothing lit in view. A dark floor with lit boards or a lit hand
    on it has a low mean too, and is a frame worth a call. `dark_luma` 0 switches the check off; `dark_peak` 0
    lets the mean alone decide (the rule before Sun 15 Nov)."""
    if not dark_luma or info[2] >= dark_luma:
        return False
    return not dark_peak or len(info) < 4 or info[3] < dark_peak


def tighten(jpeg: bytes, keep: float) -> bytes:
    """The centre of the frame, `keep` of each side, as a new JPEG. Used after repeated safety refusals,
    which usually mean a person is in the picture."""
    if Image is None or not 0.2 <= keep < 1.0:
        return jpeg
    try:
        im = Image.open(io.BytesIO(jpeg)).convert("RGB")
        w, h = im.size
        dx, dy = int(w * (1 - keep) / 2), int(h * (1 - keep) / 2)
        out = io.BytesIO()
        im.crop((dx, dy, w - dx, h - dy)).save(out, "JPEG", quality=80)
        return out.getvalue()
    except Exception:                        # noqa: BLE001
        return jpeg


MAX_IMAGE_BYTES = 2 * 1024 * 1024        # a still from the headset or phone camera; a frame is about 100 KB


def decode_jpeg_b64(image: str) -> bytes:
    """A base64 JPEG from a request body (a `data:` prefix is tolerated). Raises ValueError with a message for
    the client, or OverflowError when it is larger than MAX_IMAGE_BYTES."""
    import base64
    import binascii

    if image.startswith("data:"):
        image = image.partition(",")[2]
    try:
        data = base64.b64decode(image, validate=True)
    except (binascii.Error, ValueError):
        raise ValueError("image must be a base64 JPEG") from None
    if len(data) > MAX_IMAGE_BYTES:
        raise OverflowError(f"image is larger than {MAX_IMAGE_BYTES // (1024 * 1024)} MB")
    if not data.startswith(b"\xff\xd8"):
        raise ValueError("image must be a JPEG")
    return data
