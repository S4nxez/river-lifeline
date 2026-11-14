import logging
import time
from collections.abc import Callable

from worker.application.dispatcher import Dispatcher
from worker.application.ports import JobQueue
from worker.domain.errors import BackendUnavailable

log = logging.getLogger(__name__)


class Worker:
    """Polls the backend for jobs, runs them, and reports each result or failure back."""

    def __init__(
        self,
        queue: JobQueue,
        dispatcher: Dispatcher,
        poll_interval_seconds: float = 5.0,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._queue = queue
        self._dispatcher = dispatcher
        self._poll_interval = poll_interval_seconds
        self._sleep = sleep

    def run_once(self) -> bool:
        """Processes one job if there is any; returns whether there was one."""
        job = self._queue.next_job()
        if job is None:
            return False
        log.info("job %s (%s) started", job.id, job.type)
        try:
            result = self._dispatcher.handle(job)
        except Exception as error:  # any failure is reported, so the backend never waits on a lost job
            log.exception("job %s failed", job.id)
            self._queue.fail(job.id, f"{type(error).__name__}: {error}")
        else:
            self._queue.complete(result)
            log.info("job %s done", job.id)
        return True

    def run_forever(self, should_stop: Callable[[], bool] = lambda: False) -> None:
        while not should_stop():
            try:
                busy = self.run_once()
            except BackendUnavailable:
                log.warning("backend unavailable, retrying in %ss", self._poll_interval)
                busy = False
            if not busy:
                self._sleep(self._poll_interval)
