import logging
import time
from collections.abc import Callable

import numpy as np

from worker.adapters.imaging import sentinel1
from worker.adapters.imaging.arrays import class_raster, db_raster, to_array, to_float
from worker.adapters.imaging.backscatter import (
    Transform,
    block_mean,
    box_filter,
    linear_to_db,
    mask_fill,
    resample_into,
    trim_swath_edge,
    window_px,
)
from worker.adapters.imaging.grid import area_mask, to_pixel
from worker.adapters.imaging.refined_lee import refined_lee
from worker.adapters.imaging.water import (
    Hub,
    Sentinel1View,
    connectivity,
    hold_unverified,
    low_water_route,
    pass_threshold,
    vegetated_water,
    water_mask,
    wide_water,
)
from worker.application.ports import RasterSource
from worker.domain.area import AreaGrid, AreaSpec, Method, Speckle, Target
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
from worker.domain.rasters import XY, DType, Raster, RasterRef, Unit, WaterClass

log = logging.getLogger(__name__)

# Source CRS -> target CRS: a function from coordinate arrays to coordinate arrays
TransformFactory = Callable[[str, str], Transform]


def pyproj_transform(source_crs: str, target_crs: str) -> Transform:
    from pyproj import Transformer

    return Transformer.from_crs(source_crs, target_crs, always_xy=True).transform


