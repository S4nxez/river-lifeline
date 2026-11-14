class WorkerError(Exception):
    """Base class of the errors the worker reports back to the backend as a failed job.

    `retryable` tells the backend whether running the same job again could succeed.
    """

    retryable = True


class InvalidJob(WorkerError):
    """The job lacks what its use case needs, or carries inputs that can't be used (a broken raster)."""

    retryable = False


class UnknownJobType(WorkerError):
    """No use case handles this job type."""

    retryable = False


class UnsupportedContract(WorkerError):
    """The job speaks a contract version this worker does not."""

    retryable = False


class InvalidResult(WorkerError):
    """A use case produced a result that breaks the contract: running it again would produce the same one."""

    retryable = False


class BackendUnavailable(WorkerError):
    """The backend could not be reached or answered with an error."""


class LeaseLost(WorkerError):
    """The backend answered 409: the job is no longer leased to this worker, so it is dropped without a word."""
