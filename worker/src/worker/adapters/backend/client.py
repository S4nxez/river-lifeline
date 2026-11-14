"""Client of the backend's internal worker API: implements JobQueue, ImageryProvider and WeatherProvider.

Endpoints (see worker/README.md), all under the backend's internal network address, never through the public proxy:
    GET  /internal/worker/jobs/next            204, or 200 {"id", "type", "params"}
    POST /internal/worker/jobs/{id}/result     {"data": {...}}
    POST /internal/worker/jobs/{id}/failure    {"reason": "..."}
    GET  /internal/worker/jobs/{id}/scenes     [{"id", "mission", "acquiredOn", "polarization", "downloadUrl"}]
    GET  /internal/worker/weather?region=&from=&to=   [{"day", "precipitationMm", "temperatureC", "soilMoisture"}]
    GET  /internal/worker/droughts?region=            [{"start", "end", "severity"}]
Every request carries the shared secret in the X-Worker-Token header.
"""

import json
from datetime import date
from pathlib import Path
from typing import Any
from urllib.parse import quote, urlencode

from worker.adapters.backend.http import HttpRequest, HttpResponse, Transport, urllib_transport
from worker.domain.drought import DroughtEvent, WeatherObservation
from worker.domain.errors import BackendUnavailable, InvalidJob
from worker.domain.imagery import Scene
from worker.domain.jobs import Job, JobResult, JobType

API = "/internal/worker"


class BackendClient:
    def __init__(self, base_url: str, token: str, download_dir: Path, transport: Transport = urllib_transport) -> None:
        self._base_url = base_url.rstrip("/")
        self._token = token
        self._download_dir = download_dir
        self._transport = transport

    # --- JobQueue ---------------------------------------------------------------------------------------------

    def next_job(self) -> Job | None:
        response = self._send("GET", f"{API}/jobs/next")
        if response.status == 204:
            return None
        body = json.loads(response.body)
        job_id = str(body["id"])
        if body["type"] not in set(JobType):
            # A job this worker version can't run: hand it back as failed instead of stalling the loop
            self.fail(job_id, f"UnknownJobType: this worker can't run {body['type']!r} jobs")
            return None
        return Job(id=job_id, type=JobType(body["type"]), params=body.get("params") or {})

    def complete(self, result: JobResult) -> None:
        self._send("POST", f"{API}/jobs/{quote(result.job_id)}/result", {"data": result.data})

    def fail(self, job_id: str, reason: str) -> None:
        self._send("POST", f"{API}/jobs/{quote(job_id)}/failure", {"reason": reason})

    # --- ImageryProvider --------------------------------------------------------------------------------------

    def scenes_for(self, job: Job) -> list[Scene]:
        listing = json.loads(self._send("GET", f"{API}/jobs/{quote(job.id)}/scenes").body)
        job_dir = self._download_dir / job.id
        job_dir.mkdir(parents=True, exist_ok=True)
        return [self._download(scene, job_dir) for scene in listing]

    def _download(self, scene: dict[str, Any], job_dir: Path) -> Scene:
        # TODO: stream to disk once scenes get large; read in memory for now
        path = job_dir / f"{scene['id']}.tif"
        path.write_bytes(self._send("GET", scene["downloadUrl"]).body)
        return Scene(
            id=scene["id"],
            mission=scene["mission"],
            acquired_on=date.fromisoformat(scene["acquiredOn"]),
            polarization=scene["polarization"],
            path=path,
        )

    # --- WeatherProvider --------------------------------------------------------------------------------------

    def observations(self, region: str, start: date, end: date) -> list[WeatherObservation]:
        query = urlencode({"region": region, "from": start.isoformat(), "to": end.isoformat()})
        rows = json.loads(self._send("GET", f"{API}/weather?{query}").body)
        return [
            WeatherObservation(
                region=region,
                day=date.fromisoformat(row["day"]),
                precipitation_mm=row["precipitationMm"],
                temperature_c=row["temperatureC"],
                soil_moisture=row.get("soilMoisture"),
            )
            for row in rows
        ]

    def drought_history(self, region: str) -> list[DroughtEvent]:
        rows = json.loads(self._send("GET", f"{API}/droughts?{urlencode({'region': region})}").body)
        return [
            DroughtEvent(
                region=region,
                start=date.fromisoformat(row["start"]),
                end=date.fromisoformat(row["end"]),
                severity=row["severity"],
            )
            for row in rows
        ]

    # --- HTTP -------------------------------------------------------------------------------------------------

    def _send(self, method: str, path: str, payload: dict[str, Any] | None = None) -> HttpResponse:
        url = self._url(path)
        headers = {"X-Worker-Token": self._token, "Accept": "application/json"}
        body = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(payload).encode()
        response = self._transport(HttpRequest(method, url, headers, body))
        if not 200 <= response.status < 300:
            raise BackendUnavailable(f"{method} {url} answered {response.status}")
        return response

    def _url(self, path: str) -> str:
        if path.startswith(("http://", "https://")):
            # The token goes with every request: never send it anywhere but the backend
            if not path.startswith(self._base_url + "/"):
                raise InvalidJob(f"refusing to call {path}: not a backend URL")
            return path
        return f"{self._base_url}/{path.lstrip('/')}"