class RadarPassAnalyzer:
    """Judges a NISAR pass on the area grid with the parameters of `area.method`.

    assess_pass:
      1. per granule (the first wins where several have data), HH, and HV when the area flags water under vegetation:
         linear power to dB, fill below `validFloorDb` masked, speckle filtered (refined Lee with `speckle.looks`, or a
         box over `speckle.windowM`), resampled to the area grid by nearest pixel;
      2. the pixels outside a circular area and the band within `edgeMarginM` of the edge of the data become no data;
      3. water below the pass threshold (fixed, or measured on the mixed tiles with `waterDbAdaptive`), plus pixels
         below the weak one touching it; water under vegetation from HH and HV (`vegetatedWater`), never a route;
      4. Sentinel-1: the scene dates within `sentinel1.maxDays` whose river level is within `levelToleranceCm` of the
         pass's, the darkest filtered VV of them on each pixel, classed as water, land or undecided.
    Then, for both job types: the low-water rule (`lowWater`: at low water, seasonal beds Sentinel-1 sees as mud leave
    the routes and the ones nobody judged make a route at most at risk), coverage, and the route of every community
    (from its water access when it has one) and of the main river to the hub by channel width.

    reevaluate_pass starts from the stored `water` and `sentinel1` rasters: open water (classes 1 and 2), water under
    vegetation (class 3) and the Sentinel-1 classes, with the thresholds of the method reported as they are.
    """

    def __init__(
        self, transform_factory: TransformFactory = pyproj_transform, clock: Callable[[], float] = time.monotonic
    ) -> None:
        self._transform_factory = transform_factory
        self._clock = clock

    def assess(self, inputs: AssessPassInputs, rasters: RasterSource) -> PassAssessment:
        started = self._clock()
        area, grid, method = inputs.area, inputs.area.grid, inputs.area.method
        hh = np.full((grid.height, grid.width), np.nan, np.float32)
        hv = np.full((grid.height, grid.width), np.nan, np.float32) if method.vegetated_water else None
        hv_seen = False
        spacing_m = None
        for granule in inputs.granules:
            spacing_m = self._frame_into(hh, rasters.read(granule.hh), granule.id, method, grid)
            if hv is not None and granule.hv is not None:
                self._frame_into(hv, rasters.read(granule.hv), granule.id, method, grid)
                hv_seen = True
        if not hv_seen:
            hv = None  # single-polarisation pass

        in_area = area_mask(grid)
        hh[~in_area] = np.nan  # outside a circular area: never water, never a gap
        trim_swath_edge(hh, in_area, method.edge_margin_m, grid.pixel_m)
        if hv is not None:
            hv[~np.isfinite(hh)] = np.nan
        threshold = pass_threshold(hh, method.water_db, method.water_db_weak, method.water_db_adaptive, grid.pixel_m)
        water, valid = water_mask(hh, threshold.strong_db, threshold.weak_db, grid.pixel_m)
        vegetated = vegetated_water(hh, hv, water, method.vegetated_water, grid.pixel_m)
        view = self._sentinel1(inputs, rasters)

        result_rasters = {"radar": _radar(hh, grid)} if grid.width >= 2 and grid.height >= 2 else {}
        if view is not None:
            result_rasters["sentinel1"] = sentinel1.encode(view, grid)
        occurrence = _occurrence(inputs.occurrence, rasters, grid)
        return _judge(
            area,
            inputs.pass_info,
            inputs.gauge,
            _Masks(water, valid, vegetated, occurrence, view),
            threshold,
            result_rasters,
            self._clock() - started,
            spacing_m,
        )

    def reevaluate(self, inputs: ReevaluatePassInputs, rasters: RasterSource) -> PassAssessment:
        started = self._clock()
        area, grid, method = inputs.area, inputs.area.grid, inputs.area.method
        stored = rasters.read(inputs.water)
        if not grid.holds(stored.meta) or stored.meta.dtype != DType.UINT8 or stored.meta.unit != Unit.CLASS:
            raise InvalidJob("the stored water raster is not a class raster on the area grid")
        codes = to_array(stored)
        valid = codes != WaterClass.NO_DATA
        water = np.isin(codes, (WaterClass.WIDE, WaterClass.NARROW))
        flagged = codes == WaterClass.VEGETATED
        # a pass without any vegetated pixel is taken as single-polarisation: the stored raster cannot tell them apart
        vegetated = flagged if method.vegetated_water is not None and flagged.any() else None

        view, result_rasters = None, {}
        if inputs.sentinel1 is not None:
            classes = rasters.read(inputs.sentinel1.classes)
            view = sentinel1.decode(classes, inputs.sentinel1.dates, grid)
            result_rasters["sentinel1"] = classes
        occurrence = _occurrence(inputs.occurrence, rasters, grid)
        return _judge(
            area,
            inputs.pass_info,
            inputs.gauge,
            _Masks(water, valid, vegetated, occurrence, view),
            Threshold("fixed", method.water_db, method.water_db_weak),
            result_rasters,
            self._clock() - started,
            None,
        )

    def _frame_into(self, out: np.ndarray, band: Raster, granule_id: str, method: Method, grid: AreaGrid) -> float:
        """Adds one band of one granule to the pass on the area grid; returns the product's pixel spacing."""
        if band.meta.unit != Unit.LINEAR_POWER:
            raise InvalidJob(f"granule {granule_id}: bands must be linear power, got {band.meta.unit}")
        spacing_m = abs(band.meta.step.x)
        db = mask_fill(linear_to_db(to_float(band)), method.valid_floor_db)
        db = _speckle_filter(db, method.speckle, spacing_m)
        transform = self._transform_factory(grid.crs, band.meta.crs)
        resample_into(out, db, grid, transform, band.meta.origin, band.meta.step)
        return spacing_m

    def _sentinel1(self, inputs: AssessPassInputs, rasters: RasterSource) -> Sentinel1View | None:
        method = inputs.area.method.sentinel1
        if method is None or not inputs.sentinel1_candidates:
            return None
        dates = sentinel1.usable_dates(
            inputs.sentinel1_candidates,
            inputs.pass_info.date,
            inputs.gauge,
            method.max_days,
            method.level_tolerance_cm,
        )
        try:
            return sentinel1.pass_view(dates, method, rasters, inputs.area.grid, self._transform_factory)
        except InvalidJob as error:  # a broken scene never loses the NISAR pass: it is judged without Sentinel-1
            log.warning("pass %s: Sentinel-1 skipped: %s", inputs.pass_info.id, error)
            return None


class _Masks:
    """What a pass is judged from, on the area grid."""

    def __init__(
        self,
        water: np.ndarray,
        valid: np.ndarray,
        vegetated: np.ndarray | None,
        occurrence: np.ndarray | None,
        view: Sentinel1View | None,
    ) -> None:
        self.water, self.valid, self.vegetated, self.occurrence, self.view = water, valid, vegetated, occurrence, view


