"""The river twin (experimental early warning): which pixels hold water at a given gauge level, and below which level
each community loses its route to the hub.

A pixel the water occurrence says was water p % of the time (JRC, 1984-2021) is taken to be dry when the gauge is
below the (100 - p) % quantile of the gauge record over the same years: its drying level. The water at a level L is
every pixel whose drying level is below L; its routes are measured with the method's own connectivity (water.py),
unchanged.
"""

from dataclasses import dataclass

import numpy as np
from scipy import ndimage

from worker.adapters.imaging.grid import area_mask, to_pixel
from worker.adapters.imaging.water import Hub, connectivity, near_labels, width_mask
from worker.domain.area import AreaSpec
from worker.domain.early_warning import Threshold
from worker.domain.passes import RouteStatus
from worker.domain.rasters import OCCURRENCE_NO_DATA


def drying_table(record_cm: np.ndarray) -> np.ndarray:
    """The drying level of each occurrence 0..100 %: the (100 - p) % quantile of the gauge record."""
    record = np.asarray(record_cm, dtype=np.float64)
    record = record[np.isfinite(record)]
    if record.size == 0:
        raise ValueError("the gauge record is empty")
    return np.quantile(record, 1.0 - np.arange(101) / 100.0).astype(np.float32)


def drying_levels(occurrence: np.ndarray, record_cm: np.ndarray) -> np.ndarray:
    """The gauge level below which each pixel is dry (cm). Occurrence 100: the record's minimum (always water above
    it); 0: its maximum (never water within the record); no data: +inf (never water)."""
    table = drying_table(record_cm)
    occ = np.asarray(occurrence)
    out = np.full(occ.shape, np.inf, np.float32)
    known = occ != OCCURRENCE_NO_DATA
    out[known] = table[np.clip(occ[known], 0, 100)]
    return out


def water_at(drying: np.ndarray, level_cm: float, in_area: np.ndarray) -> np.ndarray:
    """The synthetic water mask of a gauge level."""
    return (drying < level_cm) & in_area


@dataclass(frozen=True)
class SweepRow:
    level_cm: float
    statuses: dict[str, RouteStatus]
    bottlenecks: dict[str, int | None]


