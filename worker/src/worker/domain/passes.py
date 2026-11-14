"""One radar pass over an area: what a job brings to judge it, and the judgement (PassAssessment, ChangeResult)."""

from dataclasses import dataclass, field
from datetime import date, timedelta
from enum import StrEnum

from worker.domain.area import AreaGrid, AreaSpec
from worker.domain.rasters import Raster, RasterRef


class RouteStatus(StrEnum):
    # A route at least safeWidthM wide
    CONNECTED = "connected"
    # Narrower, or through unverified low-water ground
    AT_RISK = "at_risk"
    # No route, or no water within accessRadiusM
    ISOLATED = "isolated"
    # Not covered, or a route could run through a gap
    NO_DATA = "no_data"


@dataclass(frozen=True)
class PassInfo:
    id: str
    date: date
    time_utc: str
    track: int
    direction: str  # ascending or descending


@dataclass(frozen=True)
class Granule:
    """The area's window of one NISAR GCOV frame, linear power, in the product's projection."""

    id: str
    frame: int
    hh: RasterRef
    # Present on dual-polarisation frames
    hv: RasterRef | None = None


@dataclass(frozen=True)
class Sentinel1Date:
    """Sentinel-1 VV scenes of one date, linear power, tiles in priority order (where several have data, the first
    wins)."""

    date: date
    tiles: tuple[RasterRef, ...]


@dataclass(frozen=True)
class GaugeDay:
    day: date
    level_cm: float


@dataclass(frozen=True)
class Gauge:
    """Daily median river level at the area's gauge, on the days around the pass the method may look at."""

    station: str
    days: tuple[GaugeDay, ...]

    def level_on(self, day: date, max_days: int = 1) -> float | None:
        """The level on `day`, else on the closest day within `max_days` (the earlier one on a tie), else None."""
        levels = {d.day: d.level_cm for d in self.days}
        for k in range(max_days + 1):
            for candidate in (day - timedelta(days=k), day + timedelta(days=k)):
                if candidate in levels:
                    return levels[candidate]
        return None


@dataclass(frozen=True)
class AssessPassInputs:
    area: AreaSpec
    pass_info: PassInfo
    granules: tuple[Granule, ...]
    gauge: Gauge | None
    # Water occurrence on the area grid; None when the area has none
    occurrence: RasterRef | None
    # Every date in the window; the worker drops the ones the gauge rules out
    sentinel1_candidates: tuple[Sentinel1Date, ...]


@dataclass(frozen=True)
class StoredSentinel1:
    """The Sentinel-1 classes a pass used, as stored from its result."""

    dates: tuple[date, ...]
    classes: RasterRef


@dataclass(frozen=True)
class ReevaluatePassInputs:
    area: AreaSpec
    pass_info: PassInfo
    water: RasterRef
    sentinel1: StoredSentinel1 | None
    gauge: Gauge | None
    occurrence: RasterRef | None


@dataclass(frozen=True)
class RouteResult:
    """A target's route to the hub on one pass."""

    status: RouteStatus
    # Widest channel width level that still links it; None without a route
    bottleneck_m: int | None
    # From its water access to the nearest water; None when not covered
    distance_to_water_m: int | None


@dataclass(frozen=True)
class CommunityRoute:
    community_id: str
    route: RouteResult


@dataclass(frozen=True)
class Threshold:
    source: str  # fixed or adaptive
    strong_db: float
    weak_db: float | None
    measured_db: float | None = None
    tiles: int | None = None


@dataclass(frozen=True)
class Sentinel1Agreement:
    # Scene dates used, closest first
    dates: tuple[date, ...]
    judged_km2: float
    agreement: float | None
    both_water_km2: float
    nisar_only_km2: float
    sentinel1_only_km2: float
    # NISAR water that the low-water rule left out as a drained bed
    mud_km2: float


@dataclass(frozen=True)
class PassAssessment:
    """The judgement of one pass, as assess_pass and reevaluate_pass return it.

    `rasters` holds the result rasters by contract name: `water` always, `radar` only from assess_pass, `sentinel1`
    only when scenes were used.
    """

    pass_id: str
    coverage: float
    shown: bool
    evaluated: bool
    water_km2: float
    narrow_km2: float
    # None on single-polarisation passes
    vegetated_km2: float | None
    threshold: Threshold
    river_level_cm: float | None
    # Whether the low-water rule applied; None when the area has none
    low_water: bool | None
    hub_link: RouteResult | None
    communities: tuple[CommunityRoute, ...]
    # Communities held at risk by unverified low-water ground
    unverified_route: tuple[str, ...]
    sentinel1: Sentinel1Agreement | None
    rasters: dict[str, Raster]
    seconds: float | None = None
    # Pixel spacing of the NISAR product
    spacing_m: float | None = None

    def __post_init__(self) -> None:
        if not 0 <= self.coverage <= 1:
            raise ValueError(f"coverage must be between 0 and 1, got {self.coverage}")
        if not self.evaluated and (self.hub_link is not None or self.communities):
            raise ValueError("a pass that was not evaluated has no hub link and no community routes")


@dataclass(frozen=True)
class DetectChangeInputs:
    grid: AreaGrid
    pass_id: str
    # Previous shown pass of the same track
    since_pass_id: str
    previous: RasterRef
    current: RasterRef


@dataclass(frozen=True)
class ChangeMap:
    """Water lost and gained between two `water` rasters, where both have data."""

    change: Raster
    lost_pixels: int
    gained_pixels: int


@dataclass(frozen=True)
class EvaluatedPass:
    pass_id: str
    date: date
    time_utc: str
    # A community missing here (added after the pass) counts as no data on it
    communities: tuple[CommunityRoute, ...] = field(default_factory=tuple)

    def status_of(self, community_id: str) -> RouteResult | None:
        return next((c.route for c in self.communities if c.community_id == community_id), None)
