import pytest

from worker.application.dispatcher import Dispatcher
from worker.domain.errors import UnknownJobType
from worker.domain.jobs import Job, JobResult, JobType


class Echo:
    def execute(self, job: Job) -> JobResult:
        return JobResult(job.id, {"handled": job.type.value})


def test_routes_each_job_to_the_handler_of_its_type():
    dispatcher = Dispatcher({JobType.DETECT_CHANGES: Echo()})

    result = dispatcher.handle(Job("1", JobType.DETECT_CHANGES))

    assert result == JobResult("1", {"handled": "detect_changes"})


def test_a_type_without_handler_is_an_unknown_job_type():
    dispatcher = Dispatcher({JobType.DETECT_CHANGES: Echo()})

    with pytest.raises(UnknownJobType):
        dispatcher.handle(Job("1", JobType.FORECAST_DROUGHT))


def test_lists_the_job_types_it_handles():
    assert Dispatcher({JobType.DETECT_CHANGES: Echo()}).job_types == {JobType.DETECT_CHANGES}
