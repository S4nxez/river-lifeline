from datetime import date

import pytest
from fakes import FakeModel, FakeWeather, InMemoryModelStore, weather

from worker.application.use_cases.forecast_drought import ForecastDrought
from worker.application.use_cases.train_drought_model import TrainDroughtModel
from worker.domain.drought import DroughtEvent
from worker.domain.errors import InvalidJob, ModelNotFound
from worker.domain.jobs import Job, JobType

TRAIN = Job("t1", JobType.TRAIN_DROUGHT_MODEL, {"region": "murcia", "from": "2000-01-01", "to": "2024-12-31"})
FORECAST = Job("f1", JobType.FORECAST_DROUGHT, {"region": "murcia", "horizonDays": 30, "asOf": "2025-06-30"})


def test_training_fits_a_new_model_on_the_history_and_stores_it():
    observations = [weather("murcia", "2000-01-01"), weather("murcia", "2000-01-02")]
    droughts = [DroughtEvent("murcia", date(2005, 5, 1), date(2005, 9, 1), severity=3)]
    store = InMemoryModelStore()

    result = TrainDroughtModel(FakeWeather(observations, droughts), FakeModel, store).execute(TRAIN)

    assert store.models["murcia"].trained_on == (observations, droughts)
    assert result.data == {"region": "murcia", "observations": 2, "droughts": 1}


def test_training_asks_for_the_requested_period():
    source = FakeWeather([weather("murcia", "2000-01-01")])

    TrainDroughtModel(source, FakeModel, InMemoryModelStore()).execute(TRAIN)

    assert source.requested == [("murcia", date(2000, 1, 1), date(2024, 12, 31))]


def test_training_without_weather_data_is_invalid():
    with pytest.raises(InvalidJob):
        TrainDroughtModel(FakeWeather([]), FakeModel, InMemoryModelStore()).execute(TRAIN)


def test_a_job_without_its_parameters_is_invalid():
    job = Job("t2", JobType.TRAIN_DROUGHT_MODEL, {"region": "murcia"})

    with pytest.raises(InvalidJob, match="'from'"):
        TrainDroughtModel(FakeWeather([]), FakeModel, InMemoryModelStore()).execute(job)


def test_forecasting_uses_the_region_model_on_the_last_90_days():
    store = InMemoryModelStore()
    store.save("murcia", FakeModel(probability=0.72))
    source = FakeWeather([weather("murcia", "2025-06-01")])

    result = ForecastDrought(source, store).execute(FORECAST)

    assert source.requested == [("murcia", date(2025, 4, 1), date(2025, 6, 30))]
    assert result.data == {"region": "murcia", "horizonDays": 30, "probability": 0.72, "riskLevel": "high"}


def test_forecasting_needs_a_trained_model():
    with pytest.raises(ModelNotFound):
        ForecastDrought(FakeWeather([]), InMemoryModelStore()).execute(FORECAST)
