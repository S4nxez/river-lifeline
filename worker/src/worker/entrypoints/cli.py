import logging
import os
import signal

from worker.adapters.backend.client import BackendClient
from worker.adapters.imaging.change import NumpyChangeMapper
from worker.application.dispatcher import Dispatcher
from worker.application.ports import DroughtModelAlgorithm, JobHandler, PassAnalyzer
from worker.application.use_cases.assess_pass import AssessPass, ReevaluatePass
from worker.application.use_cases.classify_communities import ClassifyCommunities
from worker.application.use_cases.detect_change import DetectChange
from worker.application.use_cases.forecast_drought import ForecastDrought
from worker.application.use_cases.train_drought_model import TrainDroughtModel
from worker.application.worker import Worker
from worker.config import Settings
from worker.domain.jobs import JobType


def build_worker(
    settings: Settings,
    backend: BackendClient | None = None,
    pass_analyzer: PassAnalyzer | None = None,
    drought_model: DroughtModelAlgorithm | None = None,
) -> Worker:
    """Wires the adapters into the use cases. Swap an implementation here, nowhere else.

    The worker only announces the job types it can run: the backend never hands it the others. The pass analysis
    (`RadarPassAnalyzer`) and the drought model (`BaselineDroughtModel`) are not implemented yet, so their job types
    are left out until they are passed here.
    """
    backend = backend or BackendClient(settings.backend_url, settings.worker_token)
    handlers: dict[JobType, JobHandler] = {
        JobType.DETECT_CHANGE: DetectChange(NumpyChangeMapper(), backend),
        JobType.CLASSIFY_COMMUNITIES: ClassifyCommunities(),
    }
    if pass_analyzer is not None:
        handlers[JobType.ASSESS_PASS] = AssessPass(pass_analyzer, backend)
        handlers[JobType.REEVALUATE_PASS] = ReevaluatePass(pass_analyzer, backend)
    if drought_model is not None:
        handlers[JobType.TRAIN_DROUGHT_MODEL] = TrainDroughtModel(drought_model)
        handlers[JobType.FORECAST_DROUGHT] = ForecastDrought(drought_model)
    return Worker(backend, Dispatcher(handlers), settings.poll_interval_seconds)


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