class RiverTwin:
    """The drying map of an area and the route of every community at any gauge level."""

    def __init__(self, area: AreaSpec, occurrence: np.ndarray, record_cm: np.ndarray) -> None:
        self.area = area
        self.in_area = area_mask(area.grid)
        self.drying = drying_levels(occurrence, record_cm)
        # the water mask only changes when the level crosses one of these: levels between two give the same routes
        self.breaks = np.unique(drying_table(record_cm))
        self._rows: dict[tuple[int, tuple[str, ...]], SweepRow] = {}
        self.no_data_share = float((np.asarray(occurrence)[self.in_area] == OCCURRENCE_NO_DATA).mean())
        grid = area.grid
        access = area.hub.access
        row, col = to_pixel(grid, access.lon if access else area.hub.lon, access.lat if access else area.hub.lat)
        self.hub = Hub(row, col, area.hub.radius_m)
        self.targets = {
            c.id: to_pixel(grid, c.access.lon if c.access else c.lon, c.access.lat if c.access else c.lat)
            for c in area.communities
        }

    def routes(self, level_cm: float, ids: list[str]) -> SweepRow:
        """Status and bottleneck of every community at a level (measured once per distinct water mask)."""
        key = (int(np.searchsorted(self.breaks, level_cm, side="left")), tuple(ids))
        if key not in self._rows:
            measured = self._measure(level_cm, ids)
            self._rows[key] = measured
        cached = self._rows[key]
        return SweepRow(level_cm, cached.statuses, cached.bottlenecks)

    def _measure(self, level_cm: float, ids: list[str]) -> SweepRow:
        m = self.area.method
        water = water_at(self.drying, level_cm, self.in_area)
        valid = np.ones_like(water)
        results = connectivity(
            water,
            valid,
            self.area.grid.pixel_m,
            self.hub,
            [self.targets[i] for i in ids],
            m.widths_m,
            m.access_radius_m,
            m.safe_width_m,
            self.in_area,
        )
        return SweepRow(
            level_cm,
            {i: r.status for i, r in zip(ids, results, strict=True)},
            {i: r.bottleneck_m for i, r in zip(ids, results, strict=True)},
        )

    def thresholds(
        self, levels_cm: list[float], classes: dict[str, str], monitored: set[str]
    ) -> tuple[list[Threshold], list[SweepRow]]:
        """Sweeps the levels from the highest down: a community's at-risk level is the highest one where it is no
        longer connected, its isolation level the highest one where it is isolated. Only monitored communities get
        thresholds; the others are reported with their class."""
        ids = [c.id for c in self.area.communities if c.id in monitored]
        at_risk: dict[str, float] = {}
        isolated: dict[str, float] = {}
        rows = []
        for level in sorted(levels_cm, reverse=True):
            row = self.routes(level, ids)
            rows.append(row)
            for cid, status in row.statuses.items():
                if status in (RouteStatus.AT_RISK, RouteStatus.ISOLATED) and cid not in at_risk:
                    at_risk[cid] = level
                if status == RouteStatus.ISOLATED and cid not in isolated:
                    isolated[cid] = level
        points = self._bottleneck_points(at_risk)
        out = []
        for c in self.area.communities:
            if c.id not in monitored:
                out.append(Threshold(c.id, classes.get(c.id, "no_data")))
                continue
            point = points.get(c.id)
            width = None
            if c.id in at_risk:
                below = [r for r in rows if r.level_cm == at_risk[c.id]]
                width = below[0].bottlenecks.get(c.id) if below else None
            out.append(
                Threshold(
                    c.id,
                    classes.get(c.id, "monitored"),
                    at_risk.get(c.id),
                    isolated.get(c.id),
                    point,
                    width,
                )
            )
        return out, rows

    def _bottleneck_points(self, at_risk: dict[str, float]) -> dict[str, tuple[float, float]]:
        """For each community, the point of its own safe-width water closest to the hub's, at its at-risk level: an
        approximation of where its route narrows below the safe width."""
        grid, m = self.area.grid, self.area.method
        px = grid.pixel_m
        out = {}
        for level in sorted(set(at_risk.values())):
            water = water_at(self.drying, level, self.in_area)
            inside = ndimage.distance_transform_edt(water) * px
            labels, _ = ndimage.label(width_mask(water, inside, m.safe_width_m, px))
            hub_labels = near_labels(labels, self.hub.row, self.hub.col, self.hub.radius_m / px)
            hub_mask = np.isin(labels, list(hub_labels)) if hub_labels else np.zeros_like(water)
            if not hub_mask.any():
                continue
            distance = ndimage.distance_transform_edt(~hub_mask)
            for cid, lvl in at_risk.items():
                if lvl != level:
                    continue
                r, c = self.targets[cid]
                own = near_labels(labels, r, c, m.access_radius_m / px) - hub_labels
                if not own:
                    continue
                mask = np.isin(labels, list(own))
                pick = np.unravel_index(np.argmin(np.where(mask, distance, np.inf)), mask.shape)
                out[cid] = (
                    round(float(grid.origin.x + pick[1] * grid.step.x), 5),
                    round(float(grid.origin.y + pick[0] * grid.step.y), 5),
                )
        return out

    # --- validation against the radar ----------------------------------------------------------------------------

    def mask_iou(self, level_cm: float, observed_water: np.ndarray, observed_valid: np.ndarray, occurrence: np.ndarray):
        """IoU of the synthetic water at a level against the radar's water, inside the area where the radar had data:
        over the whole area, on permanent water (occurrence >= 90 %) and on seasonal ground (30-90 %)."""
        synthetic = water_at(self.drying, level_cm, self.in_area)
        judged = self.in_area & observed_valid
        occ = np.asarray(occurrence)
        known = occ != OCCURRENCE_NO_DATA
        zones = {
            "area": judged,
            "permanent_water": judged & known & (occ >= 90),
            "seasonal_ground": judged & known & (occ >= 30) & (occ < 90),
        }
        out = {}
        for name, zone in zones.items():
            union = int(((synthetic | observed_water) & zone).sum())
            out[name] = round(int((synthetic & observed_water & zone).sum()) / union, 4) if union else None
        return out
