from worker.application.ports import DroughtForecaster
from worker.application.use_cases import inputs_of
from worker.domain.drought import DroughtForecast, ForecastDroughtInputs
from worker.domain.errors import InvalidJob
from worker.domain.jobs import Job, JobResult


class ForecastDrought:
    """Forecasts the drought risk of a region from the recent weather and the trained model the job carries."""

    def __init__(self, forecaster: DroughtForecaster) -> None:
        self._forecaster = forecaster

    def execute(self, job: Job) -> JobResult:
        inputs = inputs_of(job, ForecastDroughtInputs)
        if any(day.day > inputs.as_of for day in inputs.recent):
            raise InvalidJob(f"job {job.id} carries weather after its asOf date {inputs.as_of}")

        probability = self._forecaster.predict(inputs.model, list(inputs.recent), inputs.horizon_days)
        forecast = DroughtForecast(inputs.region, inputs.horizon_days, inputs.as_of, probability)

        return JobResult(
            job.id,
            {
                "region": forecast.region,
                "horizonDays": forecast.horizon_days,
                "asOf": forecast.as_of.isoformat(),
                "probability": forecast.probability,
                "riskLevel": forecast.risk_level,
            },
        )
