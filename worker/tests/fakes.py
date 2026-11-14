"""In-memory implementations of the application ports, for testing the use cases and the worker loop."""

from datetime import date
from pathlib import Path

from worker.domain.drought import DroughtEvent, WeatherObservation
from worker.domain.errors import ModelNotFound
from worker.domain.imagery import ChangeReport, Scene
from worker.domain.jobs import Job, JobResult


def scene(scene_id: str, acquired_on: str) -> Scene:
    return Scene(scene_id, "NISAR", date.fromisoformat(acquired_on), "VV", Path(f"/tmp/{scene_id}.tif"))


def weather(region: str, day: str, precipitation_mm: float = 1.0) -> WeatherObservation:
    return WeatherObservation(region, date.fromisoformat(day), precipitation_mm, 25.0)


class FakeQueue:
    def __init__(self, *jobs: Job) -> None:
        self.pending = list(jobs)
        self.completed: list[JobResult] = []
        self.failed: list[tuple[str, str]] = []

    def next_job(self) -> Job | None:
        return self.pending.pop(0) if self.pending else None

    def complete(self, result: JobResult) -> None:
        self.completed.append(result)

    def fail(self, job_id: str, reason: str) -> None:
        self.failed.append((job_id, reason))


class FakeImagery:
    def __init__(self, *scenes: Scene) -> None:
        self.scenes = list(scenes)

    def scenes_for(self, job: Job) -> list[Scene]:
        return self.scenes


class FakeWeather:
    def __init__(self, observations: list[WeatherObservation], droughts: list[DroughtEvent] | None = None) -> None:
        self._observations = observations
        self._droughts = droughts or []
        self.requested: list[tuple[str, date, date]] = []

    def observations(self, region: str, start: date, end: date) -> list[WeatherObservation]:
        self.requested.append((region, start, end))
        return self._observations

    def drought_history(self, region: str) -> list[DroughtEvent]:
        return self._droughts


class FakeDetector:
    def __init__(self, report: ChangeReport) -> None:
        self.report = report
        self.compared: list[tuple[Scene, Scene]] = []

    def detect(self, before: Scene, after: Scene) -> ChangeReport:
        self.compared.append((before, after))
        return self.report


class FakeModel:
    def __init__(self, probability: float = 0.5) -> None:
        self.probability = probability
        self.trained_on: tuple[list[WeatherObservation], list[DroughtEvent]] | None = None

    def train(self, observations: list[WeatherObservation], droughts: list[DroughtEvent]) -> None:
        self.trained_on = (observations, droughts)

    def predict(self, recent: list[WeatherObservation], horizon_days: int) -> float:
        return self.probability


class InMemoryModelStore:
    def __init__(self) -> None:
        self.models: dict[str, FakeModel] = {}

    def save(self, region: str, model: FakeModel) -> None:
        self.models[region] = model

    def load(self, region: str) -> FakeModel:
        if region not in self.models:
            raise ModelNotFound(region)
        return self.models[region]
