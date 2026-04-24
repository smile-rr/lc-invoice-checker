"""Input-mode handling for /extract — multipart, {path}, {url}.

Centralises the logic that takes the three input shapes and returns
`bytes` (the PDF) ready for the Docling wrapper. Raises `ExtractorError`
subclasses on any failure so the main handler can turn them into the
contract-defined error JSON.

Contract references:
- §POST /extract Mode A/B/C
- §Error codes (INVALID_FILE_TYPE, PATH_NOT_FOUND, FETCH_FAILED, S3_NOT_SUPPORTED)
"""

from __future__ import annotations

import logging
from pathlib import Path
from urllib.parse import urlparse

import httpx

from .errors import (
    FetchFailed,
    InvalidFileType,
    InvalidRequest,
    PathNotFound,
    PayloadTooLarge,
    S3NotSupported,
)

logger = logging.getLogger(__name__)

MAX_PDF_BYTES = 20 * 1024 * 1024  # 20 MB per contract §Mode A


def validate_pdf_bytes(data: bytes) -> None:
    """Reject anything that doesn't look like a PDF by magic bytes."""
    if not data:
        raise InvalidRequest("empty body")
    if len(data) > MAX_PDF_BYTES:
        raise PayloadTooLarge(f"PDF exceeds {MAX_PDF_BYTES} bytes ({len(data)} given)")
    if data[:4] != b"%PDF":
        raise InvalidFileType("file does not start with %PDF magic bytes")


def read_path(path_str: str) -> bytes:
    """Mode B — read a PDF from a local path inside the container."""
    p = Path(path_str)
    if not p.exists():
        raise PathNotFound(f"path does not exist inside container: {path_str}")
    if not p.is_file():
        raise PathNotFound(f"path is not a regular file: {path_str}")
    try:
        data = p.read_bytes()
    except PermissionError as exc:
        raise PathNotFound(f"permission denied reading {path_str}: {exc}") from exc
    except OSError as exc:
        raise PathNotFound(f"cannot read {path_str}: {exc}") from exc
    validate_pdf_bytes(data)
    return data


def fetch_url(url: str) -> bytes:
    """Mode C — fetch a PDF from http/https. Raises S3NotSupported for s3://."""
    parsed = urlparse(url)
    scheme = (parsed.scheme or "").lower()
    if scheme == "s3":
        raise S3NotSupported()
    if scheme not in ("http", "https"):
        raise InvalidRequest(
            f"unsupported URL scheme '{scheme}'. V1 accepts http://, https://. "
            "s3:// returns S3_NOT_SUPPORTED (V2 scope)."
        )
    try:
        with httpx.Client(
            timeout=httpx.Timeout(20.0, connect=5.0),
            follow_redirects=True,
        ) as client:
            resp = client.get(url)
            resp.raise_for_status()
            data = resp.content
    except httpx.HTTPError as exc:
        raise FetchFailed(f"fetch failed for {url}: {exc}") from exc
    validate_pdf_bytes(data)
    return data
