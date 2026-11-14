import numpy as np
import pytest
from scipy import ndimage

from worker.adapters.imaging.water import (
    Hub,
    connectivity,
    coverage_around,
    disk,
    near_labels,
    route_status,
    water_mask,
    wide_water,
    width_mask,
)
from worker.domain.passes import RouteResult, RouteStatus

PX = 20.0
WIDTHS = (40, 60, 100, 200, 400)
HUB = Hub(100, 20, 200)


def river_scene(channel_px: int = 3) -> tuple[np.ndarray, np.ndarray]:
    """200 x 300 px: a 400 m river on the left with the hub, joined by a channel to a lake on the right, and a
    detached pond at the bottom."""
    water = np.zeros((200, 300), bool)
    water[:, 10:30] = True
    water[97 : 97 + channel_px, 30:200] = True
    water[60:140, 200:260] = True
    water[180:190, 100:110] = True
    return water, np.ones_like(water)


def routes(water, valid, targets, hub=HUB, in_area=None):
    return connectivity(water, valid, PX, hub, targets, WIDTHS, access_m=400, safe_m=60, in_area=in_area)


def test_a_wide_river_is_connected():
    water, valid = river_scene()

    (result,) = routes(water, valid, [(20, 31)])

    # 400 m river measured conservatively (one pixel less): the 200 m level; water two pixels away
    assert result == RouteResult(RouteStatus.CONNECTED, 200, 2 * PX)


def test_a_lake_behind_a_60_m_channel_takes_the_channel_width():
    (result,) = routes(*river_scene(channel_px=3), [(100, 262)])

    assert (result.status, result.bottleneck_m) == (RouteStatus.CONNECTED, 60)


def test_a_lake_behind_a_40_m_channel_is_at_risk():
    (result,) = routes(*river_scene(channel_px=2), [(100, 262)])

    # even widths are rounded down one pixel: safer to warn early
    assert (result.status, result.bottleneck_m) == (RouteStatus.AT_RISK, 0)


def test_a_cut_channel_isolates_the_lake():
    water, valid = river_scene()
    water[:, 120:124] = False
    water[:, 10:30] = True

    (result,) = routes(water, valid, [(100, 262)])

    assert (result.status, result.bottleneck_m) == (RouteStatus.ISOLATED, None)


def test_a_diagonal_one_pixel_creek_still_links_the_lake():
    water, valid = river_scene(channel_px=0)
    for i in range(170):  # staircase creek touching only at corners
        water[100 + (i % 2), 30 + i] = True

    (result,) = routes(water, valid, [(100, 262)])

    assert (result.status, result.bottleneck_m) == (RouteStatus.AT_RISK, 0)


def test_outside_a_circular_area_is_not_a_gap_of_the_pass():
    water, valid = river_scene()
    yy, xx = np.mgrid[:200, :300]
    inside = (yy - 100) ** 2 + (xx - 150) ** 2 <= 160**2
    valid &= inside
    water &= inside

    (result,) = routes(water, valid, [(100, 262)], in_area=inside)

    assert (result.status, result.bottleneck_m) == (RouteStatus.CONNECTED, 60)


def test_a_detached_pond_and_a_place_far_from_water_are_isolated():
    pond, far = routes(*river_scene(), [(185, 105), (150, 150)])

    assert pond.status == far.status == RouteStatus.ISOLATED
    assert far.distance_to_water_m > 200


def test_missing_data_around_a_place_is_no_data():
    water, valid = river_scene()
    valid[:, 190:] = False

    assert routes(water, valid, [(100, 262)]) == [RouteResult(RouteStatus.NO_DATA, None, None)]


def test_a_route_that_could_run_through_a_gap_is_no_data():
    water, valid = river_scene()
    valid[:, 100:120] = False
    water[:, 100:120] = False

    assert routes(water, valid, [(100, 262)])[0].status == RouteStatus.NO_DATA


def test_a_place_off_the_grid_has_no_data():
    assert routes(*river_scene(), [(-5, 20), (100, 400)]) == [RouteResult(RouteStatus.NO_DATA, None, None)] * 2


def test_a_hub_away_from_water_links_nothing():
    assert routes(*river_scene(), [(20, 31)], hub=Hub(150, 150, 20))[0].status == RouteStatus.ISOLATED


