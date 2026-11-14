from datetime import date

import numpy as np
import pytest
from fakes import FakeRasters

from worker.adapters.imaging import sentinel1
from worker.adapters.imaging.arrays import to_array
from worker.adapters.imaging.water import Sentinel1View
from worker.domain.area import AreaGrid, Sentinel1Method
from worker.domain.errors import InvalidJob
from worker.domain.passes import Gauge, GaugeDay, Sentinel1Date
from worker.domain.rasters import XY, DType, Raster, RasterMeta, RasterRef, Sentinel1Class, Unit

METHOD = Sentinel1Method(max_days=4, level_tolerance_cm=30, water_db=-18.0, weak_db=-16.0, land_db=-16.0)
PX = 20.0
# 300 x 200 px of 0.0002 degrees; the tiles below are in "metres" = degrees x 100 000 (20 m pixels)
GRID = AreaGrid("EPSG:4326", 300, 200, XY(-64.7999, -3.4001), XY(0.0002, -0.0002), PX, (-64.8, -3.44, -64.74, -3.4))


def to_degrees(x, y):
    return np.asarray(x) / 100_000.0, np.asarray(y) / 100_000.0


def factory(_source: str, _target: str):
    return to_degrees


def tile(href: str, west_db: float | None, east_db: float | None) -> tuple[RasterRef, Raster]:
    """A VV tile in linear power over the whole grid: one value on the west half, another on the east half (None:
    no data there, -32768 as the product writes it)."""
    values = np.full((200, 300), -32768.0, np.float32)
    if west_db is not None:
        values[:, :150] = 10 ** (west_db / 10)
    if east_db is not None:
        values[:, 150:] = 10 ** (east_db / 10)
    meta = RasterMeta(
        300, 200, DType.FLOAT32, -32768.0, Unit.LINEAR_POWER, "EPSG:32720", XY(-6479990.0, -340010.0), XY(20.0, -20.0)
    )
    ref = RasterRef(f"/internal/worker/rasters/{href}", meta)
    return ref, Raster(meta, values.tobytes())


def scenes(day: str, *tiles: tuple[RasterRef, Raster]) -> Sentinel1Date:
    return Sentinel1Date(date.fromisoformat(day), tuple(ref for ref, _ in tiles))


def source(*tiles: tuple[RasterRef, Raster]) -> FakeRasters:
    return FakeRasters({ref.href: raster for ref, raster in tiles})


def test_usable_dates_closest_first_within_the_window_and_the_river_level():
    series = [("2026-10-19", 836.0), ("2026-10-20", 827.0), ("2026-10-23", 797.0), ("2026-10-25", 783.5),
              ("2026-10-26", 760.0), ("2026-10-27", 750.0)]  # fmt: skip
    gauge = Gauge("12900001", tuple(GaugeDay(date.fromisoformat(d), v) for d, v in series))
    candidates = tuple(
        Sentinel1Date(date.fromisoformat(d), ())
        for d in ("2026-10-18", "2026-10-19", "2026-10-20", "2026-10-25", "2026-10-26", "2026-10-27")
    )

    usable = sentinel1.usable_dates(candidates, date(2026, 10, 23), gauge, 4, 30)

    # 10-18 is 5 days away; on 10-26 and 10-27 the river had fallen more than 30 cm below the pass level; on 10-19 it
    # was more than 30 cm higher (10-20, exactly 30 cm higher, is kept)
    assert [d.date.isoformat() for d in usable] == ["2026-10-25", "2026-10-20"]


def test_without_a_gauge_the_window_alone_decides_and_a_tie_takes_the_earlier_scene():
    candidates = (Sentinel1Date(date(2026, 10, 25), ()), Sentinel1Date(date(2026, 10, 21), ()))

    usable = sentinel1.usable_dates(candidates, date(2026, 10, 23), None, 4, 30)

    assert [d.date.isoformat() for d in usable] == ["2026-10-21", "2026-10-25"]


def test_an_unknown_level_on_the_scene_day_does_not_exclude_the_scene():
    gauge = Gauge("x", (GaugeDay(date(2026, 10, 23), 797.0),))
    candidates = (Sentinel1Date(date(2026, 10, 26), ()),)

    assert sentinel1.usable_dates(candidates, date(2026, 10, 23), gauge, 4, 30) == list(candidates)


