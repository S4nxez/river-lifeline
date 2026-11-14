"""The radar pass analyzer against a synthetic NISAR window in UTM 20S, like Tefé's products."""

from dataclasses import replace
from datetime import date

import numpy as np
import pytest
from fakes import PASS, FakeRasters
from pyproj import Transformer

from worker.adapters.imaging.arrays import class_raster, to_array
from worker.adapters.imaging.pass_analyzer import RadarPassAnalyzer
from worker.domain.area import (
    AdaptiveThreshold,
    AreaGrid,
    AreaSpec,
    Community,
    LowWater,
    Method,
    Sentinel1Method,
    Speckle,
    Target,
    VegetatedWater,
)
from worker.domain.errors import InvalidJob
from worker.domain.passes import (
    AssessPassInputs,
    Gauge,
    GaugeDay,
    Granule,
    ReevaluatePassInputs,
    RouteStatus,
    Sentinel1Date,
    StoredSentinel1,
)
from worker.domain.rasters import XY, DType, Raster, RasterMeta, RasterRef, Unit, WaterClass

BBOX = (-64.80, -3.44, -64.74, -3.40)
STEP = 0.0002
GRID = AreaGrid("EPSG:4326", 300, 200, XY(BBOX[0] + STEP / 2, BBOX[3] - STEP / 2), XY(STEP, -STEP), 22.26, BBOX)
METHOD = Method(
    access_radius_m=400,
    min_coverage=0.8,
    min_coverage_shown=0.05,
    valid_floor_db=-40,
    edge_margin_m=0,
    water_db=-15,
    water_db_weak=None,
    water_db_adaptive=None,
    speckle=Speckle("box", None, 60),
    widths_m=(40, 60, 100, 200, 400),
    safe_width_m=60,
    vegetated_water=None,
    low_water=None,
    sentinel1=None,
)
AREA = AreaSpec(
    id="test",
    name="Test area",
    grid=GRID,
    hub=Target("Hub", -64.79, -3.42, 200),
    river=Target("River", -64.795, -3.405, 200),
    communities=(
        Community("on_the_river", "On the river", "village", -64.785, -3.43),
        Community("inland", "Inland", "village", -64.745, -3.42),
    ),
    method=METHOD,
)
UTM = "EPSG:32720"


def nisar_window(water_west_of: float = -64.77) -> tuple[RasterRef, Raster]:
    """HH in linear power over the area plus 500 m: water (-20 dB) west of a longitude, land (-5 dB) east of it."""
    to_utm = Transformer.from_crs("EPSG:4326", UTM, always_xy=True)
    x0, y1 = to_utm.transform(BBOX[0], BBOX[3])
    x1, y0 = to_utm.transform(BBOX[2], BBOX[1])
    xs = np.arange(x0 - 500, x1 + 500, 20.0)
    ys = np.arange(y1 + 500, y0 - 500, -20.0)
    split, _ = to_utm.transform(water_west_of, -3.42)
    hh = np.where(xs[None, :] < split, 10 ** (-2.0), 10 ** (-0.5)) * np.ones((len(ys), 1))
    meta = RasterMeta(len(xs), len(ys), DType.FLOAT32, "NaN", Unit.LINEAR_POWER, UTM, XY(xs[0], ys[0]), XY(20, -20))
    ref = RasterRef("/internal/worker/rasters/hh", meta)
    return ref, Raster(meta, hh.astype("<f4").tobytes())


def assess(area: AreaSpec = AREA, window=None, gauge=None):
    ref, raster = window or nisar_window()
    inputs = AssessPassInputs(area, PASS, (Granule("g1", 176, ref),), gauge, None, ())
    ticks = iter([100.0, 112.34])
    return RadarPassAnalyzer(clock=lambda: next(ticks)).assess(inputs, FakeRasters({ref.href: raster}))


