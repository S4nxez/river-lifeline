"""Client of the backend's internal worker API, contract version 1 (api/worker-api.md): implements JobQueue and
RasterSource.

    GET  /internal/worker/jobs/next?types=...&contract=...   204, or 200 Job
    POST /internal/worker/jobs/{id}/heartbeat                 200 {"leaseSeconds"}
    GET  /internal/worker/rasters/{rasterId}                  raw little-endian array (gzip allowed)
    PUT  /internal/worker/jobs/{id}/rasters/{name}            raw little-endian array, sent gzipped
    POST /internal/worker/jobs/{id}/result                    {"data": {...}}
    POST /internal/worker/jobs/{id}/failure                   {"reason", "retryable"}

All on the backend's internal address, never through the public proxy, and every request carries the shared secret
in X-Worker-Token: the client never calls any other host.
"""

import gzip
import json
from collections.abc import Collection
from typing import Any
from urllib.parse import quote, urlencode

from worker.adapters.backend.http import HttpRequest, HttpResponse, Transport, urllib_transport
from worker.adapters.backend.jobs_json import parse_job
from worker.domain.errors import BackendUnavailable, InvalidJob, InvalidResult, LeaseLost
from worker.domain.jobs import Job, JobResult, JobType
from worker.domain.rasters import Raster, RasterRef

API = "/internal/worker"


class BackendClient:
    def __init__(self, base_url: str, token: str, transport: Transport = urllib_transport) -> None:
        self._base_url = base_url.rstrip("/")
        self._token = token
        self._transport = transport

    # --- JobQueue ---------------------------------------------------------------------------------------------

    def next_job(self, types: Collection[JobType], contracts: Collection[int]) -> Job | None:
        query = urlencode(
            {"types": ",".join(str(t) for t in types), "contract": ",".join(str(c) for c in contracts)}, safe=","
        )
        response = self._send("GET", f"{API}/jobs/next?{query}")
        if response.status == 204:
            return None
        body = self._json(response)
        if not isinstance(body, dict) or "id" not in body:
            raise BackendUnavailable("GET /jobs/next answered a job without an id")
        job_id = str(body["id"])

        # The backend should only hand out what was asked for; anything else is handed back, never run
        if body.get("type") not in {str(t) for t in types}:
            self.fail(job_id, f"UnknownJobType: this worker did not ask for {body.get('type')!r} jobs", False)
            return None
        if body.get("contractVersion") not in set(contracts):
            reason = f"UnsupportedContract: this worker speaks {sorted(contracts)}, not {body.get('contractVersion')!r}"
            self.fail(job_id, reason, False)
            return None
        try:
            return parse_job(body)
        except InvalidJob as error:
            self.fail(job_id, f"InvalidJob: {error}", False)
            return None

    def heartbeat(self, job_id: str) -> int:
        body = self._json(self._send("POST", f"{API}/jobs/{quote(job_id, safe='')}/heartbeat"))
        return int(body["leaseSeconds"])

    def complete(self, result: JobResult) -> None:
        job = quote(result.job_id, safe="")
        for name, raster in result.rasters.items():
            self._send(
                "PUT",
                f"{API}/jobs/{job}/rasters/{quote(name, safe='')}",
                body=gzip.compress(raster.data, compresslevel=6),
                headers={"Content-Type": "application/octet-stream", "Content-Encoding": "gzip"},
            )
        self._send("POST", f"{API}/jobs/{job}/result", payload={"data": result.data})

    def fail(self, job_id: str, reason: str, retryable: bool) -> None:
        self._send(
            "POST",
            f"{API}/jobs/{quote(job_id, safe='')}/failure",
            payload={"reason": reason, "retryable": retryable},
        )

    # --- RasterSource -----------------------------------------------------------------------------------------

    def read(self, ref: RasterRef) -> Raster:
        response = self._send(
            "GET", ref.href, headers={"Accept": "application/octet-stream", "Accept-Encoding": "gzip"}
        )
        body = response.body
        if response.headers.get("content-encoding", "").lower() == "gzip":
            try:
                body = gzip.decompress(body)
            except (OSError, EOFError) as error:
                raise InvalidJob(f"raster {ref.href} is not valid gzip: {error}") from error
        return Raster(ref.meta, body)

    # --- HTTP -------------------------------------------------------------------------------------------------

    def _send(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> HttpResponse:
        if not path.startswith(API + "/") or "://" in path:
            # The token goes with every request: never send it anywhere but the backend's worker API
            raise InvalidJob(f"refusing to call {path!r}: not a path of the backend's worker API")
        all_headers = {"X-Worker-Token": self._token, "Accept": "application/json", **(headers or {})}
        if payload is not None:
            all_headers["Content-Type"] = "application/json"
            body = json.dumps(payload, allow_nan=False).encode()
        url = self._base_url + path
        response = self._transport(HttpRequest(method, url, all_headers, body))
        if 200 <= response.status < 300:
            return response
        if response.status == 409:
            raise LeaseLost(f"{method} {path}: the job is no longer leased to this worker")
        if response.status in (413, 422):
            raise InvalidResult(f"{method} {path} answered {response.status}: {_text(response)}")
        if response.status == 404 and path.startswith(API + "/rasters/"):
            raise InvalidJob(f"raster {path} is not available (404)")
        raise BackendUnavailable(f"{method} {path} answered {response.status}")

    @staticmethod
    def _json(response: HttpResponse) -> Any:
        try:
            return json.loads(response.body)
        except ValueError as error:
            raise BackendUnavailable(f"the backend answered invalid JSON: {error}") from error


def _text(response: HttpResponse) -> str:
    return response.body[:500].decode("utf-8", errors="replace")
