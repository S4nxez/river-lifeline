from fakes import GRID, meta

from worker.domain.rasters import XY, DType, RasterMeta, Unit


def test_a_pixel_area_comes_from_its_size_in_metres():
    assert GRID.pixel_km2 == 1.0


def test_the_grid_holds_rasters_of_its_exact_size_origin_and_step():
    assert GRID.holds(meta())
    assert not GRID.holds(RasterMeta(4, 3, DType.UINT8, 255, Unit.CLASS, "EPSG:32720", GRID.origin, GRID.step))
    assert not GRID.holds(RasterMeta(4, 3, DType.UINT8, 255, Unit.CLASS, GRID.crs, GRID.origin, XY(0.02, -0.02)))
    assert not GRID.holds(RasterMeta(3, 3, DType.UINT8, 255, Unit.CLASS, GRID.crs, GRID.origin, GRID.step))
