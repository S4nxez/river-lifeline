from worker.application.ports import DroughtModelTrainer
from worker.application.use_cases import inputs_of
from worker.domain.drought import TrainDroughtModelInputs
from worker.domain.errors import InvalidJob
from worker.domain.jobs import Job, JobResult


class TrainDroughtModel:
    """Trains a region's drought model on the weather history and past droughts the job carries. The model is part
    of the result: the backend stores it and sends it back with every forecast job."""

    def __init__(self, trainer: DroughtModelTrainer) -> None:
        self._trainer = trainer

    def execute(self, job: Job) -> JobResult:
        inputs = inputs_of(job, TrainDroughtModelInputs)
        if not inputs.observations:
            raise InvalidJob(f"job {job.id} carries no weather observations for {inputs.region}")

        model = self._trainer.train(list(inputs.observations), list(inputs.droughts))

        return JobResult(
            job.id,
            {
                "region": inputs.region,
                "observations": len(inputs.observations),
                "droughts": len(inputs.droughts),
                "model": {
                    "kind": model.kind,
                    "version": model.version,
                    "lookbackDays": model.lookback_days,
                    "parameters": model.parameters,
                },
            },
        )
