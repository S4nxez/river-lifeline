"""Builds the experimental early warning of an area (docs/early-warning.md, CONTRACTS.md: early_warning.json).

Runs offline from the CLI. It answers one question: had it existed, how many days ahead would it have warned which
communities were going to lose their water route? Nothing here changes the main record.
"""

from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from typing import Any

from worker.application.ports import LevelForecaster, RiverModel
from worker.domain.early_warning import (
    Threshold,
    expected_cutoff,
    hindcast,
    synthetic_status,
    warnings_for,
)

SCHEMA_VERSION = "1.0.0"
STEP_CM = 10.0
STATUSES = ("connected", "at_risk", "isolated")

LIMITATIONS = (
    "Width is not depth: a channel wide enough on the radar can still be too shallow for a boat.",
    "The water occurrence ends in 2021, and sediment moves channels: the drying map ages.",
    "Lake Tefé does not always follow the gauge on the Solimões: its level lags and differs.",
    "One dry season of radar labels (2026) to check it against.",
    "A hindcast is not a promise: the next drought will not repeat the last one.",
)


@dataclass(frozen=True)
class ObservedPass:
    """An evaluated radar pass: the status of each community, and (optionally) its water mask for the validation."""

    pass_id: str
    day: date
    statuses: dict[str, str]
    water: Any = None
    valid: Any = None


@dataclass(frozen=True)
class EarlyWarningRequest:
    aoi_id: str
    station_code: str
    datum_note: str
    issued_at: date
    hindcast_issues: tuple[date, ...]
    classes: dict[str, str]
    passes: tuple[ObservedPass, ...]
    record_min_cm: float
    occurrence: Any = None
    horizons: tuple[int, ...] = (7, 14, 30)
    forecast_days: int = 120
    method_summary: str = ""
    extra_limitations: tuple[str, ...] = field(default_factory=tuple)


