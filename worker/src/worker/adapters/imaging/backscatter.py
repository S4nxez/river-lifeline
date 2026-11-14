"""Backscatter on its way from a product window (linear power, product projection) to the area grid (dB)."""

from collections.abc import Callable

import numpy as np
from scipy import ndimage

from worker.adapters.imaging.grid import pixel_lats, pixel_lons
from worker.domain.area import AreaGrid
from worker.domain.rasters import XY

# Grid rows reprojected at once: bounds the memory of the coordinate arrays
ROWS_PER_CHUNK = 256

# lon, lat arrays -> x, y arrays in the product's projection
Transform = Callable[[np.ndarray, np.ndarray], tuple[np.ndarray, np.ndarray]]


def linear_to_db(values: np.ndarray) -> np.ndarray:
    """Linear power -> dB; zero, negative and non-finite values become NaN."""
    with np.errstate(divide="ignore", invalid="ignore"):
        db = 10.0 * np.log10(np.asarray(values, dtype=np.float32))
    db[~np.isfinite(db)] = np.nan
    return db


def mask_fill(db: np.ndarray, valid_floor_db: float) -> np.ndarray:
    """Values below the floor are not measurements: windows carry near-zero fill (down to -170 dB) at the edge of the
    imaged swath, far below the noise floor (about -25 dB). They become NaN (no data), so they are neither water nor
    averaged into their neighbours by the speckle filter. Works in place and returns `db`."""
    with np.errstate(invalid="ignore"):
        db[db < valid_floor_db] = np.nan
    return db


def trim_swath_edge(db: np.ndarray, in_area: np.ndarray, margin_m: float, pixel_m: float) -> np.ndarray:
    """Pixels within `margin_m` of the edge of the data (no data inside the area) become NaN. Towards the edge of the
    swath the signal rolls off to the noise floor: the median HH falls from about -8 dB inside to -25 to -35 dB in the
    last 100 m, and a band several hundred metres wide reads as water along the edge, a fake channel that links
    communities. The corners of a circular area are not an edge. Works in place and returns `db`."""
    if not margin_m:
        return db
    gaps = ~np.isfinite(db) & in_area
    if gaps.any():
        db[ndimage.distance_transform_edt(~gaps) * pixel_m <= margin_m] = np.nan
    return db


def box_sum(values: np.ndarray, k: int) -> np.ndarray:
    """Sum over a k x k window (k odd) with cumulative sums; windows are clipped at the edges."""
    r = k // 2
    padded = np.pad(values.astype(np.float64), ((r + 1, r), (r + 1, r)), mode="constant")
    s = padded.cumsum(0).cumsum(1)
    return s[k:, k:] - s[:-k, k:] - s[k:, :-k] + s[:-k, :-k]


def box_filter(db: np.ndarray, k: int) -> np.ndarray:
    """Speckle filter: mean in linear power over a k x k window, ignoring NaN. Returns dB, NaN where the input is."""
    ok = np.isfinite(db)
    linear = np.where(ok, np.power(10.0, np.where(ok, db, 0) / 10.0), 0.0)
    with np.errstate(divide="ignore", invalid="ignore"):
        out = 10.0 * np.log10(box_sum(linear, k) / box_sum(ok.astype(np.float64), k))
    out[~ok] = np.nan
    return out.astype(np.float32)


def window_px(window_m: float, spacing_m: float) -> int:
    """Odd window size in pixels, at least 3, for a window of `window_m` metres. A wider window erases channels two or
    three pixels wide."""
    return max(3, int(round(window_m / spacing_m)) | 1)


def block_mean(values: np.ndarray, f: int) -> np.ndarray:
    """Mean over f x f blocks, ignoring NaN; the rows and columns that do not fill a block are dropped."""
    h, w = values.shape[0] // f * f, values.shape[1] // f * f
    blocks = values[:h, :w].reshape(h // f, f, w // f, f)
    with np.errstate(invalid="ignore"):
        count = np.isfinite(blocks).sum(axis=(1, 3))
        total = np.nansum(blocks, axis=(1, 3))
        return np.where(count > 0, total / np.maximum(count, 1), np.nan).astype(np.float32)


def resample_into(out: np.ndarray, db: np.ndarray, grid: AreaGrid, transform: Transform, origin: XY, step: XY) -> None:
    """Fills the NaN pixels of `out` (area grid) with the nearest pixel of `db`, a product window whose first pixel
    centre is `origin` in the product's projection. Pixels already filled (by an earlier frame) are kept."""
    lons = pixel_lons(grid)
    for first in range(0, grid.height, ROWS_PER_CHUNK):
        last = min(grid.height, first + ROWS_PER_CHUNK)
        lon, lat = np.meshgrid(lons, pixel_lats(grid, first, last))
        x, y = transform(lon, lat)
        cols = np.rint((np.asarray(x) - origin.x) / step.x).astype(np.int64)
        rows = np.rint((np.asarray(y) - origin.y) / step.y).astype(np.int64)
        inside = (cols >= 0) & (cols < db.shape[1]) & (rows >= 0) & (rows < db.shape[0])
        values = np.full(cols.shape, np.nan, np.float32)
        values[inside] = db[rows[inside], cols[inside]]
        block = out[first:last]
        fill = np.isnan(block) & np.isfinite(values)
        block[fill] = values[fill]


def average_onto(values: np.ndarray, origin: XY, step: XY, grid: AreaGrid, to_lonlat: Transform) -> np.ndarray:
    """A raster in its own projection averaged onto the area grid (linear power, NaN ignored): every source pixel goes
    to the grid pixel holding its centre. For sources whose pixel is no larger than the grid's; grid pixels no source
    pixel falls in are NaN."""
    total = np.zeros(grid.width * grid.height)
    count = np.zeros(grid.width * grid.height)
    cols = origin.x + np.arange(values.shape[1]) * step.x
    for first in range(0, values.shape[0], ROWS_PER_CHUNK):
        last = min(values.shape[0], first + ROWS_PER_CHUNK)
        chunk = values[first:last]
        ok = np.isfinite(chunk)
        if not ok.any():
            continue
        x, y = np.meshgrid(cols, origin.y + np.arange(first, last) * step.y)
        lon, lat = to_lonlat(x[ok], y[ok])
        gc = np.floor((np.asarray(lon) - grid.origin.x) / grid.step.x + 0.5).astype(np.int64)
        gr = np.floor((np.asarray(lat) - grid.origin.y) / grid.step.y + 0.5).astype(np.int64)
        inside = (gc >= 0) & (gc < grid.width) & (gr >= 0) & (gr < grid.height)
        index = gr[inside] * grid.width + gc[inside]
        total += np.bincount(index, weights=chunk[ok][inside].astype(np.float64), minlength=total.size)
        count += np.bincount(index, minlength=count.size)
    with np.errstate(invalid="ignore", divide="ignore"):
        mean = np.where(count > 0, total / count, np.nan)
    return mean.reshape(grid.height, grid.width).astype(np.float32)