def test_judges_a_pass_from_its_granule_window():
    result = assess(gauge=Gauge("12900001", (GaugeDay(date(2026, 10, 22), 790.0), GaugeDay(PASS.date, 812.5))))

    assert (result.pass_id, result.coverage, result.shown, result.evaluated) == (PASS.id, 1.0, True, True)
    assert result.hub_link.status == RouteStatus.CONNECTED
    routes = {c.community_id: c.route for c in result.communities}
    assert routes["on_the_river"].status == RouteStatus.CONNECTED
    assert routes["inland"].status == RouteStatus.ISOLATED
    assert routes["inland"].distance_to_water_m > 400
    assert result.water_km2 > 0
    assert result.river_level_cm == 812.5
    assert (result.seconds, result.spacing_m) == (12.3, 20.0)
    assert (result.vegetated_km2, result.low_water, result.sentinel1) == (None, None, None)


def test_returns_the_water_classes_on_the_area_grid_and_the_radar_at_half_resolution():
    result = assess()

    water = result.rasters["water"]
    assert GRID.holds(water.meta)
    codes = to_array(water)
    assert codes[100, 10] == WaterClass.WIDE
    assert codes[100, 290] == WaterClass.DRY

    radar = result.rasters["radar"].meta
    assert (radar.width, radar.height, radar.unit, radar.dtype) == (150, 100, Unit.DB, DType.FLOAT32)
    assert radar.step == XY(STEP * 2, -STEP * 2)
    assert (radar.origin.x, radar.origin.y) == pytest.approx((BBOX[0] + STEP, BBOX[3] - STEP))
    db = np.frombuffer(result.rasters["radar"].data, "<f4").reshape(100, 150)
    assert abs(float(np.nanmedian(db[:, :20])) + 20) < 0.01


def test_a_window_that_misses_the_area_leaves_it_without_data():
    ref, raster = nisar_window()
    far = replace(ref.meta, origin=XY(ref.meta.origin.x + 100_000, ref.meta.origin.y))
    result = assess(window=(replace(ref, meta=far), replace(raster, meta=far)))

    assert (result.coverage, result.shown, result.evaluated) == (0.0, False, False)
    assert (result.hub_link, result.communities) == (None, ())
    assert (to_array(result.rasters["water"]) == WaterClass.NO_DATA).all()


def test_fill_below_the_valid_floor_is_no_data():
    ref, raster = nisar_window()
    hh = np.frombuffer(raster.data, "<f4").copy()
    hh[:] = 10 ** (-4.5)  # -45 dB everywhere: fill

    result = assess(window=(ref, replace(raster, data=hh.tobytes())))

    assert result.coverage == 0.0


def test_filters_speckle_with_refined_lee_when_the_area_asks_for_it():
    area = replace(AREA, method=replace(METHOD, speckle=Speckle("refined_lee", 9, 60)))

    result = assess(area)

    assert result.evaluated is True
    assert {c.community_id: c.route.status for c in result.communities} == {
        "on_the_river": RouteStatus.CONNECTED,
        "inland": RouteStatus.ISOLATED,
    }


@pytest.mark.parametrize("speckle", [Speckle("refined_lee", None, 60), Speckle("box", None, None), Speckle("x", 1, 1)])
def test_a_speckle_filter_without_its_parameters_or_unknown_makes_the_job_invalid(speckle):
    with pytest.raises(InvalidJob):
        assess(replace(AREA, method=replace(METHOD, speckle=speckle)))


def test_granule_bands_must_be_linear_power():
    ref, raster = nisar_window()
    in_db = replace(ref.meta, unit=Unit.DB)

    with pytest.raises(InvalidJob):
        assess(window=(replace(ref, meta=in_db), replace(raster, meta=in_db)))


