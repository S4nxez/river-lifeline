"""What the use cases need from the outside world, as interfaces. Implementations live in worker.adapters."""

from collections.abc import Collection
from datetime import date
from typing import Protocol

from worker.domain.drought import DroughtEvent, DroughtModel, WeatherObservation
from worker.domain.early_warning import LevelQuantiles, Threshold
from worker.domain.jobs import Job, JobResult, JobType
from worker.domain.passes import AssessPassInputs, ChangeMap, PassAssessment, ReevaluatePassInputs
from worker.domain.rasters import Raster, RasterRef

# --- Talking to the backend: the worker's only connection to the outside -------------------------------------------


class JobQueue(Protocol):
    def next_job(self, types: Collection[JobType], contracts: Collection[int]) -> Job | None:
        """Leases the next pending job of these types and contract versions; None when there is nothing to do."""
        ...

    def heartbeat(self, job_id: str) -> int:
        """Extends the lease; returns its new length in seconds. Raises LeaseLost when the job is no longer ours."""
        ...

    def complete(self, result: JobResult) -> None:
        """Uploads the result's rasters, then posts its data. Raises LeaseLost when the job is no longer ours."""
        ...

    def fail(self, job_id: str, reason: str, retryable: bool) -> None: ...


class RasterSource(Protocol):
    def read(self, ref: RasterRef) -> Raster:
        """Downloads a raster the job references into memory."""
        ...


# --- Algorithms: swappable implementations ------------------------------------------------------------------------


class PassAnalyzer(Protocol):
    def assess(self, inputs: AssessPassInputs, rasters: RasterSource) -> PassAssessment:
        """Judges a NISAR pass from its granule windows; reads each raster from `rasters` when it needs it."""
        ...

    def reevaluate(self, inputs: ReevaluatePassInputs, rasters: RasterSource) -> PassAssessment:
        """Judges a pass again from its stored masks, without radar."""
        ...


class ChangeMapper(Protocol):
    def compare(self, previous: Raster, current: Raster) -> ChangeMap:
        """Water lost and gained between two `water` rasters of the same grid."""
        ...


class DroughtModelTrainer(Protocol):
    def train(self, observations: list[WeatherObservation], droughts: list[DroughtEvent]) -> DroughtModel: ...


class DroughtForecaster(Protocol):
    def predict(self, model: DroughtModel, recent: list[WeatherObservation], horizon_days: int) -> float:
        """Probability (0 to 1) of a drought within `horizon_days`."""
        ...


class DroughtModelAlgorithm(DroughtModelTrainer, DroughtForecaster, Protocol):
    """One implementation that trains and forecasts with the same kind of model."""


# --- Experimental early warning (docs/early-warning.md) -------------------------------------------------------------


class RiverModel(Protocol):
    """The river twin of an area: routes at any gauge level, and how its water compares with the radar's."""

    no_data_share: float

    def thresholds(
        self, levels_cm: list[float], classes: dict[str, str], monitored: set[str]
    ) -> tuple[list[Threshold], list[object]]:
        """The at-risk and isolation levels of every community, sweeping the given gauge levels."""
        ...

    def mask_iou(self, level_cm: float, observed_water: object, observed_valid: object, occurrence: object) -> dict:
        """IoU of the water at a level against an observed water mask, by zone."""
        ...


class LevelForecaster(Protocol):
    levels: dict[date, float]

    def level_on(self, issued: date, max_back_days: int = 3) -> tuple[date, float]: ...

    def forecast(self, issued: date, horizon_days: int) -> list[LevelQuantiles]:
        """Level quantiles for each day after the issue date, from the days before it only."""
        ...


# --- What every use case is to the dispatcher ---------------------------------------------------------------------


class JobHandler(Protocol):
    def execute(self, job: Job) -> JobResult: ...
