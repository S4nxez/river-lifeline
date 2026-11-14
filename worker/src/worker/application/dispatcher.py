from collections.abc import Mapping

from worker.application.ports import JobHandler
from worker.domain.errors import UnknownJobType
from worker.domain.jobs import Job, JobResult, JobType


class Dispatcher:
    """Routes each job to the use case that handles its type."""

    def __init__(self, handlers: Mapping[JobType, JobHandler]) -> None:
        self._handlers = dict(handlers)

    @property
    def job_types(self) -> set[JobType]:
        return set(self._handlers)

    def handle(self, job: Job) -> JobResult:
        handler = self._handlers.get(job.type)
        if handler is None:
            raise UnknownJobType(f"no handler for job type {job.type!r}")
        return handler.execute(job)
