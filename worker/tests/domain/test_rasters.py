import pytest
from fakes import GRID, meta

from worker.domain.errors import InvalidJob
from worker.domain.rasters import XY, DType, Raster, RasterMeta, RasterRef, Unit


def test_the_body_takes_width_times_height_times_the_size_of_the_dtype():
    assert meta(dtype=DType.UINT8).byte_length == 12
    assert meta(dtype=DType.INT16).byte_length == 24
    assert meta(dtype=DType.FLOAT32, nodata="NaN").byte_length == 48


def test_a_raster_body_of_the_wrong_length_is_broken():
    with pytest.raises(InvalidJob, match="12 bytes, got 11"):
        Raster(meta(), bytes(11))


def test_a_raster_needs_at_least_one_pixel():
    with pytest.raises(ValueError):
        RasterMeta(0, 3, DType.UINT8, None, Unit.CLASS, "EPSG:4326", XY(0, 0), XY(1, -1))


@pytest.mark.parametrize(("dtype", "nodata"), [(DType.UINT8, "NaN"), (DType.FLOAT32, "nan"), (DType.INT16, "none")])
def test_only_float32_rasters_use_nan_as_nodata(dtype, nodata):
    with pytest.raises(ValueError):
        RasterMeta(1, 1, dtype, nodata, Unit.DB, "EPSG:4326", XY(0, 0), XY(1, -1))


@pytest.mark.parametrize(
    "href",
    ["http://evil.example/internal/worker/rasters/1", "/internal/worker/jobs/1", "/internal/worker/rasters/../x"],
)
def test_a_raster_is_only_downloaded_from_the_backend_raster_path(href):
    with pytest.raises(InvalidJob):
        RasterRef(href, meta())


def test_rasters_on_the_same_pixels_share_a_grid_whatever_their_type():
    assert meta().same_grid(meta(dtype=DType.FLOAT32, unit=Unit.DB, nodata="NaN"))
    shifted = RasterMeta(GRID.width, GRID.height, DType.UINT8, 255, Unit.CLASS, GRID.crs, XY(0, 0), GRID.step)
    assert not meta().same_grid(shifted)
