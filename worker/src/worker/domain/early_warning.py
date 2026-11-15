"""Early warning (experimental): when the river at the gauge would cut each community off, and how likely that is.

Pure arithmetic on what the adapters measured: the level thresholds of every community (from the river twin), the
level forecast quantiles (from the forecaster) and the passes the radar judged. Nothing here changes a status of the
main record: the warnings are a separate, experimental product (docs/early-warning.md).
"""

from dataclasses import dataclass, field
from datetime import date, timedelta


@dataclass(frozen=True)
class LevelQuantiles:
    """The forecast of the gauge level on one day: its 10, 50 and 90 % quantiles (cm, current datum)."""

    day: date
    p10_cm: float
    p50_cm: float
    p90_cm: float

    def __post_init__(self) -> None:
        if not self.p10_cm <= self.p50_cm <= self.p90_cm:
            raise ValueError(f"quantiles must be ordered: {self.p10_cm}, {self.p50_cm}, {self.p90_cm}")


@dataclass(frozen=True)
class Threshold:
    """The gauge levels below which a community loses its safe route and any route, as the river twin sees it.

    None means the community keeps that route over the whole range swept. `bottleneck` is (lon, lat) of the
    approximate point where the community's safe-width water ends closest to the hub, at the at-risk level.
    """

    community_id: str
    community_class: str
    at_risk_below_cm: float | None = None
    isolated_below_cm: float | None = None
    bottleneck: tuple[float, float] | None = None
    bottleneck_width_m: int | None = None


@dataclass(frozen=True)
class Warning:
    horizon_days: int
    p_at_risk: float | None
    p_isolated: float | None


def probability_below(threshold_cm: float, q: LevelQuantiles) -> float:
    """P(level < threshold) read off the 10/50/90 % quantiles: linear between them, extended beyond with the slope of
    the outer segment, clamped to [0, 1]. Equal quantiles make a step at that level."""
    points = [(q.p10_cm, 0.1), (q.p50_cm, 0.5), (q.p90_cm, 0.9)]
    if q.p10_cm == q.p90_cm:
        return 1.0 if threshold_cm > q.p50_cm else 0.0
    if threshold_cm <= q.p10_cm:
        (x0, y0), (x1, y1) = _segment(points, first=True)
    elif threshold_cm >= q.p90_cm:
        (x0, y0), (x1, y1) = _segment(points, first=False)
    elif threshold_cm <= q.p50_cm:
        (x0, y0), (x1, y1) = points[0], points[1]
    else:
        (x0, y0), (x1, y1) = points[1], points[2]
    if x1 == x0:
        p = y1 if threshold_cm >= x1 else y0
    else:
        p = y0 + (threshold_cm - x0) * (y1 - y0) / (x1 - x0)
    return min(1.0, max(0.0, p))


def _segment(points, first):
    """The outer segment with a slope (skipping a degenerate one)."""
    a, b, c = points
    if first:
        return (a, b) if b[0] > a[0] else (a, c)
    return (b, c) if c[0] > b[0] else (a, c)


def warnings_for(
    threshold: Threshold, forecast: list[LevelQuantiles], issued: date, horizons: tuple[int, ...]
) -> list[Warning]:
    """p(at risk) and p(isolated) at each horizon after the issue date; None when the community has no such
    threshold or the forecast does not reach the horizon."""
    by_day = {q.day: q for q in forecast}
    out = []
    for h in horizons:
        q = by_day.get(issued + timedelta(days=h))

        def p(level: float | None, q=q) -> float | None:
            return None if level is None or q is None else round(probability_below(level, q), 3)

        out.append(Warning(h, p(threshold.at_risk_below_cm), p(threshold.isolated_below_cm)))
    return out


def expected_cutoff(threshold_cm: float | None, forecast: list[LevelQuantiles]) -> date | None:
    """The first day the median path is below the threshold; None if it never is within the forecast."""
    if threshold_cm is None:
        return None
    for q in sorted(forecast, key=lambda q: q.day):
        if q.p50_cm < threshold_cm:
            return q.day
    return None


# --- Hindcast -------------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class HindcastCase:
    """A community the radar saw isolated, and the first issue whose median path predicted its cut-off before."""

    community_id: str
    observed_isolated_on: date
    first_warned_on: date | None
    lead_days: int | None


@dataclass(frozen=True)
class HindcastResult:
    issues: tuple[date, ...]
    cases: tuple[HindcastCase, ...]
    misses: tuple[str, ...]
    false_alarms: tuple[str, ...]
    median_lead_days: float | None
    warned: int
    observed: int
    predicted_cutoffs: dict[str, dict[str, str | None]] = field(default_factory=dict)


def hindcast(
    issues: list[tuple[date, dict[str, date | None]]],
    observed_isolated_on: dict[str, date],
    judged: set[str],
) -> HindcastResult:
    """Scores forecasts issued in the past against what the radar saw.

    `issues` holds, for every issue date (only data available that day was used), each community's expected cut-off
    date (None: the median path never crosses its isolation level). `observed_isolated_on` is the first pass date the
    radar saw each community isolated, after the first issue. `judged` are the communities the passes had data on.

    A community is warned when an issue on or before its observed cut-off predicted it; lead = observed - issue date.
    A miss: seen isolated, never warned in time. A false alarm: predicted by some issue, judged by the radar, never
    seen isolated.
    """
    ordered = sorted(issues, key=lambda item: item[0])
    cases = []
    for community_id, seen_on in sorted(observed_isolated_on.items()):
        warned_on = next(
            (issued for issued, cutoffs in ordered if issued <= seen_on and cutoffs.get(community_id) is not None),
            None,
        )
        lead = None if warned_on is None else (seen_on - warned_on).days
        cases.append(HindcastCase(community_id, seen_on, warned_on, lead))
    warned = [c for c in cases if c.first_warned_on is not None]
    predicted = {cid for _, cutoffs in ordered for cid, cut in cutoffs.items() if cut is not None}
    false_alarms = sorted(cid for cid in predicted if cid in judged and cid not in observed_isolated_on)
    return HindcastResult(
        issues=tuple(issued for issued, _ in ordered),
        cases=tuple(cases),
        misses=tuple(c.community_id for c in cases if c.first_warned_on is None),
        false_alarms=tuple(false_alarms),
        median_lead_days=_median([c.lead_days for c in warned]),
        warned=len(warned),
        observed=len(cases),
        predicted_cutoffs={
            issued.isoformat(): {cid: None if cut is None else cut.isoformat() for cid, cut in cutoffs.items()}
            for issued, cutoffs in ordered
        },
    )


def _median(values: list[int]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    mid = len(ordered) // 2
    return float(ordered[mid]) if len(ordered) % 2 else (ordered[mid - 1] + ordered[mid]) / 2


def synthetic_status(level_cm: float, threshold: Threshold) -> str:
    """The status the river twin gives a community at a gauge level, from its thresholds."""
    if threshold.isolated_below_cm is not None and level_cm <= threshold.isolated_below_cm:
        return "isolated"
    if threshold.at_risk_below_cm is not None and level_cm <= threshold.at_risk_below_cm:
        return "at_risk"
    return "connected"
