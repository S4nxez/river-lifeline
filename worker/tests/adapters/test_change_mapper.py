import pytest
from fakes import GRID, classes, meta

from worker.adapters.imaging.change import NumpyChangeMapper
from worker.domain.errors import InvalidJob
from worker.domain.rasters import XY, DType, Raster, RasterMeta, Unit

D, W, N, V, X = 0, 1, 2, 3, 255  # dry, wide, narrow, vegetated, no data


def test_marks_open_water_lost_and_gained_where_both_passes_have_data():
    previous = classes(W, N, D, D, W, D, V, X, W, D, D, N)
    current = classes(D, D, W, N, W, D, D, W, X, V, X, N)

    mapped = NumpyChangeMapper().compare(previous, current)

    assert list(mapped.change.data) == [1, 1, 2, 2, 0, 0, 0, 255, 255, 0, 255, 0]
    assert (mapped.lost_pixels, mapped.gained_pixels) == (2, 2)
    assert mapped.change.meta == meta(GRID, nodata=255.0)


def test_only_compares_rasters_of_the_same_grid():
    elsewhere = Raster(RasterMeta(4, 3, DType.UINT8, 255, Unit.CLASS, GRID.crs, XY(0, 0), GRID.step), bytes(12))

    with pytest.raises(InvalidJob):
        NumpyChangeMapper().compare(classes(*([0] * 12)), elsewhere)


def test_only_compares_class_rasters():
    radar = Raster(meta(dtype=DType.FLOAT32, unit=Unit.DB, nodata="NaN"), bytes(48))

    with pytest.raises(InvalidJob):
        NumpyChangeMapper().compare(radar, radar)