def test_classes_water_land_and_undecided():
    vv = np.full((40, 40), -8.0, np.float32)  # land
    vv[:, :20] = -21.0  # open water
    vv[:, 20:22] = -17.0  # wind-roughened edge touching the water: water
    vv[10:20, 30:39] = -17.0  # pixels between the thresholds, away from water: undecided
    vv[30:, 30:] = np.nan  # no scene

    water, land, seen = sentinel1.classify(vv, METHOD, PX)

    assert water[:, :22].all()
    assert not water[:, 22:].any()
    assert not (land[10:20, 30:39].any() or water[10:20, 30:39].any())
    assert land[0:10, 22:].all()
    assert not (seen[30:, 30:].any() or land[30:, 30:].any())


def test_agreement_counts_only_pixels_both_judged():
    water = np.zeros((10, 10), bool)
    water[:, :5] = True
    valid = np.ones_like(water)
    valid[9, :] = False
    s1_water, s1_land = np.zeros_like(water), np.zeros_like(water)
    s1_water[:, :4] = True  # NISAR has one more column of water (column 4)
    s1_land[:, 4:8] = True  # columns 8-9 undecided
    view = Sentinel1View((date(2026, 10, 25),), s1_water, s1_land, np.ones_like(water))
    mud = np.zeros_like(water)
    mud[0, :3] = True

    got = sentinel1.agreement(water, valid, view, PX, mud)

    km2 = 0.0004
    assert got.judged_km2 == round(72 * km2, 1)
    assert got.agreement == round(63 / 72, 4)
    assert (got.both_water_km2, got.nisar_only_km2, got.sentinel1_only_km2) == (
        round(36 * km2, 2),
        round(9 * km2, 2),
        0.0,
    )
    assert got.mud_km2 == round(3 * km2, 2)
    assert got.dates == (date(2026, 10, 25),)
    nothing = Sentinel1View((), np.zeros_like(water), np.zeros_like(water), np.ones_like(water))
    assert sentinel1.agreement(water, valid, nothing, PX, mud).agreement is None


def test_a_date_mosaic_averages_its_tiles_onto_the_grid_and_the_first_with_data_wins():
    first = tile("a", -20.0, None)
    second = tile("b", -5.0, -5.0)

    vv = sentinel1.mosaic_date(scenes("2026-10-25", first, second), source(first, second), GRID, factory)

    assert np.allclose(vv[:, 5:145], -20.0, atol=1e-3)
    assert np.allclose(vv[:, 155:295], -5.0, atol=1e-3)


def test_tiles_must_be_linear_power():
    ref, raster = tile("a", -20.0, -5.0)
    in_db = RasterMeta(300, 200, DType.FLOAT32, "NaN", Unit.DB, ref.meta.crs, ref.meta.origin, ref.meta.step)

    with pytest.raises(InvalidJob):
        sentinel1.mosaic_date(
            scenes("2026-10-25", (RasterRef(ref.href, in_db), raster)),
            FakeRasters({ref.href: Raster(in_db, raster.data)}),
            GRID,
            factory,
        )


def test_the_view_takes_the_darkest_scene_on_each_pixel():
    windy = tile("windy", -5.0, -5.0)  # the closest scene: wind roughens the water, bright everywhere
    calm = tile("calm", -20.0, -5.0)  # an earlier, calm scene sees the water of the west
    dates = [scenes("2026-10-25", windy), scenes("2026-10-20", calm)]

    view = sentinel1.pass_view(dates, METHOD, source(windy, calm), GRID, factory)

    assert view.dates == (date(2026, 10, 25), date(2026, 10, 20))
    assert view.water[:, :140].all()  # water on the calm date wins over the windy one
    assert view.land[:, 160:].all()  # bright on every date it was seen: land
    assert view.seen.all()


def test_no_view_without_a_usable_scene():
    assert sentinel1.pass_view([], METHOD, FakeRasters(), GRID, factory) is None


def test_the_classes_travel_as_a_raster_and_come_back_the_same():
    water = np.zeros((GRID.height, GRID.width), bool)
    water[:, :100] = True
    land = np.zeros_like(water)
    land[:, 200:] = True
    seen = np.ones_like(water)
    seen[:, 290:] = False
    view = Sentinel1View((date(2026, 10, 25),), water, land & seen, seen)

    raster = sentinel1.encode(view, GRID)
    back = sentinel1.decode(raster, view.dates, GRID)

    codes = to_array(raster)
    assert (codes[0, 0], codes[0, 150], codes[0, 250], codes[0, 295]) == (
        Sentinel1Class.WATER,
        Sentinel1Class.UNDECIDED,
        Sentinel1Class.LAND,
        Sentinel1Class.NOT_SEEN,
    )
    assert (back.water == view.water).all() and (back.land == view.land).all() and (back.seen == view.seen).all()
