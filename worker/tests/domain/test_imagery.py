import pytest

from worker.domain.imagery import ChangeReport


@pytest.mark.parametrize(
    ("changed_fraction", "level"),
    [(0.0, "low"), (0.099, "low"), (0.10, "medium"), (0.249, "medium"), (0.25, "high"), (1.0, "high")],
)
def test_the_changed_share_sets_the_level(changed_fraction, level):
    assert ChangeReport(changed_fraction, mean_change_db=1.0).level == level


def test_the_sign_of_the_mean_change_sets_the_direction():
    assert ChangeReport(0.3, mean_change_db=2.5).direction == "increase"
    assert ChangeReport(0.3, mean_change_db=-2.5).direction == "decrease"


@pytest.mark.parametrize("changed_fraction", [-0.1, 1.1])
def test_the_changed_share_is_a_fraction(changed_fraction):
    with pytest.raises(ValueError):
        ChangeReport(changed_fraction, mean_change_db=0.0)
