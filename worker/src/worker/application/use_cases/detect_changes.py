from worker.application.ports import ChangeDetector, ImageryProvider
from worker.domain.errors import InvalidJob
from worker.domain.jobs import Job, JobResult


class DetectChanges:
    """Compares the earliest and the latest scene of a job and reports how much the area changed."""

    def __init__(self, imagery: ImageryProvider, detector: ChangeDetector) -> None:
        self._imagery = imagery
        self._detector = detector

    def execute(self, job: Job) -> JobResult:
        scenes = sorted(self._imagery.scenes_for(job), key=lambda scene: scene.acquired_on)
        if len(scenes) < 2:
            raise InvalidJob(f"job {job.id} needs at least 2 scenes to compare, got {len(scenes)}")
        before, after = scenes[0], scenes[-1]

        report = self._detector.detect(before, after)

        return JobResult(
            job.id,
            {
                "beforeSceneId": before.id,
                "afterSceneId": after.id,
                "changedFraction": report.changed_fraction,
                "meanChangeDb": report.mean_change_db,
                "level": report.level,
                "direction": report.direction,
            },
        )