def test_reevaluates_from_the_stored_water_classes():
    codes = to_array(assess().rasters["water"]).copy()
    codes[:, 150:] = WaterClass.NO_DATA  # the stored pass only saw the west half
    stored = class_raster(codes, GRID.origin, GRID.step, GRID.crs)
    ref = RasterRef("/internal/worker/rasters/water", stored.meta)
    inputs = ReevaluatePassInputs(AREA, PASS, ref, None, None, None)

    result = RadarPassAnalyzer().reevaluate(inputs, FakeRasters({ref.href: stored}))

    assert result.coverage == 0.5
    assert result.evaluated is False
    assert set(result.rasters) == {"water"}
    assert (to_array(result.rasters["water"]) == codes).all()


def window(
    href: str, db_east_of: list[tuple[float, float]], unit: Unit = Unit.LINEAR_POWER
) -> tuple[RasterRef, Raster]:
    """A band in linear power in UTM over the area plus 500 m, in vertical stripes: `db_east_of` lists (longitude, dB)
    in increasing longitude; each value holds from its longitude to the next one."""
    to_utm = Transformer.from_crs("EPSG:4326", UTM, always_xy=True)
    x0, y1 = to_utm.transform(BBOX[0], BBOX[3])
    x1, y0 = to_utm.transform(BBOX[2], BBOX[1])
    xs = np.arange(x0 - 500, x1 + 500, 20.0)
    ys = np.arange(y1 + 500, y0 - 500, -20.0)
    lons, _ = Transformer.from_crs(UTM, "EPSG:4326", always_xy=True).transform(xs, np.full(xs.shape, ys[len(ys) // 2]))
    row = np.full(xs.shape, np.nan)
    for lon, db in db_east_of:
        row[lons >= lon] = 10 ** (db / 10)
    values = np.tile(row, (len(ys), 1)).astype("<f4")
    meta = RasterMeta(len(xs), len(ys), DType.FLOAT32, "NaN", unit, UTM, XY(xs[0], ys[0]), XY(20, -20))
    return RasterRef(f"/internal/worker/rasters/{href}", meta), Raster(meta, values.tobytes())


def run(area: AreaSpec, *bands, gauge=None, occurrence=None, candidates=(), hv=None):
    """assess_pass with a granule of HH (and HV), an occurrence layer and Sentinel-1 candidates."""
    hh = bands[0]
    granule = Granule("g1", 176, hh[0], hv[0] if hv else None)
    rasters = {ref.href: raster for ref, raster in [hh, *(bands[1:]), *([hv] if hv else [])]}
    occurrence_ref = None
    if occurrence is not None:
        occurrence_ref = RasterRef("/internal/worker/rasters/occurrence", occurrence.meta)
        rasters[occurrence_ref.href] = occurrence
    inputs = AssessPassInputs(area, PASS, (granule,), gauge, occurrence_ref, tuple(candidates))
    return RadarPassAnalyzer().assess(inputs, FakeRasters(rasters))


WATER_THEN_LAND = [(-65.0, -20.0), (-64.77, -5.0)]
LOW_RIVER = Gauge("12900001", (GaugeDay(PASS.date, 800.0),))
SEASONAL = (-64.79, -64.78)  # a band of the river that only holds water part of the year, around "on_the_river"


def occurrence_layer() -> Raster:
    lons = GRID.origin.x + np.arange(GRID.width) * GRID.step.x
    row = np.where((lons >= SEASONAL[0]) & (lons < SEASONAL[1]), 60, 98).astype(np.uint8)
    meta = RasterMeta(GRID.width, GRID.height, DType.UINT8, 255, Unit.PERCENT, GRID.crs, GRID.origin, GRID.step)
    return Raster(meta, np.tile(row, (GRID.height, 1)).tobytes())


LOW_WATER_AREA = replace(
    AREA,
    method=replace(
        METHOD,
        low_water=LowWater(1200, (30, 90), 60, 20),
        sentinel1=Sentinel1Method(max_days=4, level_tolerance_cm=30, water_db=-18.0, weak_db=-16.0, land_db=-16.0),
    ),
)


def mud_scene():
    """Sentinel-1 two days after the pass: water where NISAR has it, except bright mud on the seasonal band."""
    vv = window("vv", [(-65.0, -21.0), (SEASONAL[0], -5.0), (SEASONAL[1], -21.0), (-64.77, -5.0)])
    return vv, Sentinel1Date(date(2026, 10, 25), (vv[0],))


def test_flags_water_under_vegetation_from_hh_and_hv():
    vegetated_band = (-64.77, -64.765)
    hh = window("hh", [(-65.0, -20.0), (vegetated_band[0], -1.0), (vegetated_band[1], -5.0)])
    hv = window("hv", [(-65.0, -30.0), (vegetated_band[0], -9.5), (vegetated_band[1], -12.5)])
    area = replace(AREA, method=replace(METHOD, vegetated_water=VegetatedWater(-4.0, 7.0, 1000)))

    result = run(area, hh, hv=hv)

    codes = to_array(result.rasters["water"])
    assert (codes[50:150, 155:170] == WaterClass.VEGETATED).all()
    assert result.vegetated_km2 > 0
    # never a route: the community on the river keeps its route, the one east of the vegetation stays isolated
    assert {c.community_id: c.route.status for c in result.communities}["inland"] == RouteStatus.ISOLATED


def test_a_single_polarisation_pass_has_no_vegetation_layer():
    area = replace(AREA, method=replace(METHOD, vegetated_water=VegetatedWater(-4.0, 7.0, 1000)))

    assert run(area, window("hh", WATER_THEN_LAND)).vegetated_km2 is None


def test_trims_the_band_along_the_edge_of_the_data():
    hh = window("hh", [(-65.0, -20.0), (-64.77, -5.0), (-64.75, float("nan"))])  # the swath ends at -64.75
    area = replace(AREA, method=replace(METHOD, edge_margin_m=500))

    untrimmed, trimmed = run(AREA, hh), run(area, hh)

    lost = untrimmed.coverage - trimmed.coverage
    assert lost == pytest.approx(500 / GRID.pixel_m / GRID.width, abs=0.01)


def test_measures_the_threshold_when_the_area_asks_for_it():
    area = replace(AREA, method=replace(METHOD, water_db_adaptive=AdaptiveThreshold((-20.0, -10.0), 1000, 2)))

    threshold = run(area, window("hh", WATER_THEN_LAND)).threshold

    assert threshold.source == "adaptive"
    assert -20.0 <= threshold.strong_db <= -10.0
    assert threshold.tiles >= 2


def test_at_low_water_a_bed_sentinel1_sees_as_mud_leaves_the_routes():
    vv, scenes = mud_scene()

    result = run(
        LOW_WATER_AREA,
        window("hh", WATER_THEN_LAND),
        vv,
        gauge=LOW_RIVER,
        occurrence=occurrence_layer(),
        candidates=[scenes],
    )

    assert result.low_water is True
    assert result.river_level_cm == 800.0
    assert {c.community_id: c.route.status for c in result.communities}["on_the_river"] == RouteStatus.ISOLATED
    assert result.sentinel1.dates == (date(2026, 10, 25),)
    assert result.sentinel1.mud_km2 > 0
    assert result.unverified_route == ()
    assert set(result.rasters) == {"water", "radar", "sentinel1"}


def test_at_low_water_without_sentinel1_a_route_over_a_seasonal_bed_is_only_at_risk():
    result = run(LOW_WATER_AREA, window("hh", WATER_THEN_LAND), gauge=LOW_RIVER, occurrence=occurrence_layer())

    assert result.low_water is True
    route = {c.community_id: c.route for c in result.communities}["on_the_river"]
    assert route.status == RouteStatus.AT_RISK
    assert route.bottleneck_m >= 60  # the width it has is kept
    assert result.unverified_route == ("on_the_river",)
    assert result.sentinel1 is None


def test_at_high_water_the_low_water_rule_does_not_apply():
    high = Gauge("12900001", (GaugeDay(PASS.date, 1500.0),))

    result = run(LOW_WATER_AREA, window("hh", WATER_THEN_LAND), gauge=high, occurrence=occurrence_layer())

    assert result.low_water is False
    assert {c.community_id: c.route.status for c in result.communities}["on_the_river"] == RouteStatus.CONNECTED


def test_a_broken_sentinel1_scene_never_loses_the_pass():
    vv = window("vv", [(-65.0, -21.0)], unit=Unit.DB)
    scenes = Sentinel1Date(date(2026, 10, 25), (vv[0],))

    result = run(
        LOW_WATER_AREA,
        window("hh", WATER_THEN_LAND),
        vv,
        gauge=LOW_RIVER,
        occurrence=occurrence_layer(),
        candidates=[scenes],
    )

    assert result.sentinel1 is None
    assert result.evaluated is True


def test_reevaluates_with_the_stored_sentinel1_classes():
    vv, scenes = mud_scene()
    occurrence = occurrence_layer()
    assessed = run(
        LOW_WATER_AREA, window("hh", WATER_THEN_LAND), vv, gauge=LOW_RIVER, occurrence=occurrence, candidates=[scenes]
    )
    water_ref = RasterRef("/internal/worker/rasters/water", assessed.rasters["water"].meta)
    s1_ref = RasterRef("/internal/worker/rasters/s1", assessed.rasters["sentinel1"].meta)
    occurrence_ref = RasterRef("/internal/worker/rasters/occurrence", occurrence.meta)
    inputs = ReevaluatePassInputs(
        LOW_WATER_AREA, PASS, water_ref, StoredSentinel1((scenes.date,), s1_ref), LOW_RIVER, occurrence_ref
    )
    stored = FakeRasters(
        {
            water_ref.href: assessed.rasters["water"],
            s1_ref.href: assessed.rasters["sentinel1"],
            occurrence_ref.href: occurrence,
        }
    )

    result = RadarPassAnalyzer().reevaluate(inputs, stored)

    assert result.communities == assessed.communities
    assert result.sentinel1 == assessed.sentinel1
    assert set(result.rasters) == {"water", "sentinel1"}


def test_reevaluation_keeps_water_under_vegetation_out_of_the_routes():
    codes = np.zeros((GRID.height, GRID.width), np.uint8)
    codes[:, :150] = WaterClass.WIDE
    codes[:, 150:170] = WaterClass.VEGETATED
    stored = class_raster(codes, GRID.origin, GRID.step, GRID.crs)
    ref = RasterRef("/internal/worker/rasters/water", stored.meta)
    area = replace(AREA, method=replace(METHOD, vegetated_water=VegetatedWater(-4.0, 7.0, 1000)))

    result = RadarPassAnalyzer().reevaluate(
        ReevaluatePassInputs(area, PASS, ref, None, None, None), FakeRasters({ref.href: stored})
    )

    assert result.vegetated_km2 == pytest.approx(round(20 * GRID.height * GRID.pixel_km2, 2))
    assert result.water_km2 == pytest.approx(round(150 * GRID.height * GRID.pixel_km2, 2))
    assert (to_array(result.rasters["water"]) == codes).all()


def test_reevaluation_needs_a_water_raster_on_the_area_grid():
    stored = Raster(RasterMeta(2, 2, DType.UINT8, 255, Unit.CLASS, "EPSG:4326", XY(0, 0), XY(1, -1)), bytes(4))
    ref = RasterRef("/internal/worker/rasters/water", stored.meta)

    with pytest.raises(InvalidJob):
        RadarPassAnalyzer().reevaluate(
            ReevaluatePassInputs(AREA, PASS, ref, None, None, None), FakeRasters({ref.href: stored})
        )
