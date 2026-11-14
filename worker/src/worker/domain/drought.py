from dataclasses import dataclass, field
from datetime import date
from typing import Any


@dataclass(frozen=True)
class WeatherObservation:
    """One day of weather for a region, as the job carries it."""

    day: date
    precipitation_mm: float
    temperature_c: float
    soil_moisture: float | None = None


@dataclass(frozen=True)
class DroughtEvent:
    """A past drought of a region: what the predictive model learns from."""

    start: date
    end: date
    severity: int

    def __post_init__(self) -> None:
        if self.end < self.start:
            raise ValueError(f"a drought cannot end ({self.end}) before it starts ({self.start})")


@dataclass(frozen=True)
class DroughtModel:
    """A trained model as plain data: the backend stores it as is and sends it back with every forecast job. It is
    never executable code."""

    kind: str
    version: int
    # Days of weather before asOf a forecast job must carry
    lookback_days: int
    parameters: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if self.version < 1 or self.lookback_days < 1:
            raise ValueError(
                f"a model needs version and lookbackDays of at least 1, got {self.version}, {self.lookback_days}"
            )


@dataclass(frozen=True)
class TrainDroughtModelInputs:
    region: str
    observations: tuple[WeatherObservation, ...]
    droughts: tuple[DroughtEvent, ...]


@dataclass(frozen=True)
class ForecastDroughtInputs:
    region: str
    horizon_days: int
    as_of: date
    model: DroughtModel
    # The model's lookbackDays of weather up to asOf
    recent: tuple[WeatherObservation, ...]


@dataclass(frozen=True)
class DroughtForecast:
    region: str
    horizon_days: int
    as_of: date
    probability: float

    # Probability from which the risk counts as high or medium
    HIGH_FROM = 0.6
    MEDIUM_FROM = 0.3

    def __post_init__(self) -> None:
        if not 0 <= self.probability <= 1:
            raise ValueError(f"probability must be between 0 and 1, got {self.probability}")

    @property
    def risk_level(self) -> str:
        if self.probability >= self.HIGH_FROM:
            return "high"
        if self.probability >= self.MEDIUM_FROM:
            return "medium"
        return "low"
