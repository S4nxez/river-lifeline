import pytest
from fakes import FakeDetector, FakeImagery, scene

from worker.application.use_cases.detect_changes import DetectChanges
from worker.domain.errors import InvalidJob
from worker.domain.imagery import ChangeReport
from worker.domain.jobs import Job, JobType

JOB = Job("42", JobType.DETECT_CHANGES)


def test_compares_the_earliest_and_the_latest_scene():
    detector = FakeDetector(ChangeReport(0.3, -4.2))
    imagery = FakeImagery(scene("mid", "2024-02-01"), scene("last", "2024-03-01"), scene("first", "2024-01-01"))

    DetectChanges(imagery, detector).execute(JOB)

    [(before, after)] = detector.compared
    assert (before.id, after.id) == ("first", "last")


def test_reports_the_change_with_its_level_and_direction():
    imagery = FakeImagery(scene("a", "2024-01-01"), scene("b", "2024-02-01"))

    result = DetectChanges(imagery, FakeDetector(ChangeReport(0.3, -4.2))).execute(JOB)

    assert result.job_id == "42"
    assert result.data == {
        "beforeSceneId": "a",
        "afterSceneId": "b",
        "changedFraction": 0.3,
        "meanChangeDb": -4.2,
        "level": "high",
        "direction": "decrease",
    }


def test_needs_at_least_two_scenes():
    with pytest.raises(InvalidJob):
        DetectChanges(FakeImagery(scene("a", "2024-01-01")), FakeDetector(ChangeReport(0, 0))).execute(JOB)
