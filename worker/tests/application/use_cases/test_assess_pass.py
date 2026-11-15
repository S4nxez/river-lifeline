from dataclasses import replace
from datetime import date

import pytest
from fakes import (
    GRID,
    PASS,
    FakeAnalyzer,
    FakeRasters,
    assess_inputs,
    assessment,
    classes,
    job,
    reevaluate_inputs,
)

from worker.application.use_cases.assess_pass import AssessPass, ReevaluatePass
from worker.domain.area import AreaGrid
from worker.domain.errors import InvalidJob, InvalidResult
from worker.domain.jobs import JobType
from worker.domain.passes import Sentinel1Agreement
from worker.domain.rasters import XY, DType, Raster, RasterMeta, Unit

RADAR = Raster(RasterMeta(2, 1, DType.FLOAT32, "NaN", Unit.DB, "EPSG:4326", XY(0, 0), XY(0.02, -0.02)), bytes(8))
AGREEMENT = Sentinel1Agreement((date(2026, 10, 25),), 30.0, 0.9, 10.0, 1.0, 0.5, 0.2)


def assess(result, inputs=None):
    analyzer = FakeAnalyzer(result)
    return AssessPass(analyzer, FakeRasters()).execute(job(inputs or assess_inputs(), JobType.ASSESS_PASS)), analyzer


def test_returns_the_assessment_as_the_contract_shapes_it_with_its_rasters():
    water = classes(*range(12))

    result, analyzer = assess(assessment(rasters={"water": water, "radar": RADAR}))

    assert analyzer.calls[0][0] == "assess"
    assert result.rasters == {"water": water, "radar": RADAR}
    assert result.data == {
        "passId": PASS.id,
        "coverage": 0.95,
        "shown": True,
        "evaluated": True,
        "waterKm2": 12.5,
        "narrowKm2": 1.5,
        "vegetatedKm2": None,
        "threshold": {"source": "fixed", "strongDb": -16, "weakDb": -14},
        "riverLevelCm": 812.0,
        "lowWater": None,
        "hubLink": {"status": "connected", "bottleneckM": 400, "distanceToWaterM": 0},
        "communities": [
            {"communityId": "uarini", "status": "connected", "bottleneckM": 100, "distanceToWaterM": 0},
            {"communityId": "nogueira", "status": "isolated", "bottleneckM": None, "distanceToWaterM": 900},
        ],
        "unverifiedRoute": [],
        "sentinel1": None,
        "stats": {"spacingM": None},
        "rasters": {
            "water": {
                "width": 4,
                "height": 3,
                "dtype": "uint8",
                "nodata": 255,
                "unit": "class",
                "crs": "EPSG:4326",
                "origin": {"x": -65.0, "y": -3.0},
                "step": {"x": 0.01, "y": -0.01},
            },
            "radar": {
                "width": 2,
                "height": 1,
                "dtype": "float32",
                "nodata": "NaN",
                "unit": "db",
                "crs": "EPSG:4326",
                "origin": {"x": 0, "y": 0},
                "step": {"x": 0.02, "y": -0.02},
            },
        },
    }


def test_reports_the_sentinel1_agreement_with_its_raster():
    sentinel1 = classes(*([1] * 12))

    result, _ = assess(assessment(sentinel1=AGREEMENT, rasters={"water": classes(*([0] * 12)), "sentinel1": sentinel1}))

    assert result.data["sentinel1"] == {
        "dates": ["2026-10-25"],
        "judgedKm2": 30.0,
        "agreement": 0.9,
        "bothWaterKm2": 10.0,
        "nisarOnlyKm2": 1.0,
        "sentinel1OnlyKm2": 0.5,
        "mudKm2": 0.2,
    }
    assert set(result.data["rasters"]) == {"water", "sentinel1"}


