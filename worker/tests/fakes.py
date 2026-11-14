"""In-memory implementations of the application ports, and builders of domain objects, for the tests."""

from collections.abc import Collection
from datetime import date

from worker.domain.area import AreaGrid, AreaSpec, Community, Method, Speckle, Target
from worker.domain.drought import DroughtEvent, DroughtModel, WeatherObservation
from worker.domain.errors import LeaseLost
from worker.domain.jobs import Job, JobResult, JobType
from worker.domain.passes import (
    AssessPassInputs,
    ChangeMap,
    CommunityRoute,
    Granule,
    PassAssessment,
    PassInfo,
    ReevaluatePassInputs,
    RouteResult,
    RouteStatus,
    Threshold,
)
from worker.domain.rasters import XY, DType, Raster, RasterMeta, RasterRef, Unit

# --- Builders -----------------------------------------------------------------------------------------------------

GRID = AreaGrid(
    crs="EPSG:4326",
    width=4,
    height=3,
    origin=XY(-65.0, -3.0),
    step=XY(0.01, -0.01),
    pixel_m=1000.0,
    bbox=(-65.005, -3.025, -64.965, -2.995),
)


def meta(
    grid: AreaGrid = GRID, dtype: DType = DType.UINT8, unit: Unit = Unit.CLASS, nodata: float | str | None = 255.0
) -> RasterMeta:
    return RasterMeta(grid.width, grid.height, dtype, nodata, unit, grid.crs, grid.origin, grid.step)


def ref(name: str, raster_meta: RasterMeta | None = None) -> RasterRef:
    return RasterRef(f"/internal/worker/rasters/{name}", raster_meta or meta())


def classes(*values: int, grid: AreaGrid = GRID) -> Raster:
    """A uint8 class raster on `grid` with these values, row by row."""
    return Raster(meta(grid), bytes(values))


def community(cid: str) -> Community:
    return Community(cid, cid.title(), "village", -64.98, -3.01)


METHOD = Method(
    access_radius_m=500,
    min_coverage=0.8,
    min_coverage_shown=0.2,
    valid_floor_db=-40,
    edge_margin_m=1000,
    water_db=-16,
    water_db_weak=-14,
    water_db_adaptive=None,
    speckle=Speckle("refined_lee", 4.4, None),
    widths_m=(40, 60, 100),
    safe_width_m=60,
    vegetated_water=None,
    low_water=None,
    sentinel1=None,
)

AREA = AreaSpec(
    id="tefe",
    name="Tefé",
    grid=GRID,
    hub=Target("Port of Tefé", -64.97, -3.01, 300),
    river=Target("Solimões", -64.99, -3.0, 500),
    communities=(community("uarini"), community("nogueira")),
    method=METHOD,
)

PASS = PassInfo("2026-10-23_t161A", date(2026, 10, 23), "10:02", 161, "ascending")


def assess_inputs() -> AssessPassInputs:
    hh = ref("hh", RasterMeta(2, 2, DType.FLOAT32, "NaN", Unit.LINEAR_POWER, "EPSG:32720", XY(0, 0), XY(20, -20)))
    return AssessPassInputs(AREA, PASS, (Granule("NISAR_L2_GCOV", 2005, hh),), None, None, ())


def reevaluate_inputs() -> ReevaluatePassInputs:
    return ReevaluatePassInputs(AREA, PASS, ref("water"), None, None, None)


def route(status: RouteStatus, bottleneck_m: int | None = None, distance_m: int | None = 0) -> RouteResult:
    return RouteResult(status, bottleneck_m, distance_m)


