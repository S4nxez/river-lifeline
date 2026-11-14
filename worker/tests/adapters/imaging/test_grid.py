from fakes import GRID

from worker.adapters.imaging.grid import area_mask, pixel_lats, pixel_lons, to_pixel
from worker.domain.area import AreaGrid, Circle
from worker.domain.rasters import XY


def test_pixel_centres_start_at_the_origin():
    assert list(pixel_lons(GRID)) == [-65.0, -64.99, -64.98, -64.97]
    assert [round(v, 6) for v in pixel_lats(GRID, 1)] == [-3.01, -3.02]


def test_a_point_falls_in_the_pixel_that_holds_it_and_off_the_grid_stays_off():
    assert to_pixel(GRID, -64.98, -3.01) == (1, 2)
    assert to_pixel(GRID, -64.9849, -3.0149) == (1, 2)
    assert to_pixel(GRID, -65.006, -3.0) == (0, -1)
    assert to_pixel(GRID, -64.96, -3.03) == (3, 4)


def test_a_box_area_holds_every_pixel():
    assert area_mask(GRID).all()


def test_a_circular_area_holds_only_the_disc():
    grid = AreaGrid("EPSG:4326", 21, 21, XY(-0.01, 0.01), XY(0.001, -0.001), 111.0, (0, 0, 0, 0), Circle(0, 0, 1.0))

    mask = area_mask(grid)

    assert mask[10, 10] and mask[10, 2] and mask[2, 10]
    assert not mask[10, 0]
    assert not mask[0, 0] and not mask[20, 20]
