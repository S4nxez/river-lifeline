from datetime import date

import pytest

from worker.domain.connectivity import ConnectivityStatus, CrossCheck, RouteMeasurement, cross_check


@pytest.mark.parametrize(
    ("covered", "width_m", "status"),
    [
        (True, 400, ConnectivityStatus.CONNECTED),
        (True, 60, ConnectivityStatus.CONNECTED),
        (True, 59.9, ConnectivityStatus.AT_RISK),
        (True, 20, ConnectivityStatus.AT_RISK),
        (True, None, ConnectivityStatus.ISOLATED),
        (False, None, ConnectivityStatus.NO_DATA),
        (False, 200, ConnectivityStatus.NO_DATA),
    ],
)
def test_the_route_sets_the_status(covered, width_m, status):
    assert RouteMeasurement("uarini", covered, width_m).status == status


@pytest.mark.parametrize("width_m", [0, -5])
def test_a_route_width_is_positive(width_m):
    with pytest.raises(ValueError):
        RouteMeasurement("uarini", True, width_m)


SCENE_ON = date(2026, 9, 8)


def test_sentinel1_agrees_when_it_finds_the_same_status():
    check = cross_check(RouteMeasurement("u", True, 40), RouteMeasurement("u", True, 30), "s1", SCENE_ON)

    assert check == CrossCheck("s1", SCENE_ON, agrees=True)


def test_sentinel1_disagrees_when_it_finds_another_status():
    check = cross_check(RouteMeasurement("u", True, 40), RouteMeasurement("u", True, None), "s1", SCENE_ON)

    assert check.agrees is False


@pytest.mark.parametrize(("nisar_covered", "sentinel1_covered"), [(False, True), (True, False)])
def test_there_is_nothing_to_compare_when_a_scene_misses_the_route(nisar_covered, sentinel1_covered):
    nisar = RouteMeasurement("u", nisar_covered, None)
    sentinel1 = RouteMeasurement("u", sentinel1_covered, None)

    assert cross_check(nisar, sentinel1, "s1", SCENE_ON) is None
