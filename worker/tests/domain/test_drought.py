from datetime import date

import pytest

from worker.domain.drought import DroughtEvent, DroughtForecast, DroughtModel

AS_OF = date(2026, 11, 1)


@pytest.mark.parametrize(
    ("probability", "risk"),
    [(0.0, "low"), (0.29, "low"), (0.3, "medium"), (0.59, "medium"), (0.6, "high"), (1.0, "high")],
)
def test_the_probability_sets_the_risk_level(probability, risk):
    assert DroughtForecast("tefe", 30, AS_OF, probability).risk_level == risk


@pytest.mark.parametrize("probability", [-0.01, 1.01])
def test_a_forecast_probability_is_between_0_and_1(probability):
    with pytest.raises(ValueError):
        DroughtForecast("tefe", 30, AS_OF, probability)


def test_a_drought_cannot_end_before_it_starts():
    with pytest.raises(ValueError):
        DroughtEvent(start=date(2024, 6, 1), end=date(2024, 5, 1), severity=2)


@pytest.mark.parametrize(("version", "lookback_days"), [(0, 90), (1, 0)])
def test_a_model_needs_a_version_and_a_lookback(version, lookback_days):
    with pytest.raises(ValueError):
        DroughtModel("baseline", version, lookback_days)
