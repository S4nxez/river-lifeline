"""Positions on the area grid: pixel centres, the pixels that belong to the area, and points as (row, col)."""

import math

import numpy as np

from worker.domain.area import AreaGrid

M_PER_DEG_LON_EQUATOR = 111_320.0
M_PER_DEG_LAT = 110_570.0


def pixel_lons(grid: AreaGrid) -> np.ndarray:
    return grid.origin.x + np.arange(grid.width) * grid.step.x


def pixel_lats(grid: AreaGrid, first_row: int = 0, last_row: int | None = None) -> np.ndarray:
    last = grid.height if last_row is None else last_row
    return grid.origin.y + np.arange(first_row, last) * grid.step.y


def area_mask(grid: AreaGrid) -> np.ndarray:
    """The grid pixels that belong to the area: all of them, or the disc of a circular area."""
    circle = grid.circle
    if circle is None:
        return np.ones((grid.height, grid.width), bool)
    k = math.cos(math.radians(circle.lat))
    x = (pixel_lons(grid) - circle.lon) * M_PER_DEG_LON_EQUATOR * k
    y = (pixel_lats(grid) - circle.lat) * M_PER_DEG_LAT
    return (x[None, :] ** 2 + y[:, None] ** 2) <= (circle.radius_km * 1000.0) ** 2


def to_pixel(grid: AreaGrid, lon: float, lat: float) -> tuple[int, int]:
    """(row, col) of the pixel holding the point. A point off the grid gives an index off the grid (negative or too
    large), never the edge pixel."""
    col = math.floor((lon - grid.origin.x) / grid.step.x + 0.5)
    row = math.floor((lat - grid.origin.y) / grid.step.y + 0.5)
    return row, col
