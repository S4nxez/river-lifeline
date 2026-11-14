"""A study area as a job carries it (AreaSpec): its fixed grid, its targets and the parameters of the method."""

from dataclasses import dataclass

from worker.domain.rasters import XY, RasterMeta


@dataclass(frozen=True)
class LonLat:
    lon: float
    lat: float


@dataclass(frozen=True)
class Circle:
    lon: float
    lat: float
    radius_km: float


@dataclass(frozen=True)
class AreaGrid:
    """The area's fixed lon/lat grid (EPSG:4326), the same for every pass. The backend derives it; the worker never
    recomputes it."""

    crs: str
    width: int
    height: int
    origin: XY
    step: XY
    # Geometric mean of the pixel's width and height in metres
    pixel_m: float
    # [lon0, lat0, lon1, lat1], outer edges of the grid
    bbox: tuple[float, float, float, float]
    # When set, only pixels within the circle belong to the area
    circle: Circle | None = None

    @property
    def pixel_km2(self) -> float:
        return self.pixel_m * self.pixel_m / 1_000_000

    def holds(self, meta: RasterMeta) -> bool:
        """Whether a raster lies exactly on this grid."""
        return (meta.width, meta.height, meta.crs, meta.origin, meta.step) == (
            self.width,
            self.height,
            self.crs,
            self.origin,
            self.step,
        )


@dataclass(frozen=True)
class Target:
    name: str
    lon: float
    lat: float
    # Water within this distance counts as reaching it
    radius_m: float
    # Where people reach the water (pier, port); routes start here when set
    access: LonLat | None = None


@dataclass(frozen=True)
class Community:
    id: str
    name: str
    # OpenStreetMap place kind: city, town, village, hamlet...
    place: str
    lon: float
    lat: float
    access: LonLat | None = None


@dataclass(frozen=True)
class AdaptiveThreshold:
    range_db: tuple[float, float]
    tile_m: float
    min_tiles: int


@dataclass(frozen=True)
class Speckle:
    filter: str  # refined_lee or box
    looks: float | None
    window_m: float | None


@dataclass(frozen=True)
class VegetatedWater:
    hh_min_db: float
    ratio_min_db: float
    near_water_m: float


@dataclass(frozen=True)
class LowWater:
    gauge_max_cm: float
    seasonal_pct: tuple[float, float]
    patch_radius_m: float
    veto_radius_m: float


@dataclass(frozen=True)
class Sentinel1Method:
    max_days: int
    level_tolerance_cm: float
    water_db: float
    weak_db: float | None
    land_db: float


@dataclass(frozen=True)
class Method:
    """Parameters of the method, from the area file. Nothing in the worker hardcodes any of them."""

    access_radius_m: float
    min_coverage: float
    min_coverage_shown: float
    valid_floor_db: float
    edge_margin_m: float
    water_db: float
    water_db_weak: float | None
    water_db_adaptive: AdaptiveThreshold | None
    speckle: Speckle
    widths_m: tuple[float, ...]
    safe_width_m: float
    vegetated_water: VegetatedWater | None
    low_water: LowWater | None
    sentinel1: Sentinel1Method | None


@dataclass(frozen=True)
class AreaSpec:
    id: str
    name: str
    grid: AreaGrid
    hub: Target
    # A point on the main river, to report the hub <-> main river link
    river: Target
    communities: tuple[Community, ...]
    method: Method
