from fakes import FakeDroughtModel, FakeQueue, FakeRasters

from worker.config import Settings
from worker.domain.jobs import JobType
from worker.entrypoints.cli import build_worker

SETTINGS = Settings("http://backend:8080", "t", 5)


class Backend(FakeQueue, FakeRasters):
    def __init__(self) -> None:
        FakeQueue.__init__(self)
        FakeRasters.__init__(self)


def test_announces_only_the_job_types_whose_algorithms_exist():
    worker = build_worker(SETTINGS, backend=Backend())

    assert worker._dispatcher.job_types == {
        JobType.ASSESS_PASS,
        JobType.REEVALUATE_PASS,
        JobType.DETECT_CHANGE,
        JobType.CLASSIFY_COMMUNITIES,
    }


def test_every_job_type_has_a_use_case_once_its_algorithm_is_given():
    worker = build_worker(SETTINGS, backend=Backend(), pass_analyzer=object(), drought_model=FakeDroughtModel())

    assert worker._dispatcher.job_types == set(JobType)


def test_builds_the_backend_client_from_the_settings():
    worker = build_worker(SETTINGS)

    assert worker._queue._base_url == "http://backend:8080"
