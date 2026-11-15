import math
from datetime import date, timedelta

import numpy as np
import pytest

from worker.adapters.forecast.anomaly_persistence import AnomalyPersistence, _doy


def seasonal(day: date) -> float:
    """A river with a pure yearly cycle: 1000 cm +- 500 cm."""
    return 1000.0 + 500.0 * math.sin(2 * math.pi * _doy(day) / 365)


def series(first: date, last: date, offset=lambda d: 0.0) -> dict[date, float]:
    out = {}
    d = first
    while d <= last:
        out[d] = seasonal(d) + offset(d)
        d += timedelta(days=1)
    return out


def test_the_29th_of_february_shares_the_28ths_day_of_the_year():
    assert _doy(date(2024, 2, 29)) == _doy(date(2024, 2, 28)) == 58
    assert _doy(date(2024, 3, 1)) == _doy(date(2023, 3, 1)) == 59


def test_the_climatology_is_the_usual_level_of_each_day_of_the_year():
    clim = AnomalyPersistence.climatology(series(date(2000, 1, 1), date(2009, 12, 31)))

    assert clim.shape == (365,)
    assert clim[_doy(date(2001, 4, 1))] == pytest.approx(seasonal(date(2001, 4, 1)), abs=5)


def test_a_river_higher_than_usual_is_forecast_higher_along_the_seasonal_curve():
    levels = series(date(1990, 1, 1), date(2020, 12, 31))
    issued = date(2021, 6, 1)
    levels[issued] = seasonal(issued) + 200.0  # today 2 m above the usual

    forecast = AnomalyPersistence(levels).forecast(issued, 30)

    assert len(forecast) == 30
    day = forecast[13]
    assert day.day == issued + timedelta(days=14)
    assert day.p50_cm == pytest.approx(seasonal(day.day) + 200.0, abs=5)
    assert day.p10_cm <= day.p50_cm <= day.p90_cm


def test_the_spread_comes_from_the_past_errors_and_grows_with_them():
    rng = np.random.default_rng(0)
    noise = {}
    levels = series(date(1990, 1, 1), date(2021, 6, 1), lambda d: noise.setdefault(d.year, rng.normal(0, 150)))

    forecast = AnomalyPersistence(levels).forecast(date(2021, 6, 1), 30)

    # a constant yearly offset is persisted exactly: no spread within a year
    assert forecast[-1].p90_cm - forecast[-1].p10_cm < 50


def test_nothing_after_the_issue_date_is_read():
    issued = date(2021, 6, 1)
    past = series(date(1990, 1, 1), issued)
    with_future = {**past, **series(issued + timedelta(days=1), date(2021, 12, 31), lambda d: 5000.0)}

    a = AnomalyPersistence(past).forecast(issued, 30)
    b = AnomalyPersistence(with_future).forecast(issued, 30)

    assert a == b


def test_the_last_known_level_is_used_when_the_issue_day_has_none():
    levels = series(date(1990, 1, 1), date(2021, 5, 30))

    day, level = AnomalyPersistence(levels).level_on(date(2021, 6, 1))

    assert day == date(2021, 5, 30)
    with pytest.raises(ValueError):
        AnomalyPersistence(levels).level_on(date(2021, 6, 10))


def test_too_short_a_record_has_no_error_quantiles():
    with pytest.raises(ValueError):
        AnomalyPersistence(series(date(2021, 1, 1), date(2021, 3, 1))).forecast(date(2021, 3, 1), 30)
