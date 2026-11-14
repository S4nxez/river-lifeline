"""The pass threshold, water under vegetation, the low-water rule and the routes held by unverified water."""

import numpy as np
import pytest

from worker.adapters.imaging.water import (
    Sentinel1View,
    hold_unverified,
    ki_threshold,
    low_water_route,
    mixed_tiles,
    pass_threshold,
    seasonal_ground,
    vegetated_water,
)
from worker.domain.area import AdaptiveThreshold, LowWater, VegetatedWater
from worker.domain.passes import RouteResult, RouteStatus, Threshold

PX = 20.0

# --- Pass threshold -------------------------------------------------------------------------------------------------

ADAPTIVE = AdaptiveThreshold((-20.0, -13.0), 2000, 2)


def two_class_scene(water_db: float = -22.0, land_db: float = -7.0, seed: int = 0) -> np.ndarray:
    """400 x 400 px: water on the west 30 %, land elsewhere, with Gaussian spread in dB."""
    rng = np.random.default_rng(seed)
    db = rng.normal(land_db, 2.0, (400, 400)).astype(np.float32)
    db[:, :120] = rng.normal(water_db, 1.5, (400, 120))
    return db


def test_kittler_illingworth_cuts_between_the_two_classes():
    assert -18.0 < ki_threshold(two_class_scene().ravel()) < -11.0
    assert np.isnan(ki_threshold(np.array([np.nan, 3.0])))
    assert np.isnan(ki_threshold(np.full(100, -10.0)))


def test_only_mixed_and_complete_tiles_are_used():
    db = two_class_scene()
    db[300:, :] = np.nan  # gappy bottom row of tiles

    tiles = mixed_tiles(db, 100, -15.0)

    assert sorted((t[0].start, t[1].start) for t in tiles) == [(0, 100), (100, 100), (200, 100)]


def test_fixed_thresholds_without_the_adaptive_setting():
    assert pass_threshold(two_class_scene(), -15.0, -12.0, None, PX) == Threshold("fixed", -15.0, -12.0)


def test_an_adaptive_threshold_is_measured_and_keeps_the_weak_offset():
    threshold = pass_threshold(two_class_scene(), -15.0, -12.0, ADAPTIVE, PX)

    assert threshold.source == "adaptive"
    assert -18.0 < threshold.strong_db < -13.0
    assert threshold.weak_db - threshold.strong_db == pytest.approx(3.0)
    assert threshold.tiles == 4  # the column of tiles across the shore


def test_an_adaptive_threshold_is_clamped_to_its_range():
    rng = np.random.default_rng(2)
    db = rng.normal(-6.0, 0.3, (400, 400)).astype(np.float32)
    db[:, :200] = rng.normal(-16.0, 0.3, (400, 200))  # water and land 10 dB apart: the cut falls near -11 dB

    threshold = pass_threshold(db, -15.0, -12.0, AdaptiveThreshold((-20.0, -13.0), 8000, 1), PX)

    assert threshold.measured_db > -13.0
    assert (threshold.strong_db, threshold.weak_db, threshold.source) == (-13.0, -10.0, "adaptive")


def test_too_few_mixed_tiles_fall_back_to_the_fixed_threshold():
    threshold = pass_threshold(np.full((400, 400), -7.0, np.float32), -15.0, -12.0, ADAPTIVE, PX)

    assert (threshold.strong_db, threshold.weak_db, threshold.source, threshold.tiles) == (-15.0, -12.0, "fixed", 0)


# --- Water under vegetation -----------------------------------------------------------------------------------------

VEGETATION = VegetatedWater(-4.0, 7.0, 1000)


def vegetation_scene() -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """100 x 200 px: a river on the left, flooded vegetation beside it, dry forest, and the same bright signature far
    from any water (a town, a field)."""
    hh = np.full((100, 200), -7.0, np.float32)
    hv = np.full((100, 200), -12.5, np.float32)
    water = np.zeros((100, 200), bool)
    water[:, :20] = True
    hh[:, :20], hv[:, :20] = -23.0, -30.0
    hh[20:60, 20:40], hv[20:60, 20:40] = -1.0, -9.5  # flooded vegetation next to the river
    hh[20:60, 120:140], hv[20:60, 120:140] = -1.0, -9.5  # same signature 2 km from the water
    hh[70:90, 60:80], hv[70:90, 60:80] = -1.0, -6.0  # bright but with bright HV: not a double bounce
    hh[5, 30], hv[5, 30] = -1.0, -9.5  # a single speck
    return hh, hv, water


def test_flooded_vegetation_near_water_is_flagged():
    hh, hv, water = vegetation_scene()

    flagged = vegetated_water(hh, hv, water, VEGETATION, PX)

    assert flagged[20:60, 20:40].all()
    assert not flagged[20:60, 120:140].any()  # too far from open water
    assert not flagged[70:90, 60:80].any()  # HH - HV only 5 dB
    assert not flagged[5, 30]  # specks under 1 ha are dropped
    assert not (flagged & water).any()
    assert not flagged[:, 45:120].any()  # dry forest


def test_no_vegetation_layer_without_hv_or_without_the_rule():
    hh, hv, water = vegetation_scene()

    assert vegetated_water(hh, None, water, VEGETATION, PX) is None
    assert vegetated_water(hh, hv, water, None, PX) is None
    assert not vegetated_water(hh, hv, np.zeros_like(water), VEGETATION, PX).any()


# --- Low water ------------------------------------------------------------------------------------------------------

RULE = LowWater(1200, (30, 90), 60, 20)
LAKE = np.s_[60:140, 200:260]


