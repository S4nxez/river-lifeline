from dataclasses import dataclass
from datetime import date
from pathlib import Path

NISAR = "NISAR"
SENTINEL_1 = "SENTINEL-1"


@dataclass(frozen=True)
class Scene:
    """A radar acquisition (NISAR, Sentinel-1) the backend fetched and the worker downloaded to `path`."""

    id: str
    mission: str
    acquired_on: date
    polarization: str
    path: Path


def closest_scene(scenes: list[Scene], day: date, max_gap_days: int) -> Scene | None:
    """The scene acquired closest to `day`, if one is at most `max_gap_days` away (the earlier one on a tie)."""
    candidates = [scene for scene in scenes if abs((scene.acquired_on - day).days) <= max_gap_days]
    return min(candidates, key=lambda scene: (abs((scene.acquired_on - day).days), scene.acquired_on), default=None)