@pytest.mark.parametrize(
    ("bottleneck", "distance", "undetermined", "expected"),
    [
        (200, 40.0, False, RouteResult(RouteStatus.CONNECTED, 200, 40)),
        (60, 40.0, False, RouteResult(RouteStatus.CONNECTED, 60, 40)),
        (40, 40.0, False, RouteResult(RouteStatus.AT_RISK, 40, 40)),
        (0, 40.0, False, RouteResult(RouteStatus.AT_RISK, 0, 40)),
        (-1, 40.0, False, RouteResult(RouteStatus.ISOLATED, None, 40)),
        (200, 500.0, False, RouteResult(RouteStatus.ISOLATED, 200, 500)),
        (-1, 40.0, True, RouteResult(RouteStatus.NO_DATA, None, 40)),
        (-1, 500.0, True, RouteResult(RouteStatus.ISOLATED, None, 500)),
    ],
)
def test_route_status_rules(bottleneck, distance, undetermined, expected):
    assert route_status(bottleneck, distance, undetermined, 400, 60) == expected


def test_coverage_around_counts_only_the_pixels_of_the_area():
    valid = np.ones((10, 10), bool)
    valid[:, :5] = False
    in_area = np.ones_like(valid)

    assert coverage_around(valid, in_area, 5, 5, 2) == pytest.approx(0.6)
    in_area[:, :5] = False
    assert coverage_around(valid, in_area, 5, 5, 2) == 1.0
    assert coverage_around(valid, in_area, 12, 5, 2) is None
    assert coverage_around(valid, in_area, 5, 1, 1) is None


def test_width_mask_keeps_channel_centre_lines():
    water = np.zeros((9, 9), bool)
    water[:, 2:7] = True  # 5 px channel
    inside = ndimage.distance_transform_edt(water) * PX

    assert (width_mask(water, inside, 0, PX) == water).all()
    assert int(width_mask(water, inside, 60, PX)[4].sum()) == 3
    assert not width_mask(water, inside, 200, PX).any()


def test_disk_and_near_labels_at_the_edge():
    labels = np.zeros((5, 5), int)
    labels[0, 0], labels[4, 4] = 1, 2

    assert int(disk(1).sum()) == 5
    assert near_labels(labels, 0, 1, 1) == {1}
    assert near_labels(labels, 2, 2, 3) == {1, 2}
    assert near_labels(labels, 20, 20, 1) == set()


def test_specks_are_dropped_and_small_holes_filled():
    db = np.full((100, 100), -5.0, np.float32)
    db[20:80, 20:80] = -20.0  # lake
    db[50, 50] = -5.0  # one bright pixel inside it (wind, a boat)
    db[5:7, 5:7] = -20.0  # 4 px speck

    water, valid = water_mask(db, -15.0, None, PX)

    assert water[50, 50]
    assert not water[5, 5]
    assert valid.all()


def test_the_size_limits_are_one_and_two_hectares():
    db = np.full((60, 60), -5.0, np.float32)
    db[2:7, 2:7] = -20.0  # 25 px of 400 m2: 1 ha, kept
    db[20:50, 20:50] = -20.0
    db[30:35, 30:40] = -5.0  # 50 px island: 2 ha, kept as land

    water, _ = water_mask(db, -15.0, None, PX)

    assert water[4, 4]
    assert not water[32, 35]


def test_no_data_is_neither_valid_nor_water():
    db = np.full((40, 40), -20.0, np.float32)
    db[:, :10] = np.nan

    water, valid = water_mask(db, -15.0, None, PX)

    assert not valid[:, :10].any()
    assert not water[:, :10].any()
    assert water[:, 10:].all()


def test_hysteresis_keeps_rough_water_attached_to_calm_water():
    db = np.full((60, 100), -6.0, np.float32)
    db[20:40, :] = -20.0  # river
    db[20:40, 40:60] = -13.0  # wind-roughened stretch across it
    db[50:58, 10:20] = -13.0  # darkish field, not touching water

    cut, _ = water_mask(db, -15.0, None, PX)
    joined, _ = water_mask(db, -15.0, -12.0, PX)

    assert not cut[30, 50]
    assert joined[30, 50]
    assert not joined[54, 15]


def test_a_weak_threshold_below_the_strong_one_is_ignored():
    db = np.full((40, 40), -6.0, np.float32)
    db[10:30, 10:30] = -20.0

    assert (water_mask(db, -15.0, None, PX)[0] == water_mask(db, -15.0, -18.0, PX)[0]).all()


def test_narrow_channels_are_not_wide_water():
    water, _ = river_scene(channel_px=2)

    wide = wide_water(water, PX, 60)

    assert wide[100, 20]  # river
    assert not wide[97, 120]  # 40 m channel
    assert wide[100, 230]  # lake
    assert not (wide & ~water).any()
