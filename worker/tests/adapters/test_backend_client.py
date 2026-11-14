import json
from datetime import date

import pytest

from worker.adapters.backend.client import BackendClient
from worker.adapters.backend.http import HttpRequest, HttpResponse
from worker.domain.connectivity import Community
from worker.domain.errors import BackendUnavailable, InvalidJob
from worker.domain.jobs import Job, JobResult, JobType

BASE = "http://backend:8080"


class FakeTransport:
    """Answers each "METHOD url" with a canned response and records the requests."""

    def __init__(self, routes: dict[str, HttpResponse]) -> None:
        self.routes = routes
        self.requests: list[HttpRequest] = []

    def __call__(self, request: HttpRequest) -> HttpResponse:
        self.requests.append(request)
        return self.routes.get(f"{request.method} {request.url}", HttpResponse(404))


def ok(payload) -> HttpResponse:
    return HttpResponse(200, json.dumps(payload).encode())


def client(transport, tmp_path) -> BackendClient:
    return BackendClient(BASE + "/", "s3cret", tmp_path, transport)


def test_no_pending_job_is_none(tmp_path):
    transport = FakeTransport({f"GET {BASE}/internal/worker/jobs/next": HttpResponse(204)})

    assert client(transport, tmp_path).next_job() is None


def test_reads_the_next_job(tmp_path):
    job = {"id": 7, "type": "forecast_drought", "params": {"region": "murcia"}}
    transport = FakeTransport({f"GET {BASE}/internal/worker/jobs/next": ok(job)})

    assert client(transport, tmp_path).next_job() == Job("7", JobType.FORECAST_DROUGHT, {"region": "murcia"})


def test_hands_back_a_job_type_it_cannot_run_as_failed(tmp_path):
    transport = FakeTransport(
        {
            f"GET {BASE}/internal/worker/jobs/next": ok({"id": 8, "type": "make_coffee"}),
            f"POST {BASE}/internal/worker/jobs/8/failure": HttpResponse(204),
        }
    )

    assert client(transport, tmp_path).next_job() is None
    assert "make_coffee" in json.loads(transport.requests[-1].body)["reason"]


def test_every_request_carries_the_worker_token(tmp_path):
    transport = FakeTransport({f"GET {BASE}/internal/worker/jobs/next": HttpResponse(204)})

    client(transport, tmp_path).next_job()

    assert transport.requests[0].headers["X-Worker-Token"] == "s3cret"


def test_posts_results_and_failures_as_json(tmp_path):
    transport = FakeTransport(
        {
            f"POST {BASE}/internal/worker/jobs/1/result": HttpResponse(204),
            f"POST {BASE}/internal/worker/jobs/2/failure": HttpResponse(204),
        }
    )
    backend = client(transport, tmp_path)

    backend.complete(JobResult("1", {"level": "high"}))
    backend.fail("2", "InvalidJob: no scenes")

    assert [json.loads(request.body) for request in transport.requests] == [
        {"data": {"level": "high"}},
        {"reason": "InvalidJob: no scenes"},
    ]
    assert transport.requests[0].headers["Content-Type"] == "application/json"


def test_downloads_the_scenes_of_a_job(tmp_path):
    listing = [
        {"id": "s1", "mission": "NISAR", "acquiredOn": "2025-01-05", "polarization": "HH", "downloadUrl": "/f/s1"}
    ]
    transport = FakeTransport(
        {
            f"GET {BASE}/internal/worker/jobs/3/scenes": ok(listing),
            f"GET {BASE}/f/s1": HttpResponse(200, b"raster bytes"),
        }
    )

    [scene] = client(transport, tmp_path).scenes_for(Job("3", JobType.ASSESS_CONNECTIVITY))

    assert (scene.id, scene.mission, scene.acquired_on, scene.polarization) == ("s1", "NISAR", date(2025, 1, 5), "HH")
    assert scene.path == tmp_path / "3" / "s1.tif"
    assert scene.path.read_bytes() == b"raster bytes"


def test_never_sends_the_token_outside_the_backend(tmp_path):
    listing = [{"id": "s1", "mission": "NISAR", "acquiredOn": "2025-01-05", "polarization": "HH",
                "downloadUrl": "https://elsewhere.example/s1"}]  # fmt: skip
    transport = FakeTransport({f"GET {BASE}/internal/worker/jobs/3/scenes": ok(listing)})

    with pytest.raises(InvalidJob):
        client(transport, tmp_path).scenes_for(Job("3", JobType.ASSESS_CONNECTIVITY))
    assert len(transport.requests) == 1


def test_reads_the_monitored_area(tmp_path):
    area = {
        "hub": {"id": "tefe", "name": "Tefé", "lat": -3.354, "lng": -64.711},
        "communities": [{"id": "uarini", "name": "Uarini", "lat": -2.99, "lng": -65.113}],
    }
    transport = FakeTransport({f"GET {BASE}/internal/worker/area": ok(area)})

    monitored = client(transport, tmp_path).monitored_area()

    assert monitored.hub == Community("tefe", "Tefé", -3.354, -64.711)
    assert monitored.communities == [Community("uarini", "Uarini", -2.99, -65.113)]


def test_reads_weather_observations_and_drought_history(tmp_path):
    transport = FakeTransport(
        {
            f"GET {BASE}/internal/worker/weather?region=murcia&from=2025-01-01&to=2025-01-31": ok(
                [{"day": "2025-01-01", "precipitationMm": 0.4, "temperatureC": 12.5, "soilMoisture": None}]
            ),
            f"GET {BASE}/internal/worker/droughts?region=murcia": ok(
                [{"start": "2023-04-01", "end": "2023-10-01", "severity": 3}]
            ),
        }
    )
    backend = client(transport, tmp_path)

    [observation] = backend.observations("murcia", date(2025, 1, 1), date(2025, 1, 31))
    [drought] = backend.drought_history("murcia")

    assert (observation.day, observation.precipitation_mm, observation.temperature_c) == (date(2025, 1, 1), 0.4, 12.5)
    assert (drought.start, drought.end, drought.severity) == (date(2023, 4, 1), date(2023, 10, 1), 3)


def test_an_error_status_means_the_backend_is_unavailable(tmp_path):
    transport = FakeTransport({f"GET {BASE}/internal/worker/jobs/next": HttpResponse(503)})

    with pytest.raises(BackendUnavailable):
        client(transport, tmp_path).next_job()