class BuildEarlyWarning:
    def __init__(self, river: RiverModel, forecaster: LevelForecaster, clock=lambda: datetime.now(UTC)):
        self._river = river
        self._forecaster = forecaster
        self._clock = clock

    def execute(self, request: EarlyWarningRequest) -> dict[str, Any]:
        monitored = {cid for cid, c in request.classes.items() if c == "monitored"}
        if not request.classes:
            monitored = {cid for p in request.passes for cid in p.statuses}

        issue_levels = [self._forecaster.level_on(d)[1] for d in (request.issued_at, *request.hindcast_issues)]
        top = _ceil_step(max(issue_levels))
        levels = _levels(request.record_min_cm, top)
        thresholds, _ = self._river.thresholds(levels, request.classes, monitored)
        by_id = {t.community_id: t for t in thresholds}

        forecast = self._forecaster.forecast(request.issued_at, request.forecast_days)
        communities = sorted(
            (self._community(t, forecast, request) for t in thresholds),
            key=lambda c: (c["expected_cutoff_date"] is None, c["expected_cutoff_date"] or "", c["community_id"]),
        )
        return {
            "schema_version": SCHEMA_VERSION,
            "aoi_id": request.aoi_id,
            "generated_at": self._clock().strftime("%Y-%m-%dT%H:%M:%SZ"),
            "experimental": True,
            "method": {
                "summary": request.method_summary,
                "limitations": [*LIMITATIONS, *request.extra_limitations],
                "level_step_cm": STEP_CM,
                "levels_swept_cm": [levels[0], levels[-1]],
                "occurrence_no_data_share": round(self._river.no_data_share, 4),
            },
            "gauge": {"station_code": request.station_code, "datum_note": request.datum_note},
            "issued_at": request.issued_at.isoformat(),
            "level_forecast": [
                {"date": q.day.isoformat(), "p10_cm": q.p10_cm, "p50_cm": q.p50_cm, "p90_cm": q.p90_cm}
                for q in forecast
            ],
            "observed_level": self._observed_level(request.issued_at, request.forecast_days),
            "communities": communities,
            "hindcast": self._hindcast(request, by_id, monitored),
            "validation": self._validation(request, by_id, monitored),
        }

    def _community(self, t: Threshold, forecast, request: EarlyWarningRequest) -> dict[str, Any]:
        has = t.community_class == "monitored"
        cutoff = expected_cutoff(t.isolated_below_cm, forecast) if has else None
        return {
            "community_id": t.community_id,
            "class": t.community_class,
            "at_risk_below_cm": t.at_risk_below_cm,
            "isolated_below_cm": t.isolated_below_cm,
            "bottleneck": None
            if t.bottleneck is None
            else {"location": list(t.bottleneck), "width_class_m": t.bottleneck_width_m},
            "warnings": [
                {"horizon_days": w.horizon_days, "p_at_risk": w.p_at_risk, "p_isolated": w.p_isolated}
                for w in warnings_for(t, forecast, request.issued_at, request.horizons)
            ]
            if has
            else [],
            "expected_cutoff_date": None if cutoff is None else cutoff.isoformat(),
        }

    def _observed_level(self, issued: date, days: int) -> list[dict[str, Any]]:
        first, last = issued - timedelta(days=days), issued + timedelta(days=days)
        return [
            {"date": d.isoformat(), "level_cm": v} for d, v in self._forecaster.levels.items() if first <= d <= last
        ]

    def _hindcast(self, request: EarlyWarningRequest, by_id: dict[str, Threshold], monitored: set[str]):
        issues = []
        for issued in sorted(request.hindcast_issues):
            forecast = self._forecaster.forecast(issued, request.forecast_days)
            issues.append(
                (
                    issued,
                    {cid: expected_cutoff(by_id[cid].isolated_below_cm, forecast) for cid in monitored if cid in by_id},
                )
            )
        season = [p for p in request.passes if request.hindcast_issues and p.day >= min(request.hindcast_issues)]
        observed: dict[str, date] = {}
        judged: set[str] = set()
        for p in sorted(season, key=lambda p: p.day):
            for cid, status in p.statuses.items():
                if cid not in monitored or status == "no_data":
                    continue
                judged.add(cid)
                if status == "isolated" and cid not in observed:
                    observed[cid] = p.day
        result = hindcast(issues, observed, judged)
        return {
            "issues": [d.isoformat() for d in result.issues],
            "communities": [
                {
                    "community_id": c.community_id,
                    "observed_isolated_on": c.observed_isolated_on.isoformat(),
                    "first_warned_on": None if c.first_warned_on is None else c.first_warned_on.isoformat(),
                    "lead_days": c.lead_days,
                }
                for c in result.cases
            ],
            "misses": list(result.misses),
            "false_alarms": list(result.false_alarms),
            "predicted_cutoffs": result.predicted_cutoffs,
            "summary": {
                "median_lead_days": result.median_lead_days,
                "warned": result.warned,
                "observed": result.observed,
                "false_alarms": len(result.false_alarms),
                "passes": len(season),
            },
        }

    def _validation(self, request: EarlyWarningRequest, by_id: dict[str, Threshold], monitored: set[str]):
        confusion = {o: {s: 0 for s in STATUSES} for o in STATUSES}
        per_pass = []
        iou_sums: dict[str, list[float]] = {}
        for p in sorted(request.passes, key=lambda p: p.day):
            try:
                _, level = self._forecaster.level_on(p.day, max_back_days=1)
            except ValueError:
                continue
            row: dict[str, Any] = {"pass_id": p.pass_id, "date": p.day.isoformat(), "level_cm": level}
            for cid, observed in p.statuses.items():
                if cid in monitored and cid in by_id and observed in confusion:
                    confusion[observed][synthetic_status(level, by_id[cid])] += 1
            if p.water is not None and request.occurrence is not None:
                iou = self._river.mask_iou(level, p.water, p.valid, request.occurrence)
                row["mask_iou"] = iou
                for zone, value in iou.items():
                    if value is not None:
                        iou_sums.setdefault(zone, []).append(value)
            per_pass.append(row)
        agree = sum(confusion[s][s] for s in STATUSES)
        total = sum(sum(row.values()) for row in confusion.values())
        return {
            "mask_iou": {zone: round(sum(v) / len(v), 4) for zone, v in iou_sums.items()} or None,
            "status_confusion": {
                "observed_vs_synthetic": confusion,
                "agreement": round(agree / total, 4) if total else None,
            },
            "passes": per_pass,
        }


def _ceil_step(level: float) -> float:
    return float(int(-(-level // STEP_CM)) * STEP_CM)


def _levels(low: float, high: float) -> list[float]:
    start = float(int(low // STEP_CM) * STEP_CM)
    out = []
    level = start
    while level <= high + 1e-9:
        out.append(round(level, 1))
        level += STEP_CM
    return out
