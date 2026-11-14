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
