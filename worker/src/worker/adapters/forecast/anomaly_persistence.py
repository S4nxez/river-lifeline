"""Gauge level forecast by anomaly persistence (experimental early warning).

The level a day of the year usually has (the climatology) plus today's departure from it, carried forward: the
forecast follows the usual seasonal curve, shifted by how much higher or lower the river is today. The spread is the
empirical 10/50/90 % quantiles of the method's own past errors at each horizon. It is the simplest benchmark of
hydrological forecasting (Pappenberger et al. 2015) and the one to beat.

Everything is computed from the days before the issue date only: no value of the issue date's future is read.
"""

from datetime import date, timedelta

import numpy as np

from worker.domain.early_warning import LevelQuantiles

SMOOTH_DAYS = 7  # the climatology is a moving mean over +-7 days of the year
BACKTEST_STEP_DAYS = 5  # past issue dates used to measure the errors
MIN_BACKTEST_ISSUES = 30


def _doy(day: date) -> int:
    """Day of the year 0..365, the 29th of February sharing the 28th's."""
    d = day.timetuple().tm_yday - 1
    leap = day.year % 4 == 0 and (day.year % 100 != 0 or day.year % 400 == 0)
    if leap and (day.month > 2 or (day.month == 2 and day.day == 29)):
        d -= 1
    return d


class AnomalyPersistence:
    """Forecasts of the gauge level from a daily series (cm, current datum)."""

    def __init__(self, levels: dict[date, float]) -> None:
        if not levels:
            raise ValueError("the gauge series is empty")
        self.levels = dict(sorted(levels.items()))

    def _known_before(self, issued: date) -> dict[date, float]:
        return {d: v for d, v in self.levels.items() if d <= issued}

    @staticmethod
    def climatology(levels: dict[date, float]) -> np.ndarray:
        """The mean level of each day of the year (365 values), smoothed over +-SMOOTH_DAYS days, circularly."""
        sums = np.zeros(365)
        counts = np.zeros(365)
        for d, v in levels.items():
            k = min(_doy(d), 364)
            sums[k] += v
            counts[k] += 1
        if not counts.any():
            raise ValueError("no level to build a climatology from")
        kernel = np.ones(2 * SMOOTH_DAYS + 1)
        wrap = lambda a: np.concatenate([a[-SMOOTH_DAYS:], a, a[:SMOOTH_DAYS]])  # noqa: E731
        s = np.convolve(wrap(sums), kernel, mode="valid")
        c = np.convolve(wrap(counts), kernel, mode="valid")
        with np.errstate(invalid="ignore", divide="ignore"):
            clim = s / c
        if np.isnan(clim).any():
            known = np.flatnonzero(~np.isnan(clim))
            clim = np.interp(np.arange(365), known, clim[known], period=365)
        return clim

    def level_on(self, issued: date, max_back_days: int = 3) -> tuple[date, float]:
        """The latest level known on the issue date (up to max_back_days before it)."""
        for k in range(max_back_days + 1):
            day = issued - timedelta(days=k)
            if day in self.levels:
                return day, self.levels[day]
        raise ValueError(f"no gauge level within {max_back_days} days before {issued}")

    def forecast(self, issued: date, horizon_days: int) -> list[LevelQuantiles]:
        """10/50/90 % quantiles of the level for each day 1..horizon_days after the issue date."""
        known = self._known_before(issued)
        clim = self.climatology(known)
        start, level = self.level_on(issued)
        anomaly = level - clim[min(_doy(start), 364)]
        errors = self.backtest_errors(known, clim, horizon_days)
        out = []
        for h in range(1, horizon_days + 1):
            day = issued + timedelta(days=h)
            point = clim[min(_doy(day), 364)] + anomaly
            q10, q50, q90 = (point + e for e in errors[h - 1])
            out.append(LevelQuantiles(day, round(float(q10), 1), round(float(q50), 1), round(float(q90), 1)))
        return out

    @staticmethod
    def backtest_errors(known: dict[date, float], clim: np.ndarray, horizon_days: int) -> np.ndarray:
        """(horizon, 3) array: the 10/50/90 % quantiles of observed minus forecast level at each horizon, over past
        issue dates whose targets are all known (all of them before the issue date of the forecast)."""
        days = sorted(known)
        if not days:
            raise ValueError("no level to backtest on")
        first, last = days[0], days[-1]
        errors: list[list[float]] = [[] for _ in range(horizon_days)]
        t = first
        while t + timedelta(days=horizon_days) <= last:
            if t in known:
                anomaly = known[t] - clim[min(_doy(t), 364)]
                for h in range(1, horizon_days + 1):
                    target = t + timedelta(days=h)
                    if target in known:
                        errors[h - 1].append(known[target] - (clim[min(_doy(target), 364)] + anomaly))
            t += timedelta(days=BACKTEST_STEP_DAYS)
        out = np.zeros((horizon_days, 3))
        for h, e in enumerate(errors):
            if len(e) < MIN_BACKTEST_ISSUES:
                raise ValueError(f"only {len(e)} past forecasts at {h + 1} days: too short a record")
            out[h] = np.quantile(e, [0.1, 0.5, 0.9])
        return out
