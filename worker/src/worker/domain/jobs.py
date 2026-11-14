from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any

from worker.domain.rasters import Raster

# Contract versions this worker speaks (api/worker-api.md)
CONTRACT_VERSIONS = frozenset({1})


class JobType(StrEnum):
    """The work the backend can ask for. The values are the `type` field of the backend's jobs."""

    ASSESS_PASS = "assess_pass"
    REEVALUATE_PASS = "reevaluate_pass"
    DETECT_CHANGE = "detect_change"
    CLASSIFY_COMMUNITIES = "classify_communities"
    TRAIN_DROUGHT_MODEL = "train_drought_model"
    FORECAST_DROUGHT = "forecast_drought"


@dataclass(frozen=True)
class Job:
    """One lease of a job. `inputs` is the inputs object of its type (AssessPassInputs, DetectChangeInputs...)."""

    id: str
    type: JobType
    inputs: Any
    lease_seconds: int = 60
    contract_version: int = 1


@dataclass(frozen=True)
class JobResult:
    """What a use case returns: the result's `data` and the rasters it declares in `data.rasters`, by name."""

    job_id: str
    data: dict[str, Any]
    rasters: dict[str, Raster] = field(default_factory=dict)
