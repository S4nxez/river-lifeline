import logging
import time
from collections.abc import Callable

from worker.application.dispatcher import Dispatcher
from worker.application.heartbeat import Heartbeat
from worker.application.ports import JobQueue
from worker.domain.errors import BackendUnavailable, InvalidResult, LeaseLost, WorkerError
from worker.domain.jobs import CONTRACT_VERSIONS, Job, JobResult

log = logging.getLogger(__name__)


class Worker:
    """Leases jobs from the backend, runs them while keeping their lease alive, and reports each result or failure.

    A job the backend took back (409: the lease expired or the job is done) is dropped without a word.
    """

    def __init__(
        self,
        queue: JobQueue,
        dispatcher: Dispatcher,
        poll_interval_seconds: float = 5.0,
        sleep: Callable[[float], None] = time.sleep,
        heartbeat_fraction: float = 1 / 3,
    ) -> None:
        self._queue = queue
        self._dispatcher = dispatcher
        self._poll_interval = poll_interval_seconds
        self._sleep = sleep
        self._heartbeat_fraction = heartbeat_fraction

    def run_once(self) -> bool:
        """Processes one job if there is any; returns whether there was one."""
        job = self._queue.next_job(sorted(self._dispatcher.job_types), sorted(CONTRACT_VERSIONS))
        if job is None:
            return False
        log.info("job %s (%s) started", job.id, job.type)

        result: JobResult | None = None
        error: Exception | None = None
        with Heartbeat(self._queue, job.id, job.lease_seconds, self._heartbeat_fraction) as heartbeat:
            try:
                result = self._dispatcher.handle(job)
            except Exception as raised:  # any failure is reported, so the backend never waits on a lost job
                log.exception("job %s failed", job.id)
                error = raised
        if heartbeat.lost:
            log.warning("job %s dropped: no longer leased to this worker", job.id)
            return True

        try:
            if result is not None:
                self._report(job, result)
            else:
                self._fail(job, error)
        except LeaseLost:
            log.warning("job %s dropped: no longer leased to this worker", job.id)
        return True

    def _report(self, job: Job, result: JobResult) -> None:
        try:
            self._queue.complete(result)
        except InvalidResult as rejected:
            log.error("job %s: the backend rejected the result: %s", job.id, rejected)
            self._fail(job, rejected)
        else:
            log.info("job %s done", job.id)

    def _fail(self, job: Job, error: Exception | None) -> None:
        self._queue.fail(job.id, f"{type(error).__name__}: {error}", _retryable(error))

    def run_forever(self, should_stop: Callable[[], bool] = lambda: False) -> None:
        while not should_stop():
            try:
                busy = self.run_once()
            except BackendUnavailable:
                log.warning("backend unavailable, retrying in %ss", self._poll_interval)
                busy = False
            if not busy:
                self._sleep(self._poll_interval)


def _retryable(error: Exception | None) -> bool:
    """Whether the same job could succeed on another try: not when its inputs or the code rule it out."""
    if isinstance(error, WorkerError):
        return error.retryable
    return not isinstance(error, NotImplementedError | ValueError | TypeError | KeyError)
