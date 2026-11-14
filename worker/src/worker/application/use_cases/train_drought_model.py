from collections.abc import Callable
from datetime import date

from worker.application.ports import DroughtModel, DroughtModelStore, WeatherProvider
from worker.application.use_cases import require_param
from worker.domain.errors import InvalidJob
from worker.domain.jobs import Job, JobResult


class TrainDroughtModel:
    """Trains a region's drought model on its weather history and past droughts, and stores it for forecasts."""

    def __init__(
        self, weather: WeatherProvider, new_model: Callable[[], DroughtModel], store: DroughtModelStore
    ) -> None:
        self._weather = weather
        self._new_model = new_model
        self._store = store

    def execute(self, job: Job) -> JobResult:
        region = require_param(job, "region")
        start = date.fromisoformat(require_param(job, "from"))
        end = date.fromisoformat(require_param(job, "to"))

        observations = self._weather.observations(region, start, end)
        droughts = self._weather.drought_history(region)
        if not observations:
            raise InvalidJob(f"no weather observations for {region} between {start} and {end}")

        model = self._new_model()
        model.train(observations, droughts)
        self._store.save(region, model)

        return JobResult(job.id, {"region": region, "observations": len(observations), "droughts": len(droughts)})
