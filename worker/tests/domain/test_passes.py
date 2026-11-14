import pytest
from fakes import assessment, route

from worker.domain.passes import RouteStatus


@pytest.mark.parametrize("coverage", [-0.1, 1.1])
def test_coverage_is_a_share(coverage):
    with pytest.raises(ValueError):
        assessment(coverage=coverage)


def test_a_pass_that_was_not_evaluated_claims_no_route():
    with pytest.raises(ValueError):
        assessment(evaluated=False, hub_link=None)
    with pytest.raises(ValueError):
        assessment(evaluated=False, communities=(), hub_link=route(RouteStatus.CONNECTED, 100))

    assert assessment(evaluated=False, communities=(), hub_link=None).evaluated is False


def test_the_gauge_level_of_a_day_or_the_closest_day_next_to_it():
    from datetime import date

    from worker.domain.passes import Gauge, GaugeDay

    gauge = Gauge("12900001", (GaugeDay(date(2026, 10, 20), 830.0), GaugeDay(date(2026, 10, 22), 810.0)))

    assert gauge.level_on(date(2026, 10, 22)) == 810.0
    assert gauge.level_on(date(2026, 10, 21)) == 830.0  # a tie: the earlier day
    assert gauge.level_on(date(2026, 10, 23)) == 810.0
    assert gauge.level_on(date(2026, 10, 25)) is None
    assert gauge.level_on(date(2026, 10, 24), max_days=2) == 810.0
