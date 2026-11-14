"""One module per job type. Each use case is a JobHandler: execute(job) -> JobResult."""

from worker.domain.errors import InvalidJob
from worker.domain.jobs import Job


def inputs_of[T](job: Job, kind: type[T]) -> T:
    """The job's inputs, which must be of `kind`; anything else is an invalid job."""
    if not isinstance(job.inputs, kind):
        raise InvalidJob(f"job {job.id} ({job.type}) carries {type(job.inputs).__name__}, not {kind.__name__}")
    return job.inputs
