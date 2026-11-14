"""What the use cases need from the outside world, as interfaces. Implementations live in worker.adapters."""

from datetime import date
from typing import Protocol

from worker.domain.drought import DroughtEvent, WeatherObservation
from worker.domain.imagery import ChangeReport, Scene
from worker.domain.jobs import Job, JobResult

# --- Talking to the backend: the worker's only connection to the outside -------------------------------------------


class JobQueue(Protocol):
    def next_job(self) -> Job | None:
        """The next pending job, or None when there is nothing to do."""
        ...

    def complete(self, result: JobResult) -> None: ...

    def fail(self, job_id: str, reason: str) -> None: ...


class ImageryProvider(Protocol):
    def scenes_for(self, job: Job) -> list[Scene]:
        """The scenes the backend gathered (from Earthdata and other catalogs) for this job, downloaded locally."""
        ...


class WeatherProvider(Protocol):
    def observations(self, region: str, start: date, end: date) -> list[WeatherObservation]: ...

    def drought_history(self, region: str) -> list[DroughtEvent]: ...


# --- Algorithms: swappable implementations ------------------------------------------------------------------------


class ChangeDetector(Protocol):
    def detect(self, before: Scene, after: Scene) -> ChangeReport: ...


class DroughtModel(Protocol):
    def train(self, observations: list[WeatherObservation], droughts: list[DroughtEvent]) -> None: ...

    def predict(self, recent: list[WeatherObservation], horizon_days: int) -> float:
        """Probability (0 to 1) of a drought within `horizon_days`."""
        ...


class DroughtModelStore(Protocol):
    def save(self, region: str, model: DroughtModel) -> None: ...

    def load(self, region: str) -> DroughtModel:
        """The trained model of the region; raises ModelNotFound if there is none."""
        ...


# --- What every use case is to the dispatcher ---------------------------------------------------------------------


class JobHandler(Protocol):
    def execute(self, job: Job) -> JobResult: ...
