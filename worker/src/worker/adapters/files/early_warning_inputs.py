"""Reads the inputs of the experimental early warning from files (the offline CLI): the area file and its
communities, the occurrence PNG, the gauge history and the evaluated passes.

The area grid is derived from the area file exactly as the backend derives it for the worker's jobs
(api/worker-api.md, "The area grid"): the jobs carry it, but this offline run has no job.
"""

import json
import math
from datetime import date
from pathlib import Path

import numpy as np
from PIL import Image

from worker.application.use_cases.build_early_warning import ObservedPass
from worker.domain.area import (
    AreaGrid,
    AreaSpec,
    Circle,
    Community,
    LonLat,
    Method,
    Speckle,
    Target,
)
from worker.domain.rasters import XY

M_PER_DEG_LON_EQUATOR = 111_320.0
M_PER_DEG_LAT = 110_570.0


def _round_half_up(x: float) -> int:
    return math.floor(x + 0.5)


def _round5(x: float) -> float:
    return math.floor(x * 100_000.0 + 0.5) / 100_000.0


def _point(value) -> LonLat | None:
    if value is None:
        return None
    if isinstance(value, dict):
        return LonLat(float(value["lon"]), float(value["lat"]))
    return LonLat(float(value[0]), float(value[1]))


def grid_of(area: dict) -> AreaGrid:
    """The area grid: for a circle the bbox is the square around it, rounded to 1e-5 degrees."""
    step = float(area["step_deg"])
    c = area.get("circle")
    if c:
        r = c["radius_km"] * 1000.0
        dlat = r / M_PER_DEG_LAT
        dlon = r / (M_PER_DEG_LON_EQUATOR * math.cos(math.radians(c["lat"])))
        bbox = (_round5(c["lon"] - dlon), _round5(c["lat"] - dlat), _round5(c["lon"] + dlon), _round5(c["lat"] + dlat))
    else:
        bbox = tuple(float(v) for v in area["bbox"])
    lon0, lat0, lon1, lat1 = bbox
    mid = math.radians((lat0 + lat1) / 2)
    return AreaGrid(
        crs="EPSG:4326",
        width=_round_half_up((lon1 - lon0) / step),
        height=_round_half_up((lat1 - lat0) / step),
        origin=XY(lon0 + step / 2, lat1 - step / 2),
        step=XY(step, -step),
        pixel_m=step * M_PER_DEG_LON_EQUATOR * math.sqrt(math.cos(mid)),
        bbox=bbox,
        circle=Circle(c["lon"], c["lat"], c["radius_km"]) if c else None,
    )


def read_area(area_file: Path) -> AreaSpec:
    """The area file and the communities file it names, as the AreaSpec of the worker's jobs (the parameters the
    early warning uses; the others take neutral values)."""
    area = json.loads(area_file.read_text(encoding="utf-8"))
    communities = []
    if area.get("communities"):
        geojson = json.loads((area_file.parent / area["communities"]).read_text(encoding="utf-8"))
        for f in geojson["features"]:
            p = f["properties"]
            lon, lat = f["geometry"]["coordinates"][:2]
            communities.append(
                Community(str(p["id"]), p.get("name", ""), p.get("place", "unknown"), lon, lat, _point(p.get("access")))
            )

    def target(t: dict) -> Target:
        return Target(t["name"], float(t["lon"]), float(t["lat"]), float(t["radius_m"]), _point(t.get("access")))

    return AreaSpec(
        id=area["id"],
        name=area["name"],
        grid=grid_of(area),
        hub=target(area["hub"]),
        river=target(area["river"]),
        communities=tuple(communities),
        method=Method(
            access_radius_m=float(area["access_radius_m"]),
            min_coverage=float(area["min_coverage"]),
            min_coverage_shown=float(area.get("min_coverage_shown", 0.0)),
            valid_floor_db=float(area.get("valid_floor_db", -40.0)),
            edge_margin_m=float(area.get("edge_margin_m", 0.0)),
            water_db=float(area["water_db"]),
            water_db_weak=area.get("water_db_weak"),
            water_db_adaptive=None,
            speckle=Speckle(area.get("speckle_filter", "box"), area.get("speckle_looks"), area.get("speckle_m", 100.0)),
            widths_m=tuple(float(w) for w in area["widths_m"]),
            safe_width_m=float(area["safe_width_m"]),
            vegetated_water=None,
            low_water=None,
            sentinel1=None,
        ),
    )


def read_occurrence(path: Path, grid: AreaGrid) -> np.ndarray:
    """The occurrence PNG (8-bit grey, 0-100 %, 255 no data) on the area grid."""
    with Image.open(path) as image:
        occurrence = np.asarray(image.convert("L") if image.mode != "L" else image, dtype=np.uint8)
    if occurrence.shape != (grid.height, grid.width):
        raise ValueError(f"occurrence is {occurrence.shape}, the area grid {(grid.height, grid.width)}")
    return occurrence


def read_gauge(path: Path) -> tuple[dict[date, float], dict]:
    """The daily gauge history (scripts/fetch_gauge_history.py): levels by day, and the document's metadata."""
    document = json.loads(path.read_text(encoding="utf-8"))
    levels = {date.fromisoformat(d): float(v) for d, v in document["days"]}
    return levels, {k: v for k, v in document.items() if k != "days"}


def read_passes(path: Path | None, grid: AreaGrid) -> tuple[dict[str, str], list[ObservedPass]]:
    """The whole-record classes and the evaluated passes:
    {"classes": {id: class}, "passes": [{"pass_id", "date", "statuses": {id: status}, "water": "x.npy" | null}]}.
    A water file holds the pass's water classes on the area grid (uint8: 1-2 open water, 255 no data)."""
    if path is None:
        return {}, []
    document = json.loads(path.read_text(encoding="utf-8"))
    passes = []
    for p in document.get("passes", []):
        water = valid = None
        if p.get("water"):
            loaded = np.load(path.parent / p["water"])
            codes = loaded["codes"] if hasattr(loaded, "files") else loaded
            if codes.shape != (grid.height, grid.width):
                raise ValueError(f"water of {p['pass_id']} is {codes.shape}, not the area grid")
            water, valid = np.isin(codes, (1, 2)), codes != 255
        passes.append(ObservedPass(p["pass_id"], date.fromisoformat(p["date"]), dict(p["statuses"]), water, valid))
    return dict(document.get("classes", {})), passes
