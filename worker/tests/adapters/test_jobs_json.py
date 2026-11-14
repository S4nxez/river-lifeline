from datetime import date

import pytest
from sample_jobs import job

from worker.adapters.backend.jobs_json import parse_job
from worker.domain.connectivity import ClassifyCommunitiesInputs
from worker.domain.drought import DroughtModel, ForecastDroughtInputs, TrainDroughtModelInputs
from worker.domain.errors import InvalidJob
from worker.domain.jobs import JobType
from worker.domain.passes import AssessPassInputs, DetectChangeInputs, ReevaluatePassInputs, RouteStatus
from worker.domain.rasters import XY, DType, Unit


@pytest.mark.parametrize(
    ("job_type", "kind"),
    [
        ("assess_pass", AssessPassInputs),
        ("reevaluate_pass", ReevaluatePassInputs),
        ("detect_change", DetectChangeInputs),
        ("classify_communities", ClassifyCommunitiesInputs),
        ("train_drought_model", TrainDroughtModelInputs),
        ("forecast_drought", ForecastDroughtInputs),
    ],
)
def test_reads_every_job_type_into_its_inputs(job_type, kind):
    parsed = parse_job(job(job_type))

    assert (parsed.id, parsed.type, parsed.lease_seconds, parsed.contract_version) == (
        "lease-7",
        JobType(job_type),
        600,
        1,
    )
    assert isinstance(parsed.inputs, kind)


def test_reads_an_assess_pass_job_in_full():
    inputs = parse_job(job("assess_pass")).inputs

    assert inputs.pass_info.date == date(2026, 10, 23)
    assert inputs.area.grid.circle.radius_km == 2
    assert inputs.area.grid.bbox == (-65.005, -3.025, -64.965, -2.995)
    assert inputs.area.communities[1].access.lon == -64.861
    assert inputs.area.method.water_db_adaptive.range_db == (-20, -12)
    assert inputs.area.method.sentinel1.weak_db is None
    assert inputs.area.method.low_water.seasonal_pct == (10, 90)
    granule = inputs.granules[0]
    assert granule.hh.href == "/internal/worker/rasters/hh"
    assert granule.hh.meta.dtype == DType.FLOAT32
    assert granule.hh.meta.nodata == "NaN"
    assert granule.hh.meta.origin == XY(612345.0, 9654321.0)
    assert inputs.gauge.days[0].level_cm == 812.5
    assert inputs.occurrence.meta.unit == Unit.PERCENT
    assert [tile.href for tile in inputs.sentinel1_candidates[0].tiles] == [
        "/internal/worker/rasters/s1a",
        "/internal/worker/rasters/s1b",
    ]


def test_reads_routes_and_models():
    classify = parse_job(job("classify_communities")).inputs
    forecast = parse_job(job("forecast_drought")).inputs

    assert classify.passes[0].status_of("nogueira").status == RouteStatus.ISOLATED
    assert classify.passes[0].status_of("nogueira").distance_to_water_m == 900
    assert forecast.model == DroughtModel("baseline", 1, 90, {"bias": 0.1})
    assert forecast.recent[0].soil_moisture is None


def test_a_granule_without_hv_is_single_polarisation():
    body = job("assess_pass")
    del body["inputs"]["granules"][0]["bands"]["HV"]

    assert parse_job(body).inputs.granules[0].hv is None


@pytest.mark.parametrize(
    "breaks",
    [
        lambda inputs: inputs.pop("area"),
        lambda inputs: inputs.update(granules=[]),
        lambda inputs: inputs["area"]["method"].update(speckle=None),
        lambda inputs: inputs["occurrence"].update(dtype="float64"),
        lambda inputs: inputs["occurrence"].update(href="http://elsewhere/x"),
        lambda inputs: inputs["occurrence"].update(nodata="none"),
        lambda inputs: inputs["pass"].update(date="23/10/2026"),
        lambda inputs: inputs["sentinel1Candidates"][0].update(tiles=[]),
        lambda inputs: inputs["area"]["grid"].update(bbox=[1, 2, 3]),
    ],
)
def test_a_malformed_job_is_invalid(breaks):
    body = job("assess_pass")
    breaks(body["inputs"])

    with pytest.raises(InvalidJob):
        parse_job(body)


def test_a_forecast_needs_a_positive_horizon():
    body = job("forecast_drought")
    body["inputs"]["horizonDays"] = 0

    with pytest.raises(InvalidJob):
        parse_job(body)
