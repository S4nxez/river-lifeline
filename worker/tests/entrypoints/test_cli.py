from pathlib import Path

from fakes import FakeQueue

from worker.config import Settings
from worker.domain.jobs import JobType
from worker.entrypoints.cli import build_worker

SETTINGS = Settings("http://backend:8080", "t", 5, Path("/data"))


def test_every_job_type_has_a_use_case():
    worker = build_worker(SETTINGS, backend=FakeQueue())

    assert worker._dispatcher.job_types == set(JobType)


def test_builds_the_backend_client_from_the_settings():
    worker = build_worker(SETTINGS)

    assert worker._queue._base_url == "http://backend:8080"