def assessment(**changes) -> PassAssessment:
    """An evaluated assessment of PASS over AREA, with `changes` applied."""
    fields = dict(
        pass_id=PASS.id,
        coverage=0.95,
        shown=True,
        evaluated=True,
        water_km2=12.5,
        narrow_km2=1.5,
        vegetated_km2=None,
        threshold=Threshold("fixed", -16, -14),
        river_level_cm=812.0,
        low_water=None,
        hub_link=route(RouteStatus.CONNECTED, 400),
        communities=(
            CommunityRoute("uarini", route(RouteStatus.CONNECTED, 100)),
            CommunityRoute("nogueira", route(RouteStatus.ISOLATED, None, 900)),
        ),
        unverified_route=(),
        sentinel1=None,
        rasters={"water": classes(*([0] * 12))},
    )
    fields.update(changes)
    return PassAssessment(**fields)


def weather(day: str, precipitation_mm: float = 1.0) -> WeatherObservation:
    return WeatherObservation(date.fromisoformat(day), precipitation_mm, 25.0)


def job(inputs, job_type: JobType = JobType.DETECT_CHANGE, job_id: str = "1", lease_seconds: int = 60) -> Job:
    return Job(job_id, job_type, inputs, lease_seconds)


# --- Fakes of the ports -------------------------------------------------------------------------------------------


class FakeQueue:
    def __init__(self, *jobs: Job) -> None:
        self.pending = list(jobs)
        self.asked: list[tuple[list[JobType], list[int]]] = []
        self.completed: list[JobResult] = []
        self.failed: list[tuple[str, str, bool]] = []
        self.heartbeats: list[str] = []
        # Set to make every call about a job answer 409
        self.lease_lost = False

    def next_job(self, types: Collection[JobType], contracts: Collection[int]) -> Job | None:
        self.asked.append((list(types), list(contracts)))
        return self.pending.pop(0) if self.pending else None

    def heartbeat(self, job_id: str) -> int:
        self.heartbeats.append(job_id)
        if self.lease_lost:
            raise LeaseLost(job_id)
        return 60

    def complete(self, result: JobResult) -> None:
        if self.lease_lost:
            raise LeaseLost(result.job_id)
        self.completed.append(result)

    def fail(self, job_id: str, reason: str, retryable: bool) -> None:
        if self.lease_lost:
            raise LeaseLost(job_id)
        self.failed.append((job_id, reason, retryable))


class FakeRasters:
    """Serves rasters by href and records what was read."""

    def __init__(self, by_href: dict[str, Raster] | None = None) -> None:
        self.by_href = by_href or {}
        self.read_hrefs: list[str] = []

    def read(self, raster_ref: RasterRef) -> Raster:
        self.read_hrefs.append(raster_ref.href)
        return self.by_href[raster_ref.href]


class FakeAnalyzer:
    def __init__(self, result: PassAssessment) -> None:
        self.result = result
        self.calls: list[tuple[str, object]] = []

    def assess(self, inputs, rasters) -> PassAssessment:
        self.calls.append(("assess", inputs))
        return self.result

    def reevaluate(self, inputs, rasters) -> PassAssessment:
        self.calls.append(("reevaluate", inputs))
        return self.result


class FakeChangeMapper:
    def __init__(self, change: Raster, lost: int, gained: int) -> None:
        self.result = ChangeMap(change, lost, gained)
        self.compared: list[tuple[Raster, Raster]] = []

    def compare(self, previous: Raster, current: Raster) -> ChangeMap:
        self.compared.append((previous, current))
        return self.result


class FakeDroughtModel:
    """Trains a fixed model and forecasts a fixed probability, recording what it saw."""

    def __init__(self, probability: float = 0.5) -> None:
        self.probability = probability
        self.trained_on: tuple[list[WeatherObservation], list[DroughtEvent]] | None = None
        self.predicted_with: tuple[DroughtModel, list[WeatherObservation], int] | None = None

    def train(self, observations: list[WeatherObservation], droughts: list[DroughtEvent]) -> DroughtModel:
        self.trained_on = (observations, droughts)
        return DroughtModel("fake", 1, 90, {"bias": 0.1})

    def predict(self, model: DroughtModel, recent: list[WeatherObservation], horizon_days: int) -> float:
        self.predicted_with = (model, recent, horizon_days)
        return self.probability
