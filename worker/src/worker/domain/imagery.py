from dataclasses import dataclass
from datetime import date
from pathlib import Path


@dataclass(frozen=True)
class Scene:
    """A satellite acquisition (NISAR, Sentinel-1...) the backend fetched and the worker downloaded to `path`."""

    id: str
    mission: str
    acquired_on: date
    polarization: str
    path: Path


@dataclass(frozen=True)
class ChangeReport:
    """How much the radar backscatter of an area changed between two acquisitions."""

    changed_fraction: float
    mean_change_db: float

    # Share of changed pixels from which a change counts as high or medium (the UI's change levels)
    HIGH_FROM = 0.25
    MEDIUM_FROM = 0.10

    def __post_init__(self) -> None:
        if not 0 <= self.changed_fraction <= 1:
            raise ValueError(f"changed_fraction must be between 0 and 1, got {self.changed_fraction}")

    @property
    def level(self) -> str:
        if self.changed_fraction >= self.HIGH_FROM:
            return "high"
        if self.changed_fraction >= self.MEDIUM_FROM:
            return "medium"
        return "low"

    @property
    def direction(self) -> str:
        return "increase" if self.mean_change_db >= 0 else "decrease"
