"""The binary frame message (PLAN §4.5):

    [uint32 header_len][JSON header][JPEG bytes]

`header_len` is an unsigned 32-bit integer in **big-endian** (network) byte order: it is what
`new DataView(buf).setUint32(0, len)` writes in a browser. The header is UTF-8 JSON, `header_len` bytes
long. Everything after it is the JPEG.

A length that cannot be right as big-endian but is right as little-endian is accepted too, so a client
that used a `Uint32Array` still works. Big-endian is the contract.
"""
from __future__ import annotations

import json

HINTS = ("low", "medium", "high")
JPEG_MAGIC = b"\xff\xd8\xff"
MAX_HEADER_BYTES = 4096


class FrameError(ValueError):
    """The frame cannot be used. `seq` is set when the header could still be read."""

    def __init__(self, message: str, seq: int | None = None):
        super().__init__(message)
        self.seq = seq


def pack_frame(header: dict, jpeg: bytes) -> bytes:
    head = json.dumps(header, separators=(",", ":")).encode("utf-8")
    return len(head).to_bytes(4, "big") + head + jpeg


def parse_frame(data: bytes, max_jpeg_bytes: int = 1_500_000) -> tuple[dict, bytes]:
    """Return `(header, jpeg)`. Raises FrameError with a message that is safe to send back."""
    if len(data) < 8:
        raise FrameError("the frame is too short")
    body = len(data) - 4
    n = int.from_bytes(data[:4], "big")
    if not 2 <= n <= min(body, MAX_HEADER_BYTES):
        n = int.from_bytes(data[:4], "little")
        if not 2 <= n <= min(body, MAX_HEADER_BYTES):
            raise FrameError("header_len does not fit the message (uint32, big-endian, then the JSON header)")
    try:
        header = json.loads(data[4:4 + n].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise FrameError("the frame header is not JSON") from None
    if not isinstance(header, dict):
        raise FrameError("the frame header must be a JSON object")
    seq = header.get("seq")
    if isinstance(seq, bool) or not isinstance(seq, int) or seq < 0:
        raise FrameError("the frame header needs an integer `seq`")
    jpeg = data[4 + n:]
    if not jpeg.startswith(JPEG_MAGIC):
        raise FrameError("the bytes after the header are not a JPEG", seq)
    if len(jpeg) > max_jpeg_bytes:
        raise FrameError(f"the JPEG is larger than {max_jpeg_bytes} bytes", seq)
    return header, jpeg
