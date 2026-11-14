from datetime import date, timedelta

from worker.application.ports import DroughtModelStore, WeatherProvider
from worker.application.use_cases import require_param
from worker.domain.drought import DroughtForecast
from worker.domain.jobs import Job, JobResult


class ForecastDrought:
    """Forecasts the drought risk of a region from its recent weather, with the region's trained model."""

    # Recent weather the model looks at
    LOOKBACK_DAYS = 90

    def __init__(self, weather: WeatherProvider, store: DroughtModelStore) -> None:
        self._weather = weather
        self._store = store

    def execute(self, job: Job) -> JobResult:
        region = require_param(job, "region")
        horizon_days = int(require_param(job, "horizonDays"))
        today = date.fromisoformat(require_param(job, "asOf"))

        model = self._store.load(region)
        recent = self._weather.observations(region, today - timedelta(days=self.LOOKBACK_DAYS), today)
        forecast = DroughtForecast(region, horizon_days, model.predict(recent, horizon_days))

        return JobResult(
            job.id,
            {
                "region": forecast.region,
                "horizonDays": forecast.horizon_days,
                "probability": forecast.probability,
                "riskLevel": forecast.risk_level,
            },
        )
