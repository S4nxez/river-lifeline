"""Whether the communities around the hub (Tefé) can still reach it by water."""

from dataclasses import dataclass
from datetime import date
from enum import StrEnum

# Narrowest channel the monitor still counts as a working route; below it a community is at risk
CONNECTED_MIN_WIDTH_M = 60

# Farthest a Sentinel-1 scene may be from the NISAR pass to cross-check it: rivers move, older scenes mislead
MAX_CROSSCHECK_GAP_DAYS = 4


class ConnectivityStatus(StrEnum):
    CONNECTED = "connected"
    AT_RISK = "at_risk"
    ISOLATED = "isolated"
    NO_DATA = "no_data"


@dataclass(frozen=True)
class Community:
    id: str
    name: str
    lat: float
    lng: float


@dataclass(frozen=True)
class MonitoredArea:
    """The hub every route leads to, and the communities whose route to it is assessed."""

    hub: Community
    communities: list[Community]


@dataclass(frozen=True)
class RouteMeasurement:
    """What one radar scene shows of a community's water route to the hub."""

    community_id: str
    # Whether the scene covers the community and the whole route
    covered: bool
    # Narrowest point of the route; None when there is no water route at all
    width_m: float | None

    def __post_init__(self) -> None:
        if self.width_m is not None and self.width_m <= 0:
            raise ValueError(f"a route width must be positive, got {self.width_m}")

    @property
    def status(self) -> ConnectivityStatus:
        if not self.covered:
            return ConnectivityStatus.NO_DATA
        if self.width_m is None:
            return ConnectivityStatus.ISOLATED
        if self.width_m >= CONNECTED_MIN_WIDTH_M:
            return ConnectivityStatus.CONNECTED
        return ConnectivityStatus.AT_RISK


@dataclass(frozen=True)
class CrossCheck:
    """Whether a Sentinel-1 scene confirms the NISAR status of a community."""

    scene_id: str
    scene_on: date
    agrees: bool


def cross_check(
    nisar: RouteMeasurement, sentinel1: RouteMeasurement, scene_id: str, scene_on: date
) -> CrossCheck | None:
    """Compares the two statuses; None when either scene did not cover the route (nothing to compare)."""
    if not (nisar.covered and sentinel1.covered):
        return None
    return CrossCheck(scene_id, scene_on, agrees=nisar.status == sentinel1.status)
