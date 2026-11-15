from datetime import date, timedelta

import pytest

from worker.domain.early_warning import (
    LevelQuantiles,
    Threshold,
    expected_cutoff,
    hindcast,
    probability_below,
    synthetic_status,
    warnings_for,
)

DAY = date(2026, 9, 1)
Q = LevelQuantiles(DAY, 800.0, 900.0, 1000.0)


@pytest.mark.parametrize(
    ("threshold", "p"),
    [(800.0, 0.1), (850.0, 0.3), (900.0, 0.5), (950.0, 0.7), (1000.0, 0.9), (750.0, 0.0), (700.0, 0.0), (1100.0, 1.0)],
)
def test_the_probability_is_read_off_the_quantiles_linearly_and_clamped(threshold, p):
    assert probability_below(threshold, Q) == pytest.approx(p)


def test_beyond_the_outer_quantiles_the_probability_follows_their_slope():
    assert probability_below(775.0, Q) == pytest.approx(0.0)
    assert probability_below(790.0, Q) == pytest.approx(0.06)
    assert probability_below(1010.0, Q) == pytest.approx(0.94)


def test_equal_quantiles_make_a_step():
    certain = LevelQuantiles(DAY, 900.0, 900.0, 900.0)

    assert probability_below(899.0, certain) == 0.0
    assert probability_below(901.0, certain) == 1.0


def test_quantiles_must_be_ordered():
    with pytest.raises(ValueError):
        LevelQuantiles(DAY, 900.0, 800.0, 1000.0)


def forecast(levels: list[float], start: date = DAY) -> list[LevelQuantiles]:
    """A forecast whose median falls along `levels`, with a +-100 cm spread, from the day after `start`."""
    return [LevelQuantiles(start + timedelta(days=i + 1), v - 100, v, v + 100) for i, v in enumerate(levels)]


def test_warnings_give_both_probabilities_at_each_horizon():
    t = Threshold("a", "monitored", at_risk_below_cm=950.0, isolated_below_cm=850.0)
    f = forecast([1000.0] * 6 + [900.0] * 30)

    warnings = warnings_for(t, f, DAY, (7, 14, 60))

    assert [w.horizon_days for w in warnings] == [7, 14, 60]
    assert (warnings[0].p_at_risk, warnings[0].p_isolated) == (0.7, 0.3)
    assert (warnings[2].p_at_risk, warnings[2].p_isolated) == (None, None)  # beyond the forecast


def test_a_community_without_a_threshold_has_no_probability():
    warnings = warnings_for(Threshold("a", "monitored"), forecast([900.0] * 10), DAY, (7,))

    assert (warnings[0].p_at_risk, warnings[0].p_isolated) == (None, None)


def test_the_expected_cutoff_is_the_first_day_the_median_falls_below():
    f = forecast([1000.0, 950.0, 880.0, 860.0])

    assert expected_cutoff(900.0, f) == DAY + timedelta(days=3)
    assert expected_cutoff(800.0, f) is None
    assert expected_cutoff(None, f) is None


def test_the_hindcast_lead_is_from_the_first_issue_that_warned_before_the_cutoff():
    aug1, aug15, sep1 = date(2026, 8, 1), date(2026, 8, 15), date(2026, 9, 1)
    issues = [
        (sep1, {"a": date(2026, 9, 20), "b": date(2026, 9, 25), "c": None, "d": date(2026, 10, 1)}),
        (aug1, {"a": None, "b": None, "c": None, "d": None}),
        (aug15, {"a": date(2026, 9, 10), "b": None, "c": None, "d": None}),
    ]
    observed = {"a": date(2026, 9, 18), "b": date(2026, 9, 5), "c": date(2026, 10, 2)}
    late = hindcast(issues, {"b": date(2026, 8, 20)}, judged={"b"})
    assert late.misses == ("b",)  # seen isolated before any issue predicted it

    result = hindcast(issues, observed, judged={"a", "b", "c", "d"})

    assert result.issues == (aug1, aug15, sep1)
    cases = {c.community_id: c for c in result.cases}
    assert (cases["a"].first_warned_on, cases["a"].lead_days) == (aug15, 34)
    assert (cases["b"].first_warned_on, cases["b"].lead_days) == (sep1, 4)  # warned 4 days before it was seen
    assert result.misses == ("c",)
    assert result.false_alarms == ("d",)
    assert (result.warned, result.observed, result.median_lead_days) == (2, 3, 19.0)


def test_a_community_the_radar_never_judged_is_not_a_false_alarm():
    result = hindcast([(DAY, {"x": date(2026, 9, 20)})], {}, judged=set())

    assert result.false_alarms == ()
    assert result.median_lead_days is None


def test_the_median_lead_of_an_even_count_is_the_mean_of_the_middle_two():
    issues = [(date(2026, 8, 1), {"a": DAY, "b": DAY})]
    observed = {"a": date(2026, 8, 11), "b": date(2026, 8, 21)}

    assert hindcast(issues, observed, {"a", "b"}).median_lead_days == 15.0


@pytest.mark.parametrize(
    ("level", "status"), [(1000.0, "connected"), (950.0, "at_risk"), (900.0, "at_risk"), (850.0, "isolated")]
)
def test_the_synthetic_status_of_a_level_comes_from_the_thresholds(level, status):
    t = Threshold("a", "monitored", at_risk_below_cm=950.0, isolated_below_cm=850.0)

    assert synthetic_status(level, t) == status
    assert synthetic_status(level, Threshold("b", "monitored")) == "connected"
