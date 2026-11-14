"""Water mask, channel widths and the water route between each target and the hub."""

import math
from dataclasses import dataclass

import numpy as np
from scipy import ndimage

from worker.domain.passes import RouteResult, RouteStatus

# Water patches smaller than this are specks; holes smaller than this inside water are filled
MIN_WATER_M2 = 10_000
MIN_HOLE_M2 = 20_000
# Share of a target's surroundings (within the access radius) a pass must cover to judge it
MIN_COVERAGE_AROUND = 0.8
EIGHT_NEIGHBOURS = np.ones((3, 3))


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
