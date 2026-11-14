import time
from collections.abc import Callable

import numpy as np

from worker.adapters.imaging.arrays import class_raster, db_raster, to_array, to_float
from worker.adapters.imaging.backscatter import (
    Transform,
    block_mean,
    box_filter,
    linear_to_db,
    mask_fill,
    resample_into,
    window_px,
)
from worker.adapters.imaging.grid import area_mask, to_pixel
from worker.adapters.imaging.refined_lee import refined_lee
from worker.adapters.imaging.water import Hub, connectivity, water_mask, wide_water
from worker.application.ports import RasterSource
from worker.domain.area import AreaSpec, Speckle, Target
from worker.domain.errors import InvalidJob
from worker.domain.passes import (
    AssessPassInputs,
    CommunityRoute,
    Gauge,
    PassAssessment,
    PassInfo,
    ReevaluatePassInputs,
    Threshold,
)
from worker.domain.rasters import XY, DType, Raster, Unit, WaterClass

# Area CRS -> a product CRS: a function from lon/lat arrays to x/y arrays
TransformFactory = Callable[[str, str], Transform]


def pyproj_transform(source_crs: str, target_crs: str) -> Transform:
    from pyproj import Transformer

    return Transformer.from_crs(source_crs, target_crs, always_xy=True).transform


class RadarPassAnalyzer:
    """Judges a NISAR pass on the area grid with the parameters of `area.method`.

    assess_pass, per granule (the first granule wins where several have data): HH from linear power to dB, fill below
    `validFloorDb` masked, speckle filtered (refined Lee with `speckle.looks`, or a box over `speckle.windowM`), and
    resampled to the area grid by nearest pixel. Then, for both job types: water below `waterDb`, plus pixels below
    `waterDbWeak` touching it; coverage inside the area; the route of every community (from its water access when it
    has one) and of the main river to the hub, by channel width (`widthsM`), judged against `safeWidthM` and
    `accessRadiusM`.

    Not applied yet: water under vegetation, the adaptive threshold,
    the swath edge trim, the low-water rule and the Sentinel-1 classes. The result says so: `vegetatedKm2`, `lowWater`
    and `sentinel1` are null and the threshold is the fixed one.
    """

    def __init__(
        self, transform_factory: TransformFactory = pyproj_transform, clock: Callable[[], float] = time.monotonic
    ) -> None:
        self._transform_factory = transform_factory
        self._clock = clock

    def assess(self, inputs: AssessPassInputs, rasters: RasterSource) -> PassAssessment:
        started = self._clock()
        area, grid = inputs.area, inputs.area.grid
        db = np.full((grid.height, grid.width), np.nan, np.float32)
        spacing_m = None
        for granule in inputs.granules:
            band = rasters.read(granule.hh)
            if band.meta.unit != Unit.LINEAR_POWER:
                raise InvalidJob(f"granule {granule.id}: HH must be linear power, got {band.meta.unit}")
            spacing_m = abs(band.meta.step.x)
            frame_db = mask_fill(linear_to_db(to_float(band)), area.method.valid_floor_db)
            frame_db = _speckle_filter(frame_db, area.method.speckle, spacing_m)
            transform = self._transform_factory(grid.crs, band.meta.crs)
            resample_into(db, frame_db, grid, transform, band.meta.origin, band.meta.step)
            del band, frame_db
        db[~area_mask(grid)] = np.nan  # outside a circular area: never water, never a gap

        water, valid = water_mask(db, area.method.water_db, area.method.water_db_weak, grid.pixel_m)
        result_rasters = {"radar": _radar(db, area)} if grid.width >= 2 and grid.height >= 2 else {}
        return self._judge(
            area, inputs.pass_info, inputs.gauge, water, valid, result_rasters, self._clock() - started, spacing_m
        )

    def reevaluate(self, inputs: ReevaluatePassInputs, rasters: RasterSource) -> PassAssessment:
        started = self._clock()
        grid = inputs.area.grid
        stored = rasters.read(inputs.water)
        if not grid.holds(stored.meta) or stored.meta.dtype != DType.UINT8 or stored.meta.unit != Unit.CLASS:
            raise InvalidJob("the stored water raster is not a class raster on the area grid")
        codes = to_array(stored)
        valid = codes != WaterClass.NO_DATA
        water = np.isin(codes, (WaterClass.WIDE, WaterClass.NARROW, WaterClass.VEGETATED))
        return self._judge(inputs.area, inputs.pass_info, inputs.gauge, water, valid, {}, self._clock() - started, None)

    @staticmethod
    def _judge(
        area: AreaSpec,
        pass_info: PassInfo,
        gauge: Gauge | None,
        water: np.ndarray,
        valid: np.ndarray,
        result_rasters: dict[str, Raster],
        seconds: float,
        spacing_m: float | None,
    ) -> PassAssessment:
        grid, method = area.grid, area.method
        in_area = area_mask(grid)
        wide = wide_water(water, grid.pixel_m, method.safe_width_m)
        coverage = float(valid[in_area].mean()) if in_area.any() else 0.0
        evaluated = coverage >= method.min_coverage

        hub_link, communities = None, ()
        if evaluated:
            hub = _hub(area)
            targets = [to_pixel(grid, *_water_access(c.lon, c.lat, c.access)) for c in area.communities]
            routes = connectivity(
                water,
                valid,
                grid.pixel_m,
                hub,
                targets,
                method.widths_m,
                method.access_radius_m,
                method.safe_width_m,
                in_area,
            )
            communities = tuple(CommunityRoute(c.id, r) for c, r in zip(area.communities, routes, strict=True))
            hub_link = connectivity(
                water,
                valid,
                grid.pixel_m,
                hub,
                [to_pixel(grid, area.river.lon, area.river.lat)],
                method.widths_m,
                area.river.radius_m,
                method.safe_width_m,
                in_area,
            )[0]

        codes = np.full(water.shape, WaterClass.NO_DATA, np.uint8)
        codes[valid] = WaterClass.DRY
        codes[valid & water] = WaterClass.NARROW
        codes[valid & wide] = WaterClass.WIDE
        pixel_km2 = grid.pixel_km2
        return PassAssessment(
            pass_id=pass_info.id,
            coverage=round(coverage, 4),
            shown=coverage >= method.min_coverage_shown,
            evaluated=evaluated,
            water_km2=round(float((water & valid).sum()) * pixel_km2, 2),
            narrow_km2=round(float((water & valid & ~wide).sum()) * pixel_km2, 2),
            vegetated_km2=None,
            threshold=Threshold("fixed", method.water_db, method.water_db_weak),
            river_level_cm=_level_on(gauge, pass_info),
            low_water=None,
            hub_link=hub_link,
            communities=communities,
            unverified_route=(),
            sentinel1=None,
            rasters={"water": class_raster(codes, grid.origin, grid.step, grid.crs), **result_rasters},
            seconds=round(seconds, 1),
            spacing_m=spacing_m,
        )


