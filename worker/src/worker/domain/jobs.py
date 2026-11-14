from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any


class JobType(StrEnum):
    """The work the backend can ask for. The values are the `type` field of the backend's jobs."""

    ASSESS_CONNECTIVITY = "assess_connectivity"
    TRAIN_DROUGHT_MODEL = "train_drought_model"
    FORECAST_DROUGHT = "forecast_drought"


@dataclass(frozen=True)
class Job:
    id: str
    type: JobType
    params: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class JobResult:
    """What a use case returns; the backend stores it and serves it to the UI."""

    job_id: str
    data: dict[str, Any]
