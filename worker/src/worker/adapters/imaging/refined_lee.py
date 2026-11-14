"""Refined Lee speckle filter (Lee, 1981): smooths homogeneous areas and keeps edges such as river banks sharp.

For every pixel, on a 7 x 7 window in linear power:
  1. the means of the nine 3 x 3 sub-windows give the gradient across four directions (horizontal, vertical and the two
     diagonals); the steepest one is the edge;
  2. of the two halves of the window on either side of that edge, the one whose sub-window mean is closer to the
     centre's is the pixel's side: only it is used, so water is never averaged with land;
  3. the Lee estimator weighs that half's mean against the pixel itself by how much more the half varies than speckle
     alone would make it vary (speckle variance 1/looks): a homogeneous half gives its mean, a textured one keeps the
     pixel.
NaN pixels are no data: they take no part in any statistic and stay NaN.
"""

import numpy as np

HALF = 3  # half the 7 x 7 window
ROWS_PER_STRIP = 512  # rows filtered at once: bounds the memory of the working arrays
TIE = 1e-6  # relative difference under which two gradients are equal

# Each half window as one column interval [dc0, dc1] per row offset dr in -3..3 (offsets from the centre pixel)
_ROWS = range(-HALF, HALF + 1)
_HALVES = {
    "top": [(dr, (-HALF, HALF)) for dr in _ROWS if dr <= 0],
    "bottom": [(dr, (-HALF, HALF)) for dr in _ROWS if dr >= 0],
    "left": [(dr, (-HALF, 0)) for dr in _ROWS],
    "right": [(dr, (0, HALF)) for dr in _ROWS],
    "up_left": [(dr, (-HALF, -dr)) for dr in _ROWS],  # dr + dc <= 0
    "down_right": [(dr, (-dr, HALF)) for dr in _ROWS],  # dr + dc >= 0
    "up_right": [(dr, (dr, HALF)) for dr in _ROWS],  # dc >= dr
    "down_left": [(dr, (-HALF, dr)) for dr in _ROWS],  # dc <= dr
}
# (sub-window on one side, sub-window on the other, half on the first side, half on the other), sub-windows as
# (row, col) offsets of their centres
_EDGES = [
    ((-2, 0), (2, 0), "top", "bottom"),
    ((0, -2), (0, 2), "left", "right"),
    ((-2, -2), (2, 2), "up_left", "down_right"),
    ((-2, 2), (2, -2), "up_right", "down_left"),
]


def refined_lee(db: np.ndarray, looks: float) -> np.ndarray:
    """The filtered backscatter in dB; `looks` is the equivalent number of looks of the product."""
    if looks <= 0:
        raise ValueError(f"looks must be positive, got {looks}")
    out = np.full(db.shape, np.nan, np.float32)
    height = db.shape[0]
    for first in range(0, height, ROWS_PER_STRIP):
        last = min(height, first + ROWS_PER_STRIP)
        top, bottom = max(0, first - HALF), min(height, last + HALF)
        filtered = _filter_block(db[top:bottom], looks)
        out[first:last] = filtered[first - top : first - top + (last - first)]
    return out


class _WindowSums:
    """Sums over column intervals of shifted rows of a block, from row-wise prefix sums: any half window costs seven
    subtractions per pixel whatever its shape."""

    def __init__(self, values: np.ndarray) -> None:
        # float64: in float32 the rounding of a prefix over a long row is as large as the backscatter of water
        padded = np.pad(values.astype(np.float64), HALF)
        self._prefix = np.concatenate([np.zeros((padded.shape[0], 1)), padded.cumsum(axis=1)], axis=1)
        self._shape = values.shape

    def over(self, intervals: list[tuple[int, tuple[int, int]]]) -> np.ndarray:
        h, w = self._shape
        total = np.zeros(self._shape)
        for dr, (c0, c1) in intervals:
            rows = slice(HALF + dr, HALF + dr + h)
            total += (
                self._prefix[rows, HALF + c1 + 1 : HALF + c1 + 1 + w] - self._prefix[rows, HALF + c0 : HALF + c0 + w]
            )
        return total


def _filter_block(db: np.ndarray, looks: float) -> np.ndarray:
    ok = np.isfinite(db)
    linear = np.where(ok, np.power(10.0, np.where(ok, db, 0.0).astype(np.float64) / 10.0), 0.0)
    sums, squares, counts = _WindowSums(linear), _WindowSums(linear * linear), _WindowSums(ok.astype(np.float64))

    def sub_window_mean(dr: int, dc: int) -> np.ndarray:
        intervals = [(r, (dc - 1, dc + 1)) for r in range(dr - 1, dr + 2)]
        n = counts.over(intervals)
        with np.errstate(invalid="ignore", divide="ignore"):
            return np.where(n > 0, sums.over(intervals) / n, np.nan)

    centre = sub_window_mean(0, 0)
    steepest = np.full(db.shape, -1.0)
    chosen = np.zeros(db.shape, np.int8)
    names = list(_HALVES)
    for first_side, second_side, first_half, second_half in _EDGES:
        a, b = sub_window_mean(*first_side), sub_window_mean(*second_side)
        gradient = np.nan_to_num(np.abs(a - b), nan=-1.0)
        closer_to_a = np.nan_to_num(np.abs(a - centre), nan=np.inf) <= np.nan_to_num(np.abs(b - centre), nan=np.inf)
        side = np.where(closer_to_a, names.index(first_half), names.index(second_half))
        # a direction must be clearly steeper to win: on a straight edge the diagonals tie with it up to rounding
        steeper = gradient > steepest * (1 + TIE) + TIE * 1e-6
        steepest = np.where(steeper, gradient, steepest)
        chosen = np.where(steeper, side, chosen)

    mean = np.zeros(db.shape)
    mean_square = np.zeros(db.shape)
    for index, name in enumerate(names):
        here = chosen == index
        if not here.any():
            continue
        n = np.maximum(counts.over(_HALVES[name]), 1)
        mean[here] = (sums.over(_HALVES[name]) / n)[here]
        mean_square[here] = (squares.over(_HALVES[name]) / n)[here]

    variance = np.maximum(mean_square - mean * mean, 0.0)
    noise = 1.0 / looks  # squared coefficient of variation of the speckle
    signal_variance = np.maximum((variance - mean * mean * noise) / (1.0 + noise), 0.0)
    with np.errstate(invalid="ignore", divide="ignore"):
        weight = np.where(variance > 0, signal_variance / variance, 0.0)
    filtered = mean + weight * (linear - mean)
    with np.errstate(invalid="ignore", divide="ignore"):
        out = 10.0 * np.log10(filtered)
    out[~ok | ~np.isfinite(out)] = np.nan
    return out.astype(np.float32)
