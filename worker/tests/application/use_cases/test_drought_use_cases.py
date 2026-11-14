from datetime import date

import pytest
from fakes import FakeDroughtModel, job, weather

from worker.application.use_cases.forecast_drought import ForecastDrought
from worker.application.use_cases.train_drought_model import TrainDroughtModel
from worker.domain.drought import DroughtEvent, DroughtModel, ForecastDroughtInputs, TrainDroughtModelInputs
from worker.domain.errors import InvalidJob
from worker.domain.jobs import JobType

DROUGHT = DroughtEvent(date(2024, 8, 1), date(2024, 10, 15), 3)
MODEL = DroughtModel("fake", 1, 90, {"bias": 0.1})


def test_training_returns_the_model_as_plain_data():
    algorithm = FakeDroughtModel()
    observations = (weather("2024-01-01"), weather("2024-01-02"))

    result = TrainDroughtModel(algorithm).execute(
        job(TrainDroughtModelInputs("tefe", observations, (DROUGHT,)), JobType.TRAIN_DROUGHT_MODEL)
    )

    assert algorithm.trained_on == (list(observations), [DROUGHT])
    assert result.data == {
        "region": "tefe",
        "observations": 2,
        "droughts": 1,
        "model": {"kind": "fake", "version": 1, "lookbackDays": 90, "parameters": {"bias": 0.1}},
    }


def test_training_needs_weather():
    with pytest.raises(InvalidJob):
        TrainDroughtModel(FakeDroughtModel()).execute(
            job(TrainDroughtModelInputs("tefe", (), ()), JobType.TRAIN_DROUGHT_MODEL)
        )


def test_forecasts_with_the_model_and_weather_of_the_job():
    algorithm = FakeDroughtModel(probability=0.7)
    recent = (weather("2026-10-31"), weather("2026-11-01"))

    result = ForecastDrought(algorithm).execute(
        job(ForecastDroughtInputs("tefe", 30, date(2026, 11, 1), MODEL, recent), JobType.FORECAST_DROUGHT)
    )

    assert algorithm.predicted_with == (MODEL, list(recent), 30)
    assert result.data == {
        "region": "tefe",
        "horizonDays": 30,
        "asOf": "2026-11-01",
        "probability": 0.7,
        "riskLevel": "high",
    }


def test_a_forecast_never_looks_at_weather_after_its_date():
    inputs = ForecastDroughtInputs("tefe", 30, date(2026, 11, 1), MODEL, (weather("2026-11-02"),))

    with pytest.raises(InvalidJob):
        ForecastDrought(FakeDroughtModel()).execute(job(inputs, JobType.FORECAST_DROUGHT))
