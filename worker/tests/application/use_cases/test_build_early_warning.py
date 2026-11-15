from datetime import UTC, date, datetime, timedelta

import pytest

from worker.application.use_cases.build_early_warning import BuildEarlyWarning, EarlyWarningRequest, ObservedPass
from worker.domain.early_warning import LevelQuantiles, Threshold

AUG1, AUG15, SEP1 = date(2026, 8, 1), date(2026, 8, 15), date(2026, 9, 1)


class FakeRiver:
    """Fixed thresholds; records the levels it was asked to sweep."""

    no_data_share = 0.01

    def __init__(self) -> None:
        self.swept: list[float] = []

    def thresholds(self, levels_cm, classes, monitored):
        self.swept = list(levels_cm)
        return [
            Threshold("near", "monitored", 950.0, 850.0, (-64.7, -3.3), 0),
            Threshold("far", "monitored", 600.0, 500.0, None, 40),
            Threshold("dry", "inland"),
        ], []

    def mask_iou(self, level_cm, observed_water, observed_valid, occurrence):
        return {"area": 0.8, "permanent_water": 0.9, "seasonal_ground": None}


class FakeForecaster:
    """A river falling 5 cm a day from 1200 cm on 2026-07-01; records the issue dates it forecast from."""

    def __init__(self) -> None:
        self.levels = {date(2026, 7, 1) + timedelta(days=i): 1200.0 - 5 * i for i in range(200)}
        self.issued: list[date] = []

    def level_on(self, issued, max_back_days=3):
        return issued, self.levels[issued]

    def forecast(self, issued, horizon_days):
        self.issued.append(issued)
        start = self.levels[issued]
        return [
            LevelQuantiles(issued + timedelta(days=h), start - 5 * h - 50, start - 5 * h, start - 5 * h + 50)
            for h in range(1, horizon_days + 1)
        ]


def request(**changes) -> EarlyWarningRequest:
    fields = dict(
        aoi_id="tefe",
        station_code="12900001",
        datum_note="current datum",
        issued_at=SEP1,
        hindcast_issues=(AUG1, AUG15, SEP1),
        classes={"near": "monitored", "far": "monitored", "dry": "inland"},
        passes=(
            ObservedPass("p1", date(2026, 8, 20), {"near": "connected", "far": "connected"}, "water", "valid"),
            ObservedPass("p2", date(2026, 9, 10), {"near": "isolated", "far": "connected", "dry": "isolated"}),
        ),
        record_min_cm=415.0,
        occurrence="occ",
        method_summary="summary",
    )
    fields.update(changes)
    return EarlyWarningRequest(**fields)


def build(req=None):
    river, forecaster = FakeRiver(), FakeForecaster()
    clock = lambda: datetime(2026, 11, 15, 12, tzinfo=UTC)  # noqa: E731
    return BuildEarlyWarning(river, forecaster, clock).execute(req or request()), river, forecaster


def test_the_document_has_the_shape_of_the_contract():
    doc, _, _ = build()

    assert doc["schema_version"] == "1.0.0" and doc["experimental"] is True
    assert doc["aoi_id"] == "tefe" and doc["issued_at"] == "2026-09-01"
    assert doc["generated_at"] == "2026-11-15T12:00:00Z"
    assert doc["gauge"] == {"station_code": "12900001", "datum_note": "current datum"}
    assert len(doc["level_forecast"]) == 120
    assert any("Width is not depth" in limit for limit in doc["method"]["limitations"])


def test_the_sweep_runs_from_the_record_minimum_to_the_highest_issue_level():
    _, river, forecaster = build()

    assert river.swept[0] == 410.0
    assert forecaster.levels[AUG1] == 1045.0
    assert river.swept[-1] == 1050.0  # the highest issue level, rounded up to the step
    assert river.swept[1] - river.swept[0] == 10.0


def test_communities_are_sorted_by_expected_cutoff_and_only_monitored_ones_get_warnings():
    doc, _, _ = build()

    ids = [c["community_id"] for c in doc["communities"]]
    near = doc["communities"][0]
    assert ids[0] == "near"  # its isolation level is crossed first
    assert near["expected_cutoff_date"] == "2026-09-10"  # 890 cm on Sep 1, 5 cm a day: 845 cm on day 9
    assert [w["horizon_days"] for w in near["warnings"]] == [7, 14, 30]
    assert near["bottleneck"] == {"location": [-64.7, -3.3], "width_class_m": 0}
    dry = next(c for c in doc["communities"] if c["community_id"] == "dry")
    assert dry["warnings"] == [] and dry["expected_cutoff_date"] is None and dry["class"] == "inland"


def test_each_hindcast_issue_uses_its_own_date_only():
    doc, _, forecaster = build()

    assert forecaster.issued == [SEP1, AUG1, AUG15, SEP1]  # the forecast of the page, then the three issues
    case = doc["hindcast"]["communities"][0]
    assert case["community_id"] == "near" and case["observed_isolated_on"] == "2026-09-10"
    assert case["first_warned_on"] == "2026-08-01" and case["lead_days"] == 40
    assert doc["hindcast"]["summary"]["observed"] == 1
    assert doc["hindcast"]["false_alarms"] == ["far"]  # predicted, judged, never seen isolated


def test_the_validation_compares_the_synthetic_statuses_and_masks_with_the_radar():
    doc, _, _ = build()

    confusion = doc["validation"]["status_confusion"]["observed_vs_synthetic"]
    # p1 at 950 cm: near seen connected, at risk in the twin; far connected in both. p2 at 845 cm: both agree
    assert confusion["connected"]["connected"] == 2
    assert confusion["connected"]["at_risk"] == 1
    assert confusion["isolated"]["isolated"] == 1
    assert doc["validation"]["status_confusion"]["agreement"] == pytest.approx(0.75)
    assert doc["validation"]["mask_iou"] == {"area": 0.8, "permanent_water": 0.9}
    assert doc["validation"]["passes"][0]["mask_iou"]["area"] == 0.8
    assert "mask_iou" not in doc["validation"]["passes"][1]


def test_without_classes_every_judged_community_is_monitored():
    doc, _, _ = build(request(classes={}))

    assert {c["community_id"] for c in doc["communities"] if c["warnings"]} == {"near", "far"}
