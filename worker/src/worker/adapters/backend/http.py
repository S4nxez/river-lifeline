"""A minimal HTTP layer over the standard library, injectable so the client can be tested without a network."""

import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field

from worker.domain.errors import BackendUnavailable


@dataclass(frozen=True)
class HttpRequest:
    method: str
    url: str
    headers: dict[str, str] = field(default_factory=dict)
    body: bytes | None = None


@dataclass(frozen=True)
class HttpResponse:
    status: int
    body: bytes = b""
    # Header names in lower case
    headers: dict[str, str] = field(default_factory=dict)


Transport = Callable[[HttpRequest], HttpResponse]


def urllib_transport(request: HttpRequest, timeout_seconds: float = 60) -> HttpResponse:
    """Sends the request; HTTP error statuses are returned, network failures raise BackendUnavailable.

    The body is returned as sent: urllib never decodes `Content-Encoding`, the client does.
    """
    raw = urllib.request.Request(request.url, data=request.body, headers=request.headers, method=request.method)
    try:
        with urllib.request.urlopen(raw, timeout=timeout_seconds) as response:
            return HttpResponse(response.status, response.read(), _headers(response.headers))
    except urllib.error.HTTPError as error:
        return HttpResponse(error.code, error.read(), _headers(error.headers))
    except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
        raise BackendUnavailable(f"{request.method} {request.url}: {error}") from error


def _headers(headers) -> dict[str, str]:
    return {name.lower(): value for name, value in headers.items()} if headers else {}
