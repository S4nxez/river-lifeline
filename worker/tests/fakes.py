"""In-memory implementations of the application ports, for testing the use cases and the worker loop."""

from datetime import date
from pathlib import Path

from worker.domain.connectivity import Community, MonitoredArea, RouteMeasurement
from worker.domain.drought import DroughtEvent, WeatherObservation
from worker.domain.errors import ModelNotFound
from worker.domain.imagery import NISAR, Scene
from worker.domain.jobs import Job, JobResult


def scene(scene_id: str, acquired_on: str, mission: str = NISAR) -> Scene:
    return Scene(scene_id, mission, date.fromisoformat(acquired_on), "HH", Path(f"/tmp/{scene_id}.tif"))


TEFE = Community("tefe", "Tefé", -3.354, -64.711)
UARINI = Community("uarini", "Uarini", -2.99, -65.113)
NOGUEIRA = Community("nogueira", "Nogueira", -3.295, -64.86)
AREA = MonitoredArea(TEFE, [UARINI, NOGUEIRA])


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


class FakeAreas:
    def __init__(self, area: MonitoredArea = AREA) -> None:
        self.area = area

    def monitored_area(self) -> MonitoredArea:
        return self.area


class FakeRouteMapper:
    """Answers each scene id with canned measurements and records the scenes it measured."""

    def __init__(self, by_scene: dict[str, list[RouteMeasurement]] | None = None) -> None:
        self.by_scene = by_scene or {}
        self.measured: list[tuple[Scene, Community, list[Community]]] = []

    def measure(self, scene: Scene, hub: Community, communities: list[Community]) -> list[RouteMeasurement]:
        self.measured.append((scene, hub, communities))
        return self.by_scene[scene.id]


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
