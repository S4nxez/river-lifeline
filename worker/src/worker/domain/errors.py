class WorkerError(Exception):
    """Base class of the errors the worker reports back to the backend as a failed job."""


class InvalidJob(WorkerError):
    """The job lacks what its use case needs (parameters, input scenes, data)."""


class UnknownJobType(WorkerError):
    """No use case handles this job type."""


class ModelNotFound(WorkerError):
    """A forecast was requested for a region whose drought model has not been trained yet."""


class BackendUnavailable(WorkerError):
    """The backend could not be reached or answered with an error."""
