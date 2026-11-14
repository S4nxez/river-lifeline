"""Water mask, channel widths and the water route between each target and the hub."""

import math
from dataclasses import dataclass

import numpy as np
from scipy import ndimage

from worker.domain.area import AdaptiveThreshold, LowWater, VegetatedWater
from worker.domain.passes import RouteResult, RouteStatus, Threshold
from worker.domain.rasters import OCCURRENCE_NO_DATA

# Water patches smaller than this are specks; holes smaller than this inside water are filled
MIN_WATER_M2 = 10_000
MIN_HOLE_M2 = 20_000
# Share of a target's surroundings (within the access radius) a pass must cover to judge it
MIN_COVERAGE_AROUND = 0.8
EIGHT_NEIGHBOURS = np.ones((3, 3))

# Adaptive threshold: Kittler-Illingworth over the histogram of the tiles that hold both water and land
KI_RANGE_DB = (-35.0, 5.0)
KI_BINS = 400
MIXED_SHARE = (0.2, 0.8)  # a tile is mixed when this share of its pixels is below the fixed threshold
MIN_TILE_VALID = 0.9
MAX_POOLED = 2_000_000


@dataclass(frozen=True)
class Hub:
    row: int
    col: int
    radius_m: float


def water_mask(db: np.ndarray, water_db: float, weak_db: float | None, pixel_m: float) -> tuple[np.ndarray, np.ndarray]:
    """(water, valid). Water is the filtered backscatter below `water_db`. With `weak_db` (hysteresis), pixels below it
    also count when they touch that water: wind roughens stretches of rivers and lakes, which then return a little more
    echo and would otherwise cut the river in two. Specks are dropped and small holes inside water are filled."""
    valid = np.isfinite(db)
    with np.errstate(invalid="ignore"):
        water = valid & (db < water_db)
        if weak_db is not None and weak_db > water_db:
            labels, _ = ndimage.label(valid & (db < weak_db))
            keep = np.unique(labels[water])
            water = np.isin(labels, keep[keep > 0])
    pixel_m2 = pixel_m * pixel_m
    labels, n = ndimage.label(water)
    if n:
        water &= np.bincount(labels.ravel())[labels] >= round(MIN_WATER_M2 / pixel_m2)
    holes, n = ndimage.label(~water & valid)
    if n:
        small = np.bincount(holes.ravel()) < round(MIN_HOLE_M2 / pixel_m2)
        small[0] = False
        water |= small[holes]
    return water, valid


def min_water_px(pixel_m: float) -> int:
    return round(MIN_WATER_M2 / (pixel_m * pixel_m))


def ki_threshold(
    values: np.ndarray, lo: float = KI_RANGE_DB[0], hi: float = KI_RANGE_DB[1], bins: int = KI_BINS
) -> float:
    """Kittler-Illingworth minimum-error threshold of `values` (dB): each class is modelled as a Gaussian and the cut
    minimises J(t) = 1 + 2 (P1 ln s1 + P2 ln s2) - 2 (P1 ln P1 + P2 ln P2). NaN when no cut leaves two classes."""
    v = values[np.isfinite(values)]
    if v.size < 2:
        return float("nan")
    hist, edges = np.histogram(v, bins=bins, range=(lo, hi))
    x = (edges[:-1] + edges[1:]) / 2
    p = hist / max(1, hist.sum())
    p1 = np.cumsum(p)
    p2 = 1.0 - p1
    m1 = np.cumsum(p * x)
    s1 = np.cumsum(p * x * x)
    with np.errstate(divide="ignore", invalid="ignore"):
        mu1, mu2 = m1 / p1, (m1[-1] - m1) / p2
        var1 = s1 / p1 - mu1**2
        var2 = (s1[-1] - s1) / p2 - mu2**2
        j = 1 + p1 * np.log(var1) + p2 * np.log(var2) - 2 * (p1 * np.log(p1) + p2 * np.log(p2))
    j[~np.isfinite(j) | (var1 <= 0) | (var2 <= 0)] = np.inf
    if not np.isfinite(j).any():
        return float("nan")
    # an empty gap between the classes gives a plateau of equal J: take its middle, not the edge next to the water
    best = np.flatnonzero(j <= j.min() + 1e-9 * max(1.0, abs(j.min())))
    return float((edges[best[0] + 1] + edges[best[-1] + 1]) / 2)