def _judge(
    area: AreaSpec,
    pass_info: PassInfo,
    gauge: Gauge | None,
    masks: _Masks,
    threshold: Threshold,
    result_rasters: dict[str, Raster],
    seconds: float,
    spacing_m: float | None,
) -> PassAssessment:
    grid, method = area.grid, area.method
    water, valid, vegetated = masks.water, masks.valid, masks.vegetated
    in_area = area_mask(grid)
    wide = wide_water(water, grid.pixel_m, method.safe_width_m)
    coverage = float(valid[in_area].mean()) if in_area.any() else 0.0
    evaluated = coverage >= method.min_coverage
    level_cm = gauge.level_on(pass_info.date) if gauge else None
    route, unverified, applied = low_water_route(
        water, masks.occurrence, level_cm, method.low_water, grid.pixel_m, masks.view
    )

    hub_link, communities, held_ids = None, (), ()
    if evaluated:
        hub = _hub(area)
        targets = [to_pixel(grid, *_water_access(c.lon, c.lat, c.access)) for c in area.communities]

        def routes_over(usable: np.ndarray, points: list[tuple[int, int]], access_m: float):
            return connectivity(
                usable, valid, grid.pixel_m, hub, points, method.widths_m, access_m, method.safe_width_m, in_area
            )

        results = routes_over(route, targets, method.access_radius_m)
        if unverified is not None and unverified.any():
            results, held = hold_unverified(results, routes_over(route & ~unverified, targets, method.access_radius_m))
            held_ids = tuple(area.communities[i].id for i in held)
        communities = tuple(CommunityRoute(c.id, r) for c, r in zip(area.communities, results, strict=True))
        hub_link = routes_over(route, [to_pixel(grid, area.river.lon, area.river.lat)], area.river.radius_m)[0]

    codes = np.full(water.shape, WaterClass.NO_DATA, np.uint8)
    codes[valid] = WaterClass.DRY
    if vegetated is not None:
        codes[valid & vegetated & ~water] = WaterClass.VEGETATED
    codes[valid & water] = WaterClass.NARROW
    codes[valid & wide] = WaterClass.WIDE
    pixel_km2 = grid.pixel_km2
    agreement = (
        sentinel1.agreement(water, valid, masks.view, grid.pixel_m, water & ~route) if masks.view is not None else None
    )
    return PassAssessment(
        pass_id=pass_info.id,
        coverage=round(coverage, 4),
        shown=coverage >= method.min_coverage_shown,
        evaluated=evaluated,
        water_km2=round(float((water & valid).sum()) * pixel_km2, 2),
        narrow_km2=round(float((water & valid & ~wide).sum()) * pixel_km2, 2),
        vegetated_km2=None if vegetated is None else round(float((vegetated & ~water).sum()) * pixel_km2, 2),
        threshold=threshold,
        river_level_cm=level_cm,
        low_water=None if method.low_water is None else applied,
        hub_link=hub_link,
        communities=communities,
        unverified_route=held_ids,
        sentinel1=agreement,
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


def _occurrence(ref: RasterRef | None, rasters: RasterSource, grid: AreaGrid) -> np.ndarray | None:
    """The area's water occurrence (percent of the months with water, 255 no data), or None when there is none or it
    does not fit the area grid (logged): the low-water rule is then skipped."""
    if ref is None:
        return None
    raster = rasters.read(ref)
    if not grid.holds(raster.meta) or raster.meta.dtype != DType.UINT8:
        log.warning("water occurrence %s is not a uint8 raster on the area grid: ignored", ref.href)
        return None
    return to_array(raster)


def _radar(db: np.ndarray, grid: AreaGrid) -> Raster:
    """Filtered HH in dB averaged over 2 x 2 area pixels (the odd last row and column dropped)."""
    origin = XY(grid.origin.x + grid.step.x / 2, grid.origin.y + grid.step.y / 2)
    return db_raster(block_mean(db, 2), origin, XY(grid.step.x * 2, grid.step.y * 2), grid.crs)


def _water_access(lon: float, lat: float, access) -> tuple[float, float]:
    """Routes and distances are measured from where people reach the water, when it is known."""
    return (access.lon, access.lat) if access is not None else (lon, lat)


def _hub(area: AreaSpec) -> Hub:
    hub: Target = area.hub
    row, col = to_pixel(area.grid, *_water_access(hub.lon, hub.lat, hub.access))
    return Hub(row, col, hub.radius_m)
