from fakes import FakeQueue

from worker.application.dispatcher import Dispatcher
from worker.application.worker import Worker
from worker.domain.errors import BackendUnavailable
from worker.domain.jobs import Job, JobResult, JobType


class Succeeds:
    def execute(self, job: Job) -> JobResult:
        return JobResult(job.id, {"ok": True})


class Crashes:
    def execute(self, job: Job) -> JobResult:
        raise RuntimeError("disk full")


def worker_for(queue, handler, sleeps: list[float] | None = None) -> Worker:
    """A worker with a 7 s poll interval whose waits are recorded in `sleeps` instead of slept."""
    recorded = sleeps if sleeps is not None else []
    return Worker(queue, Dispatcher({JobType.DETECT_CHANGES: handler}), poll_interval_seconds=7, sleep=recorded.append)


def test_with_no_pending_job_there_is_nothing_to_do():
    assert worker_for(FakeQueue(), Succeeds()).run_once() is False


def test_a_finished_job_reports_its_result():
    queue = FakeQueue(Job("1", JobType.DETECT_CHANGES))

    assert worker_for(queue, Succeeds()).run_once() is True

    assert queue.completed == [JobResult("1", {"ok": True})]
    assert queue.failed == []


def test_a_crashing_job_is_reported_as_failed_with_the_reason():
    queue = FakeQueue(Job("1", JobType.DETECT_CHANGES))

    worker_for(queue, Crashes()).run_once()

    assert queue.failed == [("1", "RuntimeError: disk full")]
    assert queue.completed == []


def test_a_job_type_without_handler_is_reported_as_failed():
    queue = FakeQueue(Job("1", JobType.FORECAST_DROUGHT))

    worker_for(queue, Succeeds()).run_once()

    assert queue.failed[0][0] == "1"
    assert queue.failed[0][1].startswith("UnknownJobType")


def test_runs_jobs_back_to_back_and_waits_only_when_idle():
    queue = FakeQueue(Job("1", JobType.DETECT_CHANGES), Job("2", JobType.DETECT_CHANGES))
    sleeps = []
    rounds = iter([False, False, False, True])

    worker_for(queue, Succeeds(), sleeps).run_forever(should_stop=lambda: next(rounds))

    assert [result.job_id for result in queue.completed] == ["1", "2"]
    assert sleeps == [7]


def test_keeps_running_while_the_backend_is_unavailable():
    class Down(FakeQueue):
        def next_job(self):
            raise BackendUnavailable("connection refused")

    sleeps = []
    rounds = iter([False, False, True])

    worker_for(Down(), Succeeds(), sleeps).run_forever(should_stop=lambda: next(rounds))

    assert sleeps == [7, 7]
