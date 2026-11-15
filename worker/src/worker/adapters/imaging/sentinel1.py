"""Sentinel-1 C-band water and land on the area grid: the second radar of a pass.

NISAR (L band, 24 cm) and Sentinel-1 (C band, 5.6 cm) see different surfaces as rough. The wet, smooth bed of a lake or
side channel drained at low water is as smooth as open water at 24 cm, so NISAR reads it as water; at 5.6 cm the same
mud is rough and Sentinel-1 reads it about 10 dB brighter than water. Sentinel-1 is therefore the judge of whether such
a bed still holds water (water.low_water_route). Under trees and on wind-roughened water it is the other way round, and
NISAR is trusted there.

The job carries the VV tiles (radiometrically terrain-corrected, linear power) of every date near the pass. A date's
tiles are averaged onto the area grid in linear power (the first tile with data wins) and filtered with refined Lee.
Classes of a filtered VV pixel: water (below water_db, or below weak_db and touching such water, as for NISAR), land
(at or above land_db) and undecided in between; not seen where no scene covers it.
"""

from collections.abc import Callable
from datetime import date

import numpy as np

from worker.adapters.imaging.arrays import class_raster, to_array, to_float
from worker.adapters.imaging.backscatter import Transform, average_onto, linear_to_db
from worker.adapters.imaging.refined_lee import refined_lee
from worker.adapters.imaging.water import Sentinel1View, water_mask
from worker.application.ports import RasterSource
from worker.domain.area import AreaGrid, Sentinel1Method
from worker.domain.errors import InvalidJob
from worker.domain.passes import Gauge, Sentinel1Agreement, Sentinel1Date
from worker.domain.rasters import DType, Raster, Sentinel1Class, Unit

# Equivalent looks of a filtered scene: 4.4 looks of the GRD product, about 4 pixels of 10 m averaged per 20 m pixel,
# not independent
LOOKS = 6.0

# Product CRS -> area CRS: a function from x/y arrays to lon/lat arrays
TransformFactory = Callable[[str, str], Transform]


def usable_dates(
    candidates: tuple[Sentinel1Date, ...], pass_date: date, gauge: Gauge | None, max_days: int, tolerance_cm: float
) -> list[Sentinel1Date]:
    """The scene dates that may judge a pass, closest first (ties: the earlier one). A scene is only used when the river
    at the gauge was within `tolerance_cm` of its level on the pass day: a scene taken after the river fell further
    could show mud that was still water on the pass day, and one taken while it was much higher could show water over
    a bed that had drained by then. Without a gauge level, the date window alone decides."""
    level = gauge.level_on(pass_date) if gauge else None
    usable = []
    for candidate in candidates:
        gap = (candidate.date - pass_date).days
        if abs(gap) > max_days:
            continue
        there = gauge.level_on(candidate.date) if gauge else None
        if level is not None and there is not None and abs(there - level) > tolerance_cm:
            continue
        usable.append((abs(gap), gap, candidate))
    return [candidate for _, _, candidate in sorted(usable, key=lambda item: (item[0], item[1]))]


def mosaic_date(
    scenes: Sentinel1Date, rasters: RasterSource, grid: AreaGrid, transform_factory: TransformFactory
) -> np.ndarray:
    """VV (dB, unfiltered) of one date on the area grid: its tiles averaged in linear power, first with data wins."""
    vv = np.full((grid.height, grid.width), np.nan, np.float32)
    for ref in scenes.tiles:
        tile = rasters.read(ref)
        if tile.meta.unit != Unit.LINEAR_POWER:
            raise InvalidJob(f"Sentinel-1 tile {ref.href}: VV must be linear power, got {tile.meta.unit}")
        to_lonlat = transform_factory(tile.meta.crs, grid.crs)
        db = linear_to_db(average_onto(to_float(tile), tile.meta.origin, tile.meta.step, grid, to_lonlat))
        del tile
        take = np.isnan(vv) & np.isfinite(db)
        vv[take] = db[take]
    return vv


def classify(vv: np.ndarray, method: Sentinel1Method, pixel_m: float) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """(water, land, seen) of a filtered VV mosaic."""
    water, seen = water_mask(vv, method.water_db, method.weak_db, pixel_m)
    with np.errstate(invalid="ignore"):
        land = seen & ~water & (vv >= method.land_db)
    return water, land, seen


def pass_view(
    dates: list[Sentinel1Date],
    method: Sentinel1Method,
    rasters: RasterSource,
    grid: AreaGrid,
    transform_factory: TransformFactory,
) -> Sentinel1View | None:
    """What Sentinel-1 saw around a pass: the darkest filtered VV of the usable scenes on each pixel, classified; None
    without a usable scene. Wind roughens open water in C band (a lake reads about -15.5 dB on a windy day, -20 dB on a
    calm one) for a day or two, while a drained bed stays bright on every date: so a pixel is land only when every
    scene that saw it is bright."""
    if not dates:
        return None
    vv = np.full((grid.height, grid.width), np.nan, np.float32)
    for scenes in dates:
        vv = np.fmin(vv, refined_lee(mosaic_date(scenes, rasters, grid, transform_factory), LOOKS))
    water, land, seen = classify(vv, method, grid.pixel_m)
    return Sentinel1View(tuple(d.date for d in dates), water, land, seen, vv)


def agreement(
    water: np.ndarray, valid: np.ndarray, view: Sentinel1View, pixel_m: float, mud: np.ndarray
) -> Sentinel1Agreement:
    """How a NISAR water mask and Sentinel-1 agree, over the pixels both judged (NISAR valid, Sentinel-1 water or land):
    the share that agrees, the area each one alone calls water, and the NISAR water the low-water rule left out as a
    drained bed (`mud`)."""
    both = valid & (view.water | view.land)
    nisar, s1 = water & both, view.water & both
    n = int(both.sum())
    agree = int((nisar & s1).sum()) + int((both & ~nisar & ~s1).sum())
    km2 = (pixel_m / 1000.0) ** 2
    return Sentinel1Agreement(
        dates=tuple(view.dates),
        judged_km2=round(n * km2, 1),
        agreement=round(agree / n, 4) if n else None,
        both_water_km2=round(float((nisar & s1).sum()) * km2, 2),
        nisar_only_km2=round(float((nisar & ~s1).sum()) * km2, 2),
        sentinel1_only_km2=round(float((s1 & ~nisar).sum()) * km2, 2),
        mud_km2=round(float(mud.sum()) * km2, 2),
    )


def encode(view: Sentinel1View, grid: AreaGrid) -> Raster:
    """The `sentinel1` result raster: 0 undecided, 1 water, 2 land, 255 not seen."""
    codes = np.full(view.seen.shape, Sentinel1Class.NOT_SEEN, np.uint8)
    codes[view.seen] = Sentinel1Class.UNDECIDED
    codes[view.seen & view.water] = Sentinel1Class.WATER
    codes[view.seen & view.land] = Sentinel1Class.LAND
    return class_raster(codes, grid.origin, grid.step, grid.crs)


def decode(raster: Raster, dates: tuple[date, ...], grid: AreaGrid) -> Sentinel1View:
    """The view a stored `sentinel1` raster holds."""
    if not grid.holds(raster.meta) or raster.meta.dtype != DType.UINT8 or raster.meta.unit != Unit.CLASS:
        raise InvalidJob("the stored sentinel1 raster is not a class raster on the area grid")
    codes = to_array(raster)
    return Sentinel1View(
        tuple(dates), codes == Sentinel1Class.WATER, codes == Sentinel1Class.LAND, codes != Sentinel1Class.NOT_SEEN
    )
