from dataclasses import dataclass
from datetime import date


@dataclass(frozen=True)
class WeatherObservation:
    """One day of weather for a region, as the backend gets it from the weather APIs."""

    region: str
    day: date
    precipitation_mm: float
    temperature_c: float
    soil_moisture: float | None = None


@dataclass(frozen=True)
class DroughtEvent:
    """A past drought of a region: what the predictive model learns from."""

    region: str
    start: date
    end: date
    severity: int

    def __post_init__(self) -> None:
        if self.end < self.start:
            raise ValueError(f"a drought cannot end ({self.end}) before it starts ({self.start})")


@dataclass(frozen=True)
class DroughtForecast:
    region: str
    horizon_days: int
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
