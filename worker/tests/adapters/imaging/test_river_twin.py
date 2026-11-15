import numpy as np
import pytest
from fakes import METHOD

from worker.adapters.imaging.river_twin import RiverTwin, drying_levels, drying_table, water_at
from worker.domain.area import AreaGrid, AreaSpec, Community, Target
from worker.domain.rasters import XY

STEP = 0.00018
GRID = AreaGrid("EPSG:4326", 200, 60, XY(-65.0, -3.0), XY(STEP, -STEP), 20.0, (-65.0, -3.01, -64.964, -3.0))
# a record whose (100 - p) % quantile is 10 * (100 - p) cm: occurrence p dries below 10 * (100 - p)
RECORD = np.arange(0, 1001, dtype=float)


def lon(col: int) -> float:
    return GRID.origin.x + col * STEP


def lat(row: int) -> float:
    return GRID.origin.y - row * STEP


def channel_area() -> tuple[AreaSpec, np.ndarray]:
    """A river (columns 0-20) with the hub, an 11 px channel (rows 25-35) whose occurrence falls from 90 % on its
    centre line to 40 % at its banks, and a lake (columns 150-190) with a community; one more place on dry land."""
    occ = np.zeros((60, 200), np.uint8)
    occ[:, :21] = 100
    for d in range(-5, 6):
        occ[30 + d, 21:150] = 90 - 10 * abs(d)
    occ[10:51, 150:191] = 100
    occ[0, 199] = 255
    area = AreaSpec(
        id="toy",
        name="Toy",
        grid=GRID,
        hub=Target("Hub", lon(10), lat(30), 200),
        river=Target("River", lon(5), lat(5), 200),
        communities=(
            Community("lake", "Lake", "village", lon(170), lat(30)),
            Community("hill", "Hill", "village", lon(100), lat(5)),
        ),
        method=METHOD,
    )
    return area, occ


def test_a_pixel_more_often_water_dries_at_a_lower_level():
    table = drying_table(RECORD)

    assert table[100] == 0.0 and table[0] == 1000.0 and table[90] == pytest.approx(100.0)
    assert np.all(np.diff(table) <= 0)


def test_no_data_is_never_water():
    drying = drying_levels(np.array([[100, 50, 0, 255]], np.uint8), RECORD)

    assert drying[0, :3].tolist() == pytest.approx([0.0, 500.0, 1000.0])
    assert np.isinf(drying[0, 3])
    # above the highest level of the record even ground that was never water floods; no data never does
    assert water_at(drying, 2000.0, np.ones((1, 4), bool)).tolist() == [[True, True, True, False]]
    assert water_at(drying, 700.0, np.ones((1, 4), bool)).tolist() == [[True, True, False, False]]


def test_an_empty_record_is_refused():
    with pytest.raises(ValueError):
        drying_table(np.array([np.nan]))


def test_the_channel_narrows_as_the_river_falls_and_the_lake_is_cut_off_at_the_right_levels():
    area, occ = channel_area()
    twin = RiverTwin(area, occ, RECORD)

    thresholds, rows = twin.thresholds(
        [float(level) for level in range(0, 710, 10)], {"lake": "monitored", "hill": "inland"}, {"lake"}
    )

    lake = next(t for t in thresholds if t.community_id == "lake")
    # 3 px (60 m, the safe width) of channel down to 210 cm; 1 px below; none at 100 cm and below
    assert (lake.at_risk_below_cm, lake.isolated_below_cm) == (200.0, 100.0)
    assert lake.bottleneck_width_m == 0
    assert lake.bottleneck is not None
    # where the lake's safe-width water ends, at the mouth of the channel
    assert lon(148) <= lake.bottleneck[0] <= lon(152)
    hill = next(t for t in thresholds if t.community_id == "hill")
    assert (hill.community_class, hill.at_risk_below_cm, hill.isolated_below_cm) == ("inland", None, None)
    assert len(rows) == 71


def test_a_community_connected_over_the_whole_sweep_has_no_threshold():
    area, occ = channel_area()

    thresholds, _ = RiverTwin(area, occ, RECORD).thresholds([400.0, 500.0], {"lake": "monitored"}, {"lake"})

    assert thresholds[0].at_risk_below_cm is None and thresholds[0].isolated_below_cm is None


def test_levels_with_the_same_water_are_measured_once():
    area, occ = channel_area()
    twin = RiverTwin(area, occ, RECORD)
    calls = []
    measure = twin._measure
    twin._measure = lambda level, ids: calls.append(level) or measure(level, ids)

    twin.routes(301.0, ["lake"])
    twin.routes(305.0, ["lake"])  # no drying level between 301 and 305: same water
    twin.routes(450.0, ["lake"])

    assert calls == [301.0, 450.0]


def test_the_synthetic_water_is_scored_against_the_radar_by_zone():
    area, occ = channel_area()
    twin = RiverTwin(area, occ, RECORD)
    observed = twin.drying < 650.0  # the radar saw exactly the water of 650 cm
    valid = np.ones_like(observed)

    same = twin.mask_iou(650.0, observed, valid, occ)
    lower = twin.mask_iou(150.0, observed, valid, occ)

    assert same == {"area": 1.0, "permanent_water": 1.0, "seasonal_ground": 1.0}
    assert lower["area"] < 1.0
    assert lower["permanent_water"] == 1.0  # the river and the lake hold water at both levels
    assert lower["seasonal_ground"] < lower["area"]
