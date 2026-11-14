"""Refined Lee speckle filter (Lee 1981; edge-aligned windows, Lee, Grunes and De Grandi 1999), in linear power.

In a 7 x 7 window the strongest of four edge directions is found from the means of nine 3 x 3 blocks; the statistics
are taken over the half window on the pixel's own side of that edge, so a channel pixel is averaged with water and not
with the bright bank. Then x = mu + k (I - mu), k = var_x / var with var_x = (var - mu^2 Cu^2) / (1 + Cu^2) and
Cu^2 = 1 / looks: homogeneous windows (the variance of speckle only) are averaged, heterogeneous ones keep the pixel.
NaN in, NaN out.
"""

import warnings

import numpy as np
from scipy import ndimage

from worker.adapters.imaging.backscatter import box_sum

RADIUS = 3  # 7 x 7 window
CHUNK_ROWS = 512  # rows filtered at once: bounds the memory of the working arrays
MIN_PIXELS = 10  # fewer valid pixels in the chosen half window: the pixel is kept as measured


def _halves() -> list[np.ndarray]:
    """The eight edge-aligned half windows of a 7 x 7 window (28 pixels each, centre line included): west, east,
    north, south and the four triangles cut by the diagonals."""
    yy, xx = np.mgrid[-RADIUS : RADIUS + 1, -RADIUS : RADIUS + 1]
    return [xx <= 0, xx >= 0, yy <= 0, yy >= 0, (xx + yy) <= 0, (xx + yy) >= 0, (yy - xx) <= 0, (yy - xx) >= 0]


HALVES = _halves()


def refined_lee(db: np.ndarray, looks: float) -> np.ndarray:
    """The filtered backscatter in dB; `looks` is the equivalent number of looks of the product."""
    if looks <= 0:
        raise ValueError(f"looks must be positive, got {looks}")
    # float32 as measured: directions that tie on a clean edge are broken the same way the method was validated with
    linear = np.where(np.isfinite(db), np.power(10.0, np.nan_to_num(db) / 10.0), np.nan)
    padded = np.pad(linear, RADIUS, mode="constant", constant_values=np.nan)
    ok = np.isfinite(padded)
    out = np.empty(db.shape, np.float32)
    for first in range(0, db.shape[0], CHUNK_ROWS):
        last = min(db.shape[0], first + CHUNK_ROWS)
        out[first:last] = _filter_chunk(padded[first : last + 2 * RADIUS], ok[first : last + 2 * RADIUS], 1.0 / looks)
    with np.errstate(divide="ignore", invalid="ignore"):
        result = 10.0 * np.log10(out)
    result[~np.isfinite(result)] = np.nan
    return result.astype(np.float32)


def _sub_means(linear: np.ndarray, ok: np.ndarray) -> np.ndarray:
    """Mean of every 3 x 3 block of a padded chunk, NaN ignored."""
    with np.errstate(invalid="ignore", divide="ignore"):
        return box_sum(np.where(ok, linear, 0.0), 3) / box_sum(ok.astype(np.float64), 3)


def _filter_chunk(linear: np.ndarray, ok: np.ndarray, cu2: float) -> np.ndarray:
    """Refined Lee on a chunk padded with RADIUS rows and columns on every side; returns the unpadded result."""
    r = RADIUS
    h, w = linear.shape[0] - 2 * r, linear.shape[1] - 2 * r
    means = _sub_means(linear, ok)
    block = {(dy, dx): means[r + dy : r + dy + h, r + dx : r + dx + w] for dy in (-2, 0, 2) for dx in (-2, 0, 2)}

    def side(*keys: tuple[int, int]) -> np.ndarray:
        """Mean of the blocks of one side that have data (image edges and gaps are NaN)."""
        with np.errstate(invalid="ignore"), warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            return np.nanmean(np.stack([block[k] for k in keys]), axis=0)

    # the block means on each side of the four possible edges; the side closer to the centre block is the pixel's own
    sides = [
        (side((-2, -2), (0, -2), (2, -2)), side((-2, 2), (0, 2), (2, 2))),  # west | east
        (side((-2, -2), (-2, 0), (-2, 2)), side((2, -2), (2, 0), (2, 2))),  # north | south
        (side((-2, -2), (-2, 0), (0, -2)), side((0, 2), (2, 0), (2, 2))),  # north-west | south-east
        (side((-2, 0), (-2, 2), (0, 2)), side((0, -2), (2, -2), (2, 0))),  # north-east | south-west
    ]
    centre = block[0, 0]
    with np.errstate(invalid="ignore"):  # NaN sides (gaps, image edges) are handled below
        gradients = np.stack([np.abs(b - a) for a, b in sides])
        direction = np.argmax(np.nan_to_num(gradients, nan=-1.0), axis=0)
        first = np.take_along_axis(np.stack([a for a, _ in sides]), direction[None], 0)[0]
        second = np.take_along_axis(np.stack([b for _, b in sides]), direction[None], 0)[0]
        d1, d2 = np.abs(first - centre), np.abs(second - centre)
    own_side = np.where(np.isnan(d1), 1, np.where(np.isnan(d2) | (d1 <= d2), 0, 1))  # a side without data loses
    choice = 2 * direction + own_side  # index into HALVES

    values = np.where(ok, linear, 0.0)
    valid = ok.astype(np.float64)
    total, squares, count = np.zeros((h, w)), np.zeros((h, w)), np.zeros((h, w))
    inner = (slice(r, r + h), slice(r, r + w))
    for k, half in enumerate(HALVES):
        here = choice == k
        if not here.any():
            continue
        kernel = half.astype(np.float64)
        total[here] = ndimage.correlate(values, kernel, mode="constant")[inner][here]
        squares[here] = ndimage.correlate(values * values, kernel, mode="constant")[inner][here]
        count[here] = ndimage.correlate(valid, kernel, mode="constant")[inner][here]
    with np.errstate(invalid="ignore", divide="ignore"):
        mu = total / count
        var = np.maximum(squares / count - mu * mu, 0.0)
        var_x = (var - mu * mu * cu2) / (1.0 + cu2)
        k = np.clip(np.where(var > 0, var_x / var, 0.0), 0.0, 1.0)
    pixel = linear[inner]
    out = np.where(count >= MIN_PIXELS, mu + k * (pixel - mu), pixel)
    out[~ok[inner]] = np.nan
    return out
