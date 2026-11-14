import logging
import threading

from worker.application.ports import JobQueue
from worker.domain.errors import BackendUnavailable, LeaseLost

log = logging.getLogger(__name__)


class Heartbeat:
    """Keeps a job's lease alive while it runs: extends it every `fraction` of the lease, in a background thread.

    Use it as a context manager around the job. `lost` turns true when the backend answers that the job is no longer
    ours; the job's result is then dropped.
    """

    def __init__(self, queue: JobQueue, job_id: str, lease_seconds: float, fraction: float = 1 / 3) -> None:
        self._queue = queue
        self._job_id = job_id
        self._interval = lease_seconds * fraction
        self._fraction = fraction
        self._stop = threading.Event()
        self._lost = threading.Event()
        self._thread = threading.Thread(target=self._beat, name=f"heartbeat-{job_id}", daemon=True)

    @property
    def lost(self) -> bool:
        return self._lost.is_set()

    def __enter__(self) -> "Heartbeat":
        self._thread.start()
        return self

    def __exit__(self, *_exc: object) -> None:
        self._stop.set()
        self._thread.join()

    def _beat(self) -> None:
        while not self._stop.wait(self._interval):
            try:
                self._interval = self._queue.heartbeat(self._job_id) * self._fraction
            except LeaseLost:
                log.warning("job %s: lease lost, its result will be dropped", self._job_id)
                self._lost.set()
                return
            except BackendUnavailable as error:
                # The lease may still be alive: keep the job running and try again on the next beat
                log.warning("job %s: heartbeat failed (%s)", self._job_id, error)
