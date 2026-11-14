import threading

import pytest
from fakes import FakeQueue, job

from worker.application.dispatcher import Dispatcher
from worker.application.worker import Worker
from worker.domain.errors import BackendUnavailable, InvalidJob, InvalidResult
from worker.domain.jobs import Job, JobResult, JobType


class Succeeds:
    def execute(self, job: Job) -> JobResult:
        return JobResult(job.id, {"ok": True})


class Raises:
    def __init__(self, error: Exception) -> None:
        self.error = error

    def execute(self, job: Job) -> JobResult:
        raise self.error


def worker_for(queue, handler, sleeps: list[float] | None = None, heartbeat_fraction: float = 1 / 3) -> Worker:
    """A worker with a 7 s poll interval whose waits are recorded in `sleeps` instead of slept."""
    recorded = sleeps if sleeps is not None else []
    return Worker(
        queue,
        Dispatcher({JobType.DETECT_CHANGE: handler}),
        poll_interval_seconds=7,
        sleep=recorded.append,
        heartbeat_fraction=heartbeat_fraction,
    )


def test_asks_only_for_the_job_types_and_contract_versions_it_runs():
    queue = FakeQueue()

    assert worker_for(queue, Succeeds()).run_once() is False

    assert queue.asked == [(["detect_change"], [1])]


def test_a_finished_job_reports_its_result():
    queue = FakeQueue(job(None))

    assert worker_for(queue, Succeeds()).run_once() is True

    assert queue.completed == [JobResult("1", {"ok": True})]
    assert queue.failed == []


def test_a_crashing_job_is_reported_as_failed_and_retryable():
    queue = FakeQueue(job(None))

    worker_for(queue, Raises(MemoryError("out of memory"))).run_once()

    assert queue.failed == [("1", "MemoryError: out of memory", True)]
    assert queue.completed == []


@pytest.mark.parametrize(
    "error", [InvalidJob("no granule reaches the area"), NotImplementedError("not yet"), ValueError("bad")]
)
def test_a_job_that_would_fail_again_is_not_retryable(error):
    queue = FakeQueue(job(None))

    worker_for(queue, Raises(error)).run_once()

    assert queue.failed == [("1", f"{type(error).__name__}: {error}", False)]


def test_a_job_type_without_handler_is_reported_as_failed():
    queue = FakeQueue(job(None, JobType.FORECAST_DROUGHT))

    worker_for(queue, Succeeds()).run_once()

    assert queue.failed[0][0] == "1"
    assert queue.failed[0][1].startswith("UnknownJobType")
    assert queue.failed[0][2] is False


def test_a_result_the_backend_rejects_is_reported_as_a_failure_not_worth_retrying():
    class Rejects(FakeQueue):
        def complete(self, result):
            raise InvalidResult("POST /result answered 422")

    queue = Rejects(job(None))

    worker_for(queue, Succeeds()).run_once()

    assert queue.failed == [("1", "InvalidResult: POST /result answered 422", False)]


def test_a_job_whose_lease_was_lost_is_dropped_without_a_word():
    queue = FakeQueue(job(None))
    queue.lease_lost = True

    assert worker_for(queue, Succeeds()).run_once() is True

    assert queue.completed == []
    assert queue.failed == []


def test_keeps_the_lease_alive_while_a_job_runs():
    queue = FakeQueue(job(None, lease_seconds=1))

    class WaitsForAHeartbeat:
        def execute(self, job: Job) -> JobResult:
            for _ in range(500):
                if queue.heartbeats:
                    return JobResult(job.id, {"beats": len(queue.heartbeats)})
                threading.Event().wait(0.01)
            raise AssertionError("no heartbeat")

    worker_for(queue, WaitsForAHeartbeat(), heartbeat_fraction=0.01).run_once()

    assert queue.heartbeats[0] == "1"
    assert len(queue.completed) == 1


def test_drops_the_result_when_a_heartbeat_finds_the_lease_lost():
    queue = FakeQueue(job(None, lease_seconds=1))

    class RunsUntilTheLeaseIsLost:
        def execute(self, job: Job) -> JobResult:
            queue.lease_lost = True
            for _ in range(500):
                if queue.heartbeats:
                    break
                threading.Event().wait(0.01)
            queue.lease_lost = False  # the result would be accepted: the worker must still not post it
            return JobResult(job.id, {})

    worker_for(queue, RunsUntilTheLeaseIsLost(), heartbeat_fraction=0.01).run_once()

    assert queue.heartbeats
    assert queue.completed == []
    assert queue.failed == []


def test_runs_jobs_back_to_back_and_waits_only_when_idle():
    queue = FakeQueue(job(None, job_id="1"), job(None, job_id="2"))
    sleeps = []
    rounds = iter([False, False, False, True])

    worker_for(queue, Succeeds(), sleeps).run_forever(should_stop=lambda: next(rounds))

    assert [result.job_id for result in queue.completed] == ["1", "2"]
    assert sleeps == [7]


def test_keeps_running_while_the_backend_is_unavailable():
    class Down(FakeQueue):
        def next_job(self, types, contracts):
            raise BackendUnavailable("connection refused")

    sleeps = []
    rounds = iter([False, False, True])

    worker_for(Down(), Succeeds(), sleeps).run_forever(should_stop=lambda: next(rounds))

    assert sleeps == [7, 7]