def mixed_tiles(db: np.ndarray, tile_px: int, prior_db: float) -> list[tuple[slice, slice]]:
    """The tiles that hold both water and land: valid on at least MIN_TILE_VALID of their pixels, with MIXED_SHARE of
    them below `prior_db` (their histogram is bimodal, so a threshold can be measured there)."""
    out = []
    for a in range(0, db.shape[0] - tile_px + 1, tile_px):
        for b in range(0, db.shape[1] - tile_px + 1, tile_px):
            tile = db[a : a + tile_px, b : b + tile_px]
            ok = np.isfinite(tile)
            if ok.mean() < MIN_TILE_VALID:
                continue
            share = float((tile[ok] < prior_db).mean())
            if MIXED_SHARE[0] <= share <= MIXED_SHARE[1]:
                out.append((slice(a, a + tile_px), slice(b, b + tile_px)))
    return out


def pass_threshold(
    db: np.ndarray, water_db: float, weak_db: float | None, adaptive: AdaptiveThreshold | None, pixel_m: float
) -> Threshold:
    """The water thresholds of one pass. The fixed ones, unless the area measures them: then the Kittler-Illingworth
    threshold of the pixels of the mixed tiles (split-based selection, Martinis et al. 2009), clamped to `range_db`,
    with the weak threshold kept at the same offset. Too few mixed tiles: the fixed threshold."""
    if adaptive is None:
        return Threshold("fixed", water_db, weak_db)
    tiles = mixed_tiles(db, max(8, int(round(adaptive.tile_m / pixel_m))), water_db)
    if len(tiles) >= adaptive.min_tiles:
        pooled = np.concatenate([db[t][np.isfinite(db[t])] for t in tiles])
        if pooled.size > MAX_POOLED:
            pooled = pooled[:: pooled.size // MAX_POOLED + 1]
        measured = ki_threshold(pooled)
        if np.isfinite(measured):
            lo, hi = adaptive.range_db
            strong = float(np.clip(measured, lo, hi))
            weak = None if weak_db is None else round(strong + (weak_db - water_db), 2)
            return Threshold("adaptive", round(strong, 2), weak, round(measured, 2), len(tiles))
    return Threshold("fixed", water_db, weak_db, None, len(tiles))


def vegetated_water(
    hh: np.ndarray, hv: np.ndarray | None, water: np.ndarray, rule: VegetatedWater | None, pixel_m: float
) -> np.ndarray | None:
    """Water under vegetation (floating meadows, flooded forest): the double bounce water -> stem -> radar makes HH
    brighter than dry forest and raises HH much more than HV. Flagged where HH > hh_min_db and HH - HV >= ratio_min_db,
    outside open water, within near_water_m of it, specks under 1 ha dropped. None without HV (single-polarisation
    pass) or without the area rule. It is never a navigable route: routes use open water only."""
    if hv is None or rule is None:
        return None
    with np.errstate(invalid="ignore"):
        flagged = (hh > rule.hh_min_db) & ((hh - hv) >= rule.ratio_min_db) & ~water
    if water.any():
        flagged &= ndimage.distance_transform_edt(~water) * pixel_m <= rule.near_water_m
    else:
        flagged[:] = False
    labels, n = ndimage.label(flagged)
    if n:
        flagged &= np.bincount(labels.ravel())[labels] >= min_water_px(pixel_m)
    return flagged


def disk(radius_px: float) -> np.ndarray:
    """Boolean disc of the given radius (pixels), centred in a (2r+1) x (2r+1) array."""
    r = int(math.ceil(radius_px))
    yy, xx = np.mgrid[-r : r + 1, -r : r + 1]
    return (yy * yy + xx * xx) <= radius_px * radius_px


def near_labels(labels: np.ndarray, row: int, col: int, radius_px: float) -> set[int]:
    """Non-zero labels within `radius_px` of (row, col)."""
    k = disk(radius_px)
    r = k.shape[0] // 2
    a0, b0 = max(0, row - r), max(0, col - r)
    a1, b1 = min(labels.shape[0], row + r + 1), min(labels.shape[1], col + r + 1)
    if a1 <= a0 or b1 <= b0:
        return set()
    window = labels[a0:a1, b0:b1]
    values = window[k[a0 - (row - r) : a1 - (row - r), b0 - (col - r) : b1 - (col - r)]]
    return set(np.unique(values[values > 0]).tolist())


def width_mask(water: np.ndarray, inside_m: np.ndarray, width_m: float, pixel_m: float) -> np.ndarray:
    """Pixels on the centre line of channels at least `width_m` wide (width 0: all water). A channel whose centre line
    is e pixels from the shore is at least (2e - 1) pixels wide, so widths are estimated one pixel short: warnings
    come early rather than late."""
    return water if width_m == 0 else inside_m >= (width_m + pixel_m) / 2.0


def _hub_labels(labels: np.ndarray, hub: Hub, pixel_m: float) -> set[int]:
    return near_labels(labels, hub.row, hub.col, hub.radius_m / pixel_m)


def undetermined_targets(
    water: np.ndarray, gaps: np.ndarray, pixel_m: float, hub: Hub, targets: list[tuple[int, int]], access_m: float
) -> set[int]:
    """Where the pass has gaps a route might run through the missing part: the targets linked only when the gaps are
    taken as water cannot be called isolated."""
    if not gaps.any():
        return set()
    labels, _ = ndimage.label(water | gaps)
    hub_labels = _hub_labels(labels, hub, pixel_m)
    return {i for i, (row, col) in enumerate(targets) if near_labels(labels, row, col, access_m / pixel_m) & hub_labels}


def bottlenecks(
    water: np.ndarray,
    inside_m: np.ndarray,
    pixel_m: float,
    hub: Hub,
    targets: list[tuple[int, int]],
    widths_m: tuple[float, ...],
    access_m: float,
) -> list[float]:
    """For each target, the widest width in [0, *widths_m] that still links it to the hub (-1: not linked). A target
    only passes a width if it passed the narrower one."""
    linked: list[float] = [-1] * len(targets)
    previous: float = -1
    for width in (0, *widths_m):
        # One-pixel creeks often run diagonally: at the "any water" level, corner contacts count
        labels, _ = ndimage.label(
            width_mask(water, inside_m, width, pixel_m), structure=EIGHT_NEIGHBOURS if width == 0 else None
        )
        hub_labels = _hub_labels(labels, hub, pixel_m)
        if not hub_labels:
            break  # the hub is off the water at this width: no wider level links anything
        for i, (row, col) in enumerate(targets):
            if linked[i] == previous and near_labels(labels, row, col, access_m / pixel_m) & hub_labels:
                linked[i] = width
        previous = width
    return linked


def coverage_around(valid: np.ndarray, in_area: np.ndarray, row: int, col: int, radius_px: int) -> float | None:
    """Share of the area's pixels within `radius_px` of (row, col) that have data; None off the grid."""
    if not (0 <= row < valid.shape[0] and 0 <= col < valid.shape[1]):
        return None
    window = (slice(max(0, row - radius_px), row + radius_px + 1), slice(max(0, col - radius_px), col + radius_px + 1))
    covered = valid[window][in_area[window]]
    return float(covered.mean()) if covered.size else None


def route_status(
    bottleneck_m: float, distance_m: float, undetermined: bool, access_m: float, safe_m: float
) -> RouteResult:
    """The route of a target the pass covers."""
    distance = int(round(distance_m))
    if bottleneck_m < 0 and undetermined and distance_m <= access_m:
        return RouteResult(RouteStatus.NO_DATA, None, distance)
    if bottleneck_m < 0 or distance_m > access_m:
        status = RouteStatus.ISOLATED
    elif bottleneck_m >= safe_m:
        status = RouteStatus.CONNECTED
    else:
        status = RouteStatus.AT_RISK
    return RouteResult(status, None if bottleneck_m < 0 else int(bottleneck_m), distance)


def connectivity(
    water: np.ndarray,
    valid: np.ndarray,
    pixel_m: float,
    hub: Hub,
    targets: list[tuple[int, int]],
    widths_m: tuple[float, ...],
    access_m: float,
    safe_m: float,
    in_area: np.ndarray | None = None,
) -> list[RouteResult]:
    """The route of each target (row, col) to the hub. `in_area` marks the pixels that belong to the area: the pixels
    outside it (the corners of a circular area) are not gaps of the pass."""
    in_area = np.ones_like(valid) if in_area is None else in_area
    inside_m = ndimage.distance_transform_edt(water) * pixel_m
    to_water_m = ndimage.distance_transform_edt(~water) * pixel_m
    maybe = undetermined_targets(water, ~valid & in_area, pixel_m, hub, targets, access_m)
    linked = bottlenecks(water, inside_m, pixel_m, hub, targets, widths_m, access_m)
    radius_px = int(access_m / pixel_m)
    routes = []
    for i, (row, col) in enumerate(targets):
        cover = coverage_around(valid, in_area, row, col, radius_px)
        if cover is None or cover < MIN_COVERAGE_AROUND:
            routes.append(RouteResult(RouteStatus.NO_DATA, None, None))
        else:
            routes.append(route_status(linked[i], float(to_water_m[row, col]), i in maybe, access_m, safe_m))
    return routes


def wide_water(water: np.ndarray, pixel_m: float, safe_m: float) -> np.ndarray:
    """The water of channels at least `safe_m` wide."""
    inside_m = ndimage.distance_transform_edt(water) * pixel_m
    core = inside_m >= (safe_m + pixel_m) / 2.0
    return water & ndimage.binary_dilation(core, structure=disk(safe_m / 2.0 / pixel_m + 1))


def hold_unverified(results: list[RouteResult], strict: list[RouteResult]) -> tuple[list[RouteResult], list[int]]:
    """Routes that need unverified water (see low_water_route): a target linked with that water but not as well
    without it is at risk, never connected (its width is kept). Returns the routes and the indices of the targets
    whose route needs the unverified water."""
    out, held = [], []
    for i, (route, without) in enumerate(zip(results, strict, strict=True)):
        if route.status in (RouteStatus.CONNECTED, RouteStatus.AT_RISK) and without.status != route.status:
            held.append(i)
            if route.status == RouteStatus.CONNECTED:
                route = RouteResult(RouteStatus.AT_RISK, route.bottleneck_m, route.distance_to_water_m)
        out.append(route)
    return out, held


def seasonal_ground(occurrence: np.ndarray, low_pct: float, high_pct: float) -> np.ndarray:
    """Pixels whose water occurrence is known and in [low_pct, high_pct): ground that is water only part of the year,
    such as the bed of a lake or side channel that drains at low water. Ground below low_pct was rarely water in
    1984-2021: water there now is usually a channel the river has cut since (meanders move), not a drained bed."""
    return (occurrence != OCCURRENCE_NO_DATA) & (occurrence >= low_pct) & (occurrence < high_pct)


@dataclass(frozen=True)
class Sentinel1View:
    """What Sentinel-1 saw around a pass, on the area grid: the scene dates used (closest first) and its classes."""

    dates: tuple
    water: np.ndarray
    land: np.ndarray
    seen: np.ndarray


def low_water_route(
    water: np.ndarray,
    occurrence: np.ndarray | None,
    level_cm: float | None,
    rule: LowWater | None,
    pixel_m: float,
    sentinel1: Sentinel1View | None = None,
) -> tuple[np.ndarray, np.ndarray | None, bool]:
    """(route, unverified, applied): the water a route may use on a pass, the water a route may only use at risk, and
    whether the low-water rule applied.

    At low water (river level at the gauge at most gauge_max_cm) NISAR reads the wet, smooth bed of a drained lake, bay
    or side channel as dark as open water, and the water occurrence cannot tell such a bed from a lake that still holds
    water. Sentinel-1 can: at its shorter wavelength the mud is rough and bright. So, on seasonal ground only:
      - water that Sentinel-1 sees as land leaves the routes where it forms patches that hold a disc of veto_radius_m
        (a speck or a one-pixel bank of disagreement is not a drained bed);
      - broad patches (holding a disc of patch_radius_m) that no usable Sentinel-1 scene judged are unverified: a route
        through them is kept, but only as at risk, never as safe.
    Without Sentinel-1 every broad seasonal patch is unverified. Without a rule, an occurrence layer or a level, the
    water is used as it is and nothing is unverified."""
    if rule is None or occurrence is None or level_cm is None or level_cm > rule.gauge_max_cm:
        return water, None, False
    low, high = rule.seasonal_pct
    seasonal = water & seasonal_ground(occurrence, low, high)
    beds = ndimage.binary_opening(seasonal, structure=disk(rule.patch_radius_m / pixel_m))
    if sentinel1 is None:
        return water, beds, True
    mud = ndimage.binary_opening(seasonal & sentinel1.land, structure=disk(rule.veto_radius_m / pixel_m))
    return water & ~mud, beds & ~sentinel1.seen & ~mud, True
