"""Whole-record classes of the communities of an area, and the per-pass counts the page shows (classify_communities).

A community's class comes from every evaluated pass it has data on:
  - no_data:    no evaluated pass has data on it;
  - monitored:  at least one pass found it a water route (connected or at risk);
  - inland:     every pass found it isolated with the nearest water farther than the access radius;
  - unresolved: it has data and water within reach, but no pass ever found it a route.

Per-pass counts are over monitored communities only. An isolation is unconfirmed when the community was not isolated
on its previous pass with data (or has none): it counts as at risk until a second pass confirms it.
"""

from dataclasses import dataclass
from enum import StrEnum

from worker.domain.passes import EvaluatedPass, RouteResult, RouteStatus


@dataclass(frozen=True)
class ClassifyCommunitiesInputs:
    area_id: str
    access_radius_m: float
    community_ids: tuple[str, ...]
    # Every evaluated pass of the area, oldest first
    passes: tuple[EvaluatedPass, ...]


class CommunityClass(StrEnum):
    MONITORED = "monitored"
    UNRESOLVED = "unresolved"
    INLAND = "inland"
    NO_DATA = "no_data"


@dataclass(frozen=True)
class StatusCounts:
    connected: int = 0
    at_risk: int = 0
    isolated: int = 0
    no_data: int = 0


@dataclass(frozen=True)
class PassCounts:
    pass_id: str
    counts: StatusCounts
    # Isolated on this pass but not on the previous one with data
    unconfirmed: tuple[str, ...]


@dataclass(frozen=True)
class Classification:
    area_id: str
    classes: dict[str, CommunityClass]
    passes: tuple[PassCounts, ...]

    @property
    def totals(self) -> dict[CommunityClass, int]:
        return {kind: sum(1 for c in self.classes.values() if c == kind) for kind in CommunityClass}


def classify(
    area_id: str, access_radius_m: float, community_ids: list[str], passes: list[EvaluatedPass]
) -> Classification:
    ordered = sorted(passes, key=lambda p: (p.date, p.time_utc))
    history = {cid: [_route(p, cid) for p in ordered] for cid in community_ids}
    classes = {cid: _class_of(routes, access_radius_m) for cid, routes in history.items()}
    monitored = [cid for cid in community_ids if classes[cid] == CommunityClass.MONITORED]

    counted = []
    for index, evaluated in enumerate(ordered):
        tally = {status: 0 for status in RouteStatus}
        unconfirmed = []
        for cid in monitored:
            status = history[cid][index].status
            if status == RouteStatus.ISOLATED and not _isolated_before(history[cid], index):
                unconfirmed.append(cid)
                status = RouteStatus.AT_RISK
            tally[status] += 1
        counts = StatusCounts(
            connected=tally[RouteStatus.CONNECTED],
            at_risk=tally[RouteStatus.AT_RISK],
            isolated=tally[RouteStatus.ISOLATED],
            no_data=tally[RouteStatus.NO_DATA],
        )
        counted.append(PassCounts(evaluated.pass_id, counts, tuple(unconfirmed)))
    return Classification(area_id, classes, tuple(counted))


_NO_DATA = RouteResult(RouteStatus.NO_DATA, None, None)


def _route(evaluated: EvaluatedPass, community_id: str) -> RouteResult:
    return evaluated.status_of(community_id) or _NO_DATA


def _class_of(routes: list[RouteResult], access_radius_m: float) -> CommunityClass:
    seen = [route for route in routes if route.status != RouteStatus.NO_DATA]
    if not seen:
        return CommunityClass.NO_DATA
    if any(route.status in (RouteStatus.CONNECTED, RouteStatus.AT_RISK) for route in seen):
        return CommunityClass.MONITORED
    if all(route.distance_to_water_m is not None and route.distance_to_water_m > access_radius_m for route in seen):
        return CommunityClass.INLAND
    return CommunityClass.UNRESOLVED


def _isolated_before(routes: list[RouteResult], index: int) -> bool:
    """Whether the community's last pass with data before `index` found it isolated too."""
    earlier = [route for route in routes[:index] if route.status != RouteStatus.NO_DATA]
    return bool(earlier) and earlier[-1].status == RouteStatus.ISOLATED
