"""Error codes matching extractors/CONTRACT.md §Error codes (v1.0 FROZEN).

Every error response body is shaped by `ErrorResponse`. Codes here are
the *only* `error` strings the Java client should ever see.
"""

from __future__ import annotations

from dataclasses import dataclass


# -- 4xx codes (input errors) -------------------------------------------------
INVALID_FILE_TYPE = "INVALID_FILE_TYPE"  # 400 — upload was not application/pdf
INVALID_REQUEST = "INVALID_REQUEST"  # 400 — missing/multiple input modes, bad JSON
PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"  # 413 — body > 20 MB
FETCH_FAILED = "FETCH_FAILED"  # 400 — URL mode: remote fetch failed
PATH_NOT_FOUND = "PATH_NOT_FOUND"  # 400 — path mode: path not readable
S3_NOT_SUPPORTED = "S3_NOT_SUPPORTED"  # 501 — s3:// not implemented in V1

# -- 5xx codes (server errors) ------------------------------------------------
EXTRACTION_FAILED = "EXTRACTION_FAILED"  # 500 — Docling/MiniRU raised
EXTRACTION_TIMEOUT = "EXTRACTION_TIMEOUT"  # 504 — exceeded 25 s service budget


@dataclass(frozen=True)
class ExtractorError(Exception):
    """Raised inside the service; the FastAPI handler converts it to JSON + status."""

    code: str
    message: str
    status_code: int = 400

    def __str__(self) -> str:  # pragma: no cover — trivial
        return f"{self.code}: {self.message}"


class InvalidFileType(ExtractorError):
    def __init__(self, message: str) -> None:
        super().__init__(INVALID_FILE_TYPE, message, 400)


class InvalidRequest(ExtractorError):
    def __init__(self, message: str) -> None:
        super().__init__(INVALID_REQUEST, message, 400)


class PayloadTooLarge(ExtractorError):
    def __init__(self, message: str) -> None:
        super().__init__(PAYLOAD_TOO_LARGE, message, 413)


class FetchFailed(ExtractorError):
    def __init__(self, message: str) -> None:
        super().__init__(FETCH_FAILED, message, 400)


class PathNotFound(ExtractorError):
    def __init__(self, message: str) -> None:
        super().__init__(PATH_NOT_FOUND, message, 400)


class S3NotSupported(ExtractorError):
    def __init__(self, message: str = "s3:// URLs are not supported in V1") -> None:
        super().__init__(S3_NOT_SUPPORTED, message, 501)


class ExtractionFailed(ExtractorError):
    def __init__(self, message: str) -> None:
        super().__init__(EXTRACTION_FAILED, message, 500)


class ExtractionTimeout(ExtractorError):
    def __init__(self, message: str = "extraction exceeded service budget (25 s)") -> None:
        super().__init__(EXTRACTION_TIMEOUT, message, 504)
