import numpy as np
import pytest

from worker.adapters.imaging.backscatter import (
    average_onto,
    block_mean,
    box_filter,
    box_sum,
    linear_to_db,
    mask_fill,
    resample_into,
    trim_swath_edge,
    window_px,
)
from worker.domain.area import AreaGrid
from worker.domain.rasters import XY


def test_linear_power_to_db_and_invalid_values():
    db = linear_to_db(np.array([1.0, 0.1, 0.0, -1.0, np.inf]))

    np.testing.assert_allclose(db[:2], [0.0, -10.0], atol=1e-6)
    assert np.isnan(db[2:]).all()


def test_values_below_the_floor_are_fill():
    db = mask_fill(np.array([-45.0, -40.0, -10.0], np.float32), -40.0)

    assert np.isnan(db[0])
    np.testing.assert_array_equal(db[1:], [-40.0, -10.0])


def test_box_sum_matches_a_brute_force_sum_clipped_at_the_edges():
    a = np.random.default_rng(1).random((7, 9))
    expected = np.array([[a[max(0, i - 1) : i + 2, max(0, j - 1) : j + 2].sum() for j in range(9)] for i in range(7)])

    np.testing.assert_allclose(box_sum(a, 3), expected)


def test_the_box_filter_averages_in_linear_power_and_ignores_nan():
    db = np.full((5, 5), -10.0, np.float32)
    db[2, 2] = 0.0
    db[0, 0] = np.nan

    out = box_filter(db, 3)

    assert abs(float(out[2, 2]) - 10 * np.log10((8 * 0.1 + 1.0) / 9)) < 1e-4
    assert np.isnan(out[0, 0])
    assert abs(float(out[4, 4]) + 10.0) < 1e-4


def test_the_window_is_odd_and_at_least_three_pixels():
    assert [window_px(m, 20.0) for m in (10, 60, 100, 120)] == [3, 3, 5, 7]
    assert window_px(100, 10.0) == 11


def test_block_mean_ignores_nan_and_drops_incomplete_blocks():
    a = np.array([[1, 3, 5], [np.nan, 2, 7], [9, 9, 9]], np.float32)

    out = block_mean(a, 2)

    assert out.shape == (1, 1)
    assert float(out[0, 0]) == 2.0
    assert np.isnan(block_mean(np.full((2, 2), np.nan), 2)[0, 0])


def scaled(lon, lat):
    """lon/lat -> 'projected' metres, enough for the resampling."""
    return np.asarray(lon) * 100_000.0, np.asarray(lat) * 100_000.0


def unscaled(x, y):
    return np.asarray(x) / 100_000.0, np.asarray(y) / 100_000.0


SMALL = AreaGrid("EPSG:4326", 6, 4, XY(0.005, 0.035), XY(0.01, -0.01), 1000.0, (0.0, 0.0, 0.06, 0.04))


def test_resampling_takes_the_nearest_product_pixel_and_keeps_filled_ones():
    db = np.arange(24, dtype=np.float32).reshape(4, 6)  # product pixels on the same grid
    out = np.full((4, 6), np.nan, np.float32)
    out[0, 0] = -99.0  # already filled by an earlier frame

    resample_into(out, db, SMALL, scaled, XY(500.0, 3500.0), XY(1000.0, -1000.0))

    assert out[0, 0] == -99.0
    np.testing.assert_array_equal(out[1:], db[1:])


def test_pixels_outside_the_product_stay_nan():
    out = np.full((4, 6), np.nan, np.float32)

    resample_into(out, np.zeros((2, 2), np.float32), SMALL, scaled, XY(500.0, 3500.0), XY(1000.0, -1000.0))

    assert int(np.isfinite(out).sum()) == 4


def test_the_band_along_the_swath_edge_becomes_no_data():
    db = np.full((6, 10), -8.0, np.float32)
    db[:, 8:] = np.nan  # the swath ends at column 8
    in_area = np.ones_like(db, bool)
    in_area[0, :3] = False  # a corner of a circular area: not an edge
    db[0, :3] = np.nan

    out = trim_swath_edge(db, in_area, 40.0, 20.0)

    assert out is db
    assert np.isnan(out[:, 6:]).all()  # 2 px = 40 m from the edge
    assert not np.isnan(out[1:, :6]).any()


def test_nothing_is_trimmed_without_a_margin_or_without_a_gap():
    same = np.full((3, 3), -8.0, np.float32)

    trim_swath_edge(same, np.ones((3, 3), bool), 0, 20.0)
    trim_swath_edge(same, np.ones((3, 3), bool), 100.0, 20.0)

    assert not np.isnan(same).any()


def test_a_finer_raster_is_averaged_onto_the_grid_ignoring_no_data():
    values = np.arange(48, dtype=np.float32).reshape(8, 6)  # 500 m pixels: 2 x 2 per grid pixel of SMALL
    values[0, 0] = np.nan

    out = average_onto(values, XY(250.0, 3750.0), XY(500.0, -500.0), SMALL, unscaled)

    assert out.shape == (4, 6)
    assert out[0, 0] == pytest.approx((1 + 6 + 7) / 3)
    assert out[1, 1] == pytest.approx((14 + 15 + 20 + 21) / 4)
    assert np.isnan(out[:, 3:]).all()  # no source pixel east of x = 3000 m
