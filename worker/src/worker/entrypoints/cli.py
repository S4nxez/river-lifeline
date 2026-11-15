import argparse
import json
import logging
import os
import signal
import sys
from datetime import date
from pathlib import Path

from worker.adapters.backend.client import BackendClient
from worker.adapters.imaging.change import NumpyChangeMapper
from worker.adapters.imaging.pass_analyzer import RadarPassAnalyzer
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

    The worker only announces the job types it can run: the backend never hands it the others. The drought model
    (`BaselineDroughtModel`) is not implemented yet, so its job types are left out until one is passed here.
    """
    backend = backend or BackendClient(settings.backend_url, settings.worker_token)
    pass_analyzer = pass_analyzer or RadarPassAnalyzer()
    handlers: dict[JobType, JobHandler] = {
        JobType.ASSESS_PASS: AssessPass(pass_analyzer, backend),
        JobType.REEVALUATE_PASS: ReevaluatePass(pass_analyzer, backend),
        JobType.DETECT_CHANGE: DetectChange(NumpyChangeMapper(), backend),
        JobType.CLASSIFY_COMMUNITIES: ClassifyCommunities(),
    }
    if drought_model is not None:
        handlers[JobType.TRAIN_DROUGHT_MODEL] = TrainDroughtModel(drought_model)
        handlers[JobType.FORECAST_DROUGHT] = ForecastDrought(drought_model)
    return Worker(backend, Dispatcher(handlers), settings.poll_interval_seconds)


EARLY_WARNING_SUMMARY = (
    "Each pixel is dry when the gauge is below the level it was dry at in 1984-2021 (JRC water occurrence against "
    "the gauge record); the routes of the method run on that water at every level, giving each community the level "
    "below which it is at risk or cut off. An anomaly-persistence forecast of the gauge, with the spread of its past "
    "errors, turns those levels into probabilities and an expected cut-off date."
)


def early_warning(argv: list[str]) -> Path:
    """`python -m worker early-warning ...`: builds aois/<id>/early_warning.json offline (docs/early-warning.md)."""
    from worker.adapters.files.early_warning_inputs import read_area, read_gauge, read_occurrence, read_passes
    from worker.adapters.forecast.anomaly_persistence import AnomalyPersistence
    from worker.adapters.imaging.river_twin import RiverTwin
    from worker.application.use_cases.build_early_warning import BuildEarlyWarning, EarlyWarningRequest

    ap = argparse.ArgumentParser(prog="worker early-warning", description=early_warning.__doc__)
    ap.add_argument("--area-file", required=True, type=Path, help="server/src/main/resources/areas/<id>.json")
    ap.add_argument("--occurrence", required=True, type=Path, help="the area's occurrence PNG")
    ap.add_argument("--gauge", required=True, type=Path, help="gauge history (scripts/fetch_gauge_history.py)")
    ap.add_argument("--passes", type=Path, help="evaluated passes and classes (JSON, see read_passes)")
    ap.add_argument("--out", required=True, type=Path, help="aois/<id>/early_warning.json to write")
    ap.add_argument("--issued-at", type=date.fromisoformat, help="forecast date (default: the last gauge day)")
    ap.add_argument("--hindcast", default="2026-08-01,2026-08-15,2026-09-01", help="hindcast issue dates")
    ap.add_argument("--record", default="1984-01-01,2021-12-31", help="gauge years the occurrence covers")
    ap.add_argument("--summary-note", default="", help="appended to method.summary")
    a = ap.parse_args(argv)

    area = read_area(a.area_file)
    levels, gauge = read_gauge(a.gauge)
    first, last = (date.fromisoformat(d) for d in a.record.split(","))
    record = [v for d, v in levels.items() if first <= d <= last]
    occurrence = read_occurrence(a.occurrence, area.grid)
    classes, passes = read_passes(a.passes, area.grid)
    river = RiverTwin(area, occurrence, record)
    forecaster = AnomalyPersistence(levels)
    datum = "; ".join(c.get("note", "") for c in gauge.get("datum_changes", [])) or "levels as published"
    request = EarlyWarningRequest(
        aoi_id=area.id,
        station_code=gauge.get("station_code", ""),
        datum_note=f"Current datum. {datum}",
        issued_at=a.issued_at or max(levels),
        hindcast_issues=tuple(date.fromisoformat(d) for d in a.hindcast.split(",") if d),
        classes=classes,
        passes=tuple(passes),
        record_min_cm=min(record),
        occurrence=occurrence,
        method_summary=f"{EARLY_WARNING_SUMMARY} {a.summary_note}".strip(),
    )
    document = BuildEarlyWarning(river, forecaster).execute(request)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(document, indent=1, ensure_ascii=False), encoding="utf-8")
    return a.out


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    if sys.argv[1:2] == ["early-warning"]:
        logging.getLogger(__name__).info("early warning written to %s", early_warning(sys.argv[2:]))
        return
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
