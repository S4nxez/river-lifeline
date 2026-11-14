"""One module per job type. Each use case is a JobHandler: execute(job) -> JobResult."""

from typing import Any

from worker.domain.errors import InvalidJob
from worker.domain.jobs import Job


def require_param(job: Job, name: str) -> Any:
    """The job parameter `name`; a job without it is invalid."""
    if name not in job.params:
        raise InvalidJob(f"job {job.id} ({job.type}) is missing the parameter {name!r}")
    return job.params[name]