@pytest.mark.parametrize(
    ("result", "problem"),
    [
        (assessment(pass_id="another"), "another"),
        (assessment(rasters={"radar": RADAR}), "water required"),
        (assessment(rasters={"water": classes(*([0] * 12)), "change": classes(*([0] * 12))}), "allowed"),
        (
            assessment(
                rasters={
                    "water": Raster(
                        RasterMeta(1, 1, DType.UINT8, 255, Unit.CLASS, GRID.crs, GRID.origin, GRID.step), bytes(1)
                    )
                }
            ),
            "area grid",
        ),
        (assessment(sentinel1=AGREEMENT), "sentinel1 raster"),
        (assessment(communities=()), "one route per community"),
    ],
)
def test_a_result_that_breaks_the_contract_is_never_posted(result, problem):
    with pytest.raises(InvalidResult, match=problem):
        assess(result)


def test_a_pass_that_was_not_evaluated_has_no_routes_to_check():
    result, _ = assess(assessment(evaluated=False, hub_link=None, communities=()))

    assert result.data["evaluated"] is False
    assert result.data["communities"] == []


def test_a_job_of_another_type_is_invalid():
    with pytest.raises(InvalidJob):
        AssessPass(FakeAnalyzer(assessment()), FakeRasters()).execute(job(reevaluate_inputs(), JobType.ASSESS_PASS))


def test_reevaluates_from_the_stored_masks():
    analyzer = FakeAnalyzer(assessment())

    result = ReevaluatePass(analyzer, FakeRasters()).execute(job(reevaluate_inputs(), JobType.REEVALUATE_PASS))

    assert analyzer.calls == [("reevaluate", reevaluate_inputs())]
    assert set(result.rasters) == {"water"}


def test_a_reevaluation_brings_no_radar():
    analyzer = FakeAnalyzer(assessment(rasters={"water": classes(*([0] * 12)), "radar": RADAR}))

    with pytest.raises(InvalidResult):
        ReevaluatePass(analyzer, FakeRasters()).execute(job(reevaluate_inputs(), JobType.REEVALUATE_PASS))


def test_a_reevaluation_reports_the_sentinel1_agreement_without_sending_its_classes_again():
    analyzer = FakeAnalyzer(assessment(sentinel1=AGREEMENT))

    result = ReevaluatePass(analyzer, FakeRasters()).execute(job(reevaluate_inputs(), JobType.REEVALUATE_PASS))

    assert result.data["sentinel1"]["dates"] == ["2026-10-25"]
    assert set(result.rasters) == set(result.data["rasters"]) == {"water"}


def test_returns_the_sentinel1_image_with_the_sentinel1_classes():
    image = Raster(RasterMeta(2, 1, DType.FLOAT32, "NaN", Unit.DB, "EPSG:4326", XY(0, 0), XY(0.02, -0.02)), bytes(8))
    rasters = {"water": classes(*([0] * 12)), "sentinel1": classes(*([1] * 12)), "sentinel1_vv": image}

    result, _ = assess(assessment(sentinel1=AGREEMENT, rasters=rasters))

    assert set(result.rasters) == {"water", "sentinel1", "sentinel1_vv"}
    assert result.data["rasters"]["sentinel1_vv"]["unit"] == "db"


def test_a_sentinel1_image_without_its_classes_is_never_posted():
    image = Raster(RasterMeta(2, 1, DType.FLOAT32, "NaN", Unit.DB, "EPSG:4326", XY(0, 0), XY(0.02, -0.02)), bytes(8))

    with pytest.raises(InvalidResult, match="sentinel1_vv"):
        assess(assessment(rasters={"water": classes(*([0] * 12)), "sentinel1_vv": image}))


def test_a_reevaluation_never_sends_sentinel1_classes():
    analyzer = FakeAnalyzer(
        assessment(sentinel1=AGREEMENT, rasters={"water": classes(*([0] * 12)), "sentinel1": classes(*([1] * 12))})
    )

    with pytest.raises(InvalidResult):
        ReevaluatePass(analyzer, FakeRasters()).execute(job(reevaluate_inputs(), JobType.REEVALUATE_PASS))


def test_the_grid_check_uses_the_area_of_the_job():
    other = AreaGrid("EPSG:4326", 4, 3, XY(10, 10), GRID.step, 1000, GRID.bbox)
    inputs = assess_inputs()
    inputs = replace(inputs, area=replace(inputs.area, grid=other))

    with pytest.raises(InvalidResult, match="area grid"):
        assess(assessment(), inputs)