def low_water_scene() -> tuple[np.ndarray, np.ndarray]:
    """200 x 300 px: a river, a 60 m channel and a lake; the lake is seasonal ground (water 60 % of the months)."""
    water = np.zeros((200, 300), bool)
    water[:, 10:30] = True
    water[97:100, 30:200] = True
    water[LAKE] = True
    water[180:190, 100:110] = True
    occurrence = np.full(water.shape, 98, np.uint8)
    occurrence[LAKE] = 60
    return water, occurrence


def seen_by_sentinel1(water: np.ndarray, land=None, seen=None) -> Sentinel1View:
    """Everything seen, water where NISAR has water, land on `land`."""
    land = np.zeros_like(water) if land is None else land
    seen = np.ones_like(water) if seen is None else seen
    return Sentinel1View((), water & ~land & seen, land & seen, seen)


def test_seasonal_ground_is_the_band_between_rarely_and_always_water():
    occurrence = np.array([[0, 29, 30, 60, 89, 90, 100, 255]], np.uint8)

    assert seasonal_ground(occurrence, 30, 90).tolist() == [[False, False, True, True, True, False, False, False]]


def test_a_bed_sentinel1_sees_as_mud_leaves_the_routes():
    water, occurrence = low_water_scene()
    land = np.zeros_like(water)
    land[LAKE] = True

    route, unverified, applied = low_water_route(water, occurrence, 800.0, RULE, PX, seen_by_sentinel1(water, land))

    assert applied
    assert route[LAKE].sum() <= 4  # the opening may keep the four corner pixels
    np.testing.assert_array_equal(route[:, :200], water[:, :200])  # river and channel kept
    assert not unverified.any()


def test_a_seasonal_lake_that_still_holds_water_keeps_its_route():
    water, occurrence = low_water_scene()

    route, unverified, applied = low_water_route(water, occurrence, 800.0, RULE, PX, seen_by_sentinel1(water))

    assert applied
    np.testing.assert_array_equal(route, water)
    assert not unverified.any()


def test_mud_is_only_judged_on_seasonal_ground_and_in_patches():
    water, occurrence = low_water_scene()
    land = np.zeros_like(water)
    land[:, 12:18] = True  # Sentinel-1 "land" on the permanent river (wind, a ship): NISAR is kept
    land[100, 205:255] = True  # a one-pixel line of disagreement across the lake: not a drained bed
    land[70:72, 210:212] = True  # a speck of 40 m: too small as well

    route, _, _ = low_water_route(water, occurrence, 800.0, RULE, PX, seen_by_sentinel1(water, land))

    np.testing.assert_array_equal(route, water)


def test_without_sentinel1_a_broad_seasonal_bed_is_unverified_not_removed():
    water, occurrence = low_water_scene()

    route, unverified, applied = low_water_route(water, occurrence, 800.0, RULE, PX)

    assert applied
    np.testing.assert_array_equal(route, water)
    assert unverified[63:137, 203:257].all()  # an opening may leave out a few corner pixels
    assert not unverified[:, :200].any()


def test_the_part_of_a_bed_sentinel1_did_not_see_is_unverified():
    water, occurrence = low_water_scene()
    seen = np.ones_like(water)
    seen[:, 230:] = False  # the scene ends in the middle of the lake
    land = np.zeros_like(water)
    land[60:140, 200:230] = True

    route, unverified, _ = low_water_route(water, occurrence, 800.0, RULE, PX, seen_by_sentinel1(water, land, seen))

    assert not route[62:138, 202:228].any()
    assert route[62:138, 232:258].all()
    assert unverified[63:137, 232:257].all()
    assert not unverified[:, :229].any()


@pytest.mark.parametrize(
    ("has_occurrence", "level", "rule"),
    [(True, 1500.0, RULE), (True, 1200.1, RULE), (False, 800.0, RULE), (True, None, RULE), (True, 800.0, None)],
)
def test_nothing_changes_at_high_water_or_without_a_rule_layer_or_level(has_occurrence, level, rule):
    water, occurrence = low_water_scene()

    route, unverified, applied = low_water_route(water, occurrence if has_occurrence else None, level, rule, PX)

    assert (route is water, unverified, applied) == (True, None, False)


def test_the_low_water_limit_is_included():
    water, occurrence = low_water_scene()

    assert low_water_route(water, occurrence, 1200.0, RULE, PX)[2]


def test_thin_seasonal_banks_new_channels_and_unknown_ground_are_never_unverified():
    water, _ = low_water_scene()
    occurrence = np.full(water.shape, 98, np.uint8)
    occurrence[:, 10:12] = 60  # a 40 m seasonal fringe along the river bank: not a broad bed
    occurrence[:, 15:25] = 10  # the river now runs over ground that was rarely water: a new channel
    occurrence[LAKE] = 255  # no occurrence data on the lake

    route, unverified, applied = low_water_route(water, occurrence, 800.0, RULE, PX)

    assert applied
    np.testing.assert_array_equal(route, water)
    assert not unverified.any()


# --- Routes through unverified water --------------------------------------------------------------------------------


def test_a_route_that_needs_unverified_water_is_held_at_risk_with_its_width():
    c, r, i, n = RouteStatus.CONNECTED, RouteStatus.AT_RISK, RouteStatus.ISOLATED, RouteStatus.NO_DATA
    with_it = [(c, 100, 0), (c, 100, 0), (r, 0, 0), (r, 0, 0), (i, None, 900), (c, 100, 0)]
    without = [(c, 100, 0), (i, None, 0), (i, None, 0), (r, 0, 0), (i, None, 900), (n, None, None)]

    out, held = hold_unverified([RouteResult(*v) for v in with_it], [RouteResult(*v) for v in without])

    assert [o.status for o in out] == [c, r, r, r, i, r]
    assert out[1] == RouteResult(r, 100, 0)
    assert held == [1, 2, 5]
