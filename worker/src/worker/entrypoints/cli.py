import logging
import os
import signal

from worker.adapters.backend.client import BackendClient
from worker.adapters.imaging.log_ratio import LogRatioChangeDetector
from worker.adapters.ml.drought_model import BaselineDroughtModel
from worker.adapters.ml.model_store import PickleModelStore
from worker.application.dispatcher import Dispatcher
from worker.application.use_cases.detect_changes import DetectChanges
from worker.application.use_cases.forecast_drought import ForecastDrought
from worker.application.use_cases.train_drought_model import TrainDroughtModel
from worker.application.worker import Worker
from worker.config import Settings
from worker.domain.jobs import JobType


def build_worker(settings: Settings, backend: BackendClient | None = None) -> Worker:
    """Wires the adapters into the use cases. Swap an implementation here, nowhere else."""
    backend = backend or BackendClient(settings.backend_url, settings.worker_token, settings.scenes_dir)
    models = PickleModelStore(settings.models_dir)
    dispatcher = Dispatcher(
        {
            JobType.DETECT_CHANGES: DetectChanges(backend, LogRatioChangeDetector()),
            JobType.TRAIN_DROUGHT_MODEL: TrainDroughtModel(backend, BaselineDroughtModel, models),
            JobType.FORECAST_DROUGHT: ForecastDrought(backend, models),
        }
    )
    return Worker(backend, dispatcher, settings.poll_interval_seconds)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    worker = build_worker(Settings.from_env(os.environ))

    stopping = False

    def stop(signum: int, _frame: object) -> None:
        nonlocal stopping
        logging.getLogger(__name__).info("signal %s: stopping after the current job", signum)
        stopping = True

    # podman/docker stop sends SIGTERM: finish the job in progress instead of dropping it
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    worker.run_forever(should_stop=lambda: stopping)