def _speckle_filter(db: np.ndarray, speckle: Speckle, spacing_m: float) -> np.ndarray:
    if speckle.filter == "box":
        if speckle.window_m is None:
            raise InvalidJob("the box speckle filter needs speckle.windowM")
        return box_filter(db, window_px(speckle.window_m, spacing_m))
    if speckle.filter == "refined_lee":
        if speckle.looks is None:
            raise InvalidJob("the refined Lee speckle filter needs speckle.looks")
        return refined_lee(db, speckle.looks)
    raise InvalidJob(f"unknown speckle filter {speckle.filter!r}")


def _radar(db: np.ndarray, area: AreaSpec) -> Raster:
    """Filtered HH in dB averaged over 2 x 2 area pixels (the odd last row and column dropped)."""
    grid = area.grid
    origin = XY(grid.origin.x + grid.step.x / 2, grid.origin.y + grid.step.y / 2)
    return db_raster(block_mean(db, 2), origin, XY(grid.step.x * 2, grid.step.y * 2), grid.crs)


def _water_access(lon: float, lat: float, access) -> tuple[float, float]:
    """Routes and distances are measured from where people reach the water, when it is known."""
    return (access.lon, access.lat) if access is not None else (lon, lat)


def _hub(area: AreaSpec) -> Hub:
    hub: Target = area.hub
    row, col = to_pixel(area.grid, *_water_access(hub.lon, hub.lat, hub.access))
    return Hub(row, col, hub.radius_m)


def _level_on(gauge: Gauge | None, pass_info: PassInfo) -> float | None:
    """The river level at the gauge on the day of the pass."""
    if gauge is None:
        return None
    return next((day.level_cm for day in gauge.days if day.day == pass_info.date), None)
