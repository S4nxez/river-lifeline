import gzip
import json

import pytest
from fakes import classes, ref
from sample_jobs import job

from worker.adapters.backend.client import BackendClient
from worker.adapters.backend.http import HttpRequest, HttpResponse
from worker.domain.errors import BackendUnavailable, InvalidJob, InvalidResult, LeaseLost
from worker.domain.jobs import JobResult, JobType

BASE = "http://backend:8080"
API = f"{BASE}/internal/worker"
NEXT = f"GET {API}/jobs/next?types=detect_change,classify_communities&contract=1"
TYPES = [JobType.DETECT_CHANGE, JobType.CLASSIFY_COMMUNITIES]


class FakeTransport:
    """Answers each "METHOD url" with a canned response and records the requests."""

    def __init__(self, routes: dict[str, HttpResponse]) -> None:
        self.routes = routes
        self.requests: list[HttpRequest] = []

    def __call__(self, request: HttpRequest) -> HttpResponse:
        self.requests.append(request)
        return self.routes.get(f"{request.method} {request.url}", HttpResponse(404))

    def sent(self, method_and_url: str) -> HttpRequest:
        return next(r for r in self.requests if f"{r.method} {r.url}" == method_and_url)


def ok(payload) -> HttpResponse:
    return HttpResponse(200, json.dumps(payload).encode())


def client(transport) -> BackendClient:
    return BackendClient(BASE + "/", "s3cret", transport)


# --- Leasing jobs ---------------------------------------------------------------------------------------------


def test_asks_for_the_job_types_and_contract_versions_it_runs():
    transport = FakeTransport({NEXT: HttpResponse(204)})

    assert client(transport).next_job(TYPES, [1]) is None
    assert transport.requests[0].headers["X-Worker-Token"] == "s3cret"


def test_leases_the_next_job_with_its_inputs():
    transport = FakeTransport({NEXT: ok(job("detect_change"))})

    leased = client(transport).next_job(TYPES, [1])

    assert (leased.id, leased.type, leased.lease_seconds) == ("lease-7", JobType.DETECT_CHANGE, 600)
    assert leased.inputs.since_pass_id == "2026-10-11_t161A"


@pytest.mark.parametrize(
    ("body", "reason"),
    [
        (job("forecast_drought"), "UnknownJobType"),
        (job("detect_change", contractVersion=2), "UnsupportedContract"),
        (job("detect_change", inputs={"grid": {}}), "InvalidJob"),
    ],
)
def test_hands_back_a_job_it_cannot_run_as_a_failure_not_worth_retrying(body, reason):
    transport = FakeTransport({NEXT: ok(body), f"POST {API}/jobs/lease-7/failure": HttpResponse(204)})

    assert client(transport).next_job(TYPES, [1]) is None

    failure = json.loads(transport.sent(f"POST {API}/jobs/lease-7/failure").body)
    assert failure["reason"].startswith(reason)
    assert failure["retryable"] is False


def test_an_error_status_means_the_backend_is_unavailable():
    transport = FakeTransport({NEXT: HttpResponse(503)})

    with pytest.raises(BackendUnavailable):
        client(transport).next_job(TYPES, [1])


# --- Leases ---------------------------------------------------------------------------------------------------


def test_a_heartbeat_returns_the_new_lease():
    transport = FakeTransport({f"POST {API}/jobs/lease-7/heartbeat": ok({"leaseSeconds": 300})})

    assert client(transport).heartbeat("lease-7") == 300


def test_a_409_means_the_lease_is_lost():
    transport = FakeTransport({f"POST {API}/jobs/lease-7/heartbeat": HttpResponse(409)})

    with pytest.raises(LeaseLost):
        client(transport).heartbeat("lease-7")


# --- Rasters --------------------------------------------------------------------------------------------------


def test_downloads_a_raster_into_memory():
    body = bytes(range(12))
    transport = FakeTransport({f"GET {API}/rasters/water": HttpResponse(200, body)})

    raster = client(transport).read(ref("water"))

    assert raster.data == body
    assert raster.meta == ref("water").meta
    assert transport.requests[0].headers["Accept-Encoding"] == "gzip"


def test_decompresses_a_gzipped_raster():
    body = bytes(range(12))
    gzipped = HttpResponse(200, gzip.compress(body), {"content-encoding": "gzip"})
    transport = FakeTransport({f"GET {API}/rasters/water": gzipped})

    assert client(transport).read(ref("water")).data == body


@pytest.mark.parametrize(
    "response",
    [HttpResponse(200, bytes(11)), HttpResponse(404), HttpResponse(200, b"not gzip", {"content-encoding": "gzip"})],
)
def test_a_missing_or_broken_raster_makes_the_job_invalid(response):
    transport = FakeTransport({f"GET {API}/rasters/water": response})

    with pytest.raises(InvalidJob):
        client(transport).read(ref("water"))


# --- Results --------------------------------------------------------------------------------------------------


def test_uploads_the_rasters_gzipped_before_posting_the_result():
    water = classes(*range(12))
    transport = FakeTransport(
        {
            f"PUT {API}/jobs/lease-7/rasters/water": HttpResponse(204),
            f"POST {API}/jobs/lease-7/result": HttpResponse(204),
        }
    )

    client(transport).complete(JobResult("lease-7", {"passId": "p"}, {"water": water}))

    upload, result = transport.requests
    assert (upload.method, result.method) == ("PUT", "POST")
    assert upload.headers["Content-Encoding"] == "gzip"
    assert upload.headers["Content-Type"] == "application/octet-stream"
    assert gzip.decompress(upload.body) == water.data
    assert json.loads(result.body) == {"data": {"passId": "p"}}


@pytest.mark.parametrize(("status", "error"), [(409, LeaseLost), (422, InvalidResult), (413, InvalidResult)])
def test_a_rejected_result_says_why(status, error):
    transport = FakeTransport({f"POST {API}/jobs/lease-7/result": HttpResponse(status, b"water has 11 bytes")})

    with pytest.raises(error):
        client(transport).complete(JobResult("lease-7", {}))


def test_reports_a_failure_and_whether_it_is_worth_retrying():
    transport = FakeTransport({f"POST {API}/jobs/a%2Fb/failure": HttpResponse(204)})

    client(transport).fail("a/b", "MemoryError: out of memory", True)

    assert json.loads(transport.requests[0].body) == {"reason": "MemoryError: out of memory", "retryable": True}


def test_never_calls_anything_but_the_worker_api():
    transport = FakeTransport({})

    with pytest.raises(InvalidJob):
        client(transport)._send("GET", "http://elsewhere.example/internal/worker/rasters/1")
    with pytest.raises(InvalidJob):
        client(transport)._send("GET", "/api/v1/areas")
    assert transport.requests == []
