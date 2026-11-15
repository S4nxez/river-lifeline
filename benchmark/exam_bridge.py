"""Runs the accuracy exam of the earlier processing code (`river-monitor benchmark`, exam 3) on this repository's
worker method.

The exam runs its own pipeline end to end and scores what it writes (catalog.json and the masks of every pass). This
bridge replaces only the step that judges one pass (`lifeline.pipeline.process_pass`): the pass's granule windows,
Sentinel-1 scenes, gauge days and occurrence layer, read by the exam's own code and cache, are handed to the worker's
`RadarPassAnalyzer` as the backend would hand them in an `assess_pass` job; its result is written back in the
format the exam scores. Everything else (pass search, gauge, whole-record classes, change, references, scoring) is
the exam's.

Usage (both packages installed in one environment, MONITOR_CACHE_DIR and EARTHDATA_TOKEN set):
    python benchmark/exam_bridge.py --label v3-new-worker [--workers 1] [--skip-real]
"""

import argparse
import datetime as dt
import os
import time

import numpy as np
import requests
from lifeline import config, pipeline
from lifeline.analysis import radar as old_radar
from lifeline.output import layers as old_layers
from lifeline.output.catalog import pass_dir
from lifeline.sources import sentinel1 as old_s1
from lifeline.sources.occurrence import load_occurrence

from worker.adapters.imaging import pass_analyzer as analyzer_module
from worker.adapters.imaging import sentinel1 as s1_module
from worker.adapters.imaging.pass_analyzer import RadarPassAnalyzer
from worker.adapters.imaging.water import wide_water
from worker.domain.area import (
    AdaptiveThreshold,
    AreaGrid,
    AreaSpec,
    Circle,
    Community,
    LonLat,
    LowWater,
    Method,
    Sentinel1Method,
    Speckle,
    Target,
    VegetatedWater,
)
from worker.domain.passes import AssessPassInputs, Gauge, GaugeDay, Granule, PassInfo, Sentinel1Date
from worker.domain.rasters import XY, DType, Raster, RasterMeta, RasterRef, Unit, WaterClass

STATUS = config.STATUS


# --- What the worker's analyzer computes on the way, kept for the files the exam reads ------------------------------


class Captured:
    """The filtered HH and HV and the Sentinel-1 view of the pass being judged (one pass per process at a time)."""

    hh = None
    hv = None
    view = None


_water_mask = analyzer_module.water_mask
_vegetated_water = analyzer_module.vegetated_water
_pass_view = s1_module.pass_view


def _capture_water_mask(db, *args, **kwargs):
    Captured.hh = db
    return _water_mask(db, *args, **kwargs)


def _capture_vegetated_water(hh, hv, *args, **kwargs):
    Captured.hv = hv
    return _vegetated_water(hh, hv, *args, **kwargs)


def _capture_pass_view(*args, **kwargs):
    Captured.view = _pass_view(*args, **kwargs)
    return Captured.view


# --- Inputs: the exam's area, windows and scenes as an assess_pass job ---------------------------------------------


class MemoryRasters:
    """The RasterSource of the analyzer: rasters by href, read lazily from the exam's cache."""

    def __init__(self):
        self.loaders = {}

    def add(self, href, meta, load):
        self.loaders[href] = (meta, load)
        return RasterRef(href, meta)

    def read(self, ref):
        meta, load = self.loaders[ref.href]
        return Raster(
            meta, np.ascontiguousarray(load(), dtype="<f4" if meta.dtype == DType.FLOAT32 else "<u1").tobytes()
        )


def _lonlat(value):
    if value is None:
        return None
    if isinstance(value, dict):
        return LonLat(float(value["lon"]), float(value["lat"]))
    return LonLat(float(value[0]), float(value[1]))


def _target(t):
    return Target(t["name"], float(t["lon"]), float(t["lat"]), float(t["radius_m"]), _lonlat(t.get("access")))


def area_spec(area, communities):
    """The exam's area dict (geometry.area.load_area) as the contract's AreaSpec."""
    lon0, lat0, lon1, lat1 = area["bbox"]
    step = area["step_deg"]
    c = area.get("circle")
    grid = AreaGrid(
        crs="EPSG:4326",
        width=area["nx"],
        height=area["ny"],
        origin=XY(lon0 + step / 2, lat1 - step / 2),
        step=XY(step, -step),
        pixel_m=area["px_m"],
        bbox=(lon0, lat0, lon1, lat1),
        circle=Circle(c["lon"], c["lat"], c["radius_km"]) if c else None,
    )
    adaptive = area.get("water_db_adaptive")
    vegetated = area.get("vegetated_water")
    low = area.get("low_water")
    s1 = area.get("sentinel1")
    method = Method(
        access_radius_m=area["access_radius_m"],
        min_coverage=area["min_coverage"],
        min_coverage_shown=area.get("min_coverage_shown", 0.0),
        valid_floor_db=area.get("valid_floor_db", old_radar.DEFAULT_VALID_FLOOR_DB),
        edge_margin_m=area.get("edge_margin_m", 0),
        water_db=area["water_db"],
        water_db_weak=area.get("water_db_weak"),
        water_db_adaptive=AdaptiveThreshold(tuple(adaptive["range_db"]), adaptive["tile_m"], adaptive["min_tiles"])
        if adaptive
        else None,
        speckle=Speckle(
            area.get("speckle_filter", "box"),
            area.get("speckle_looks"),
            area.get("speckle_m", old_radar.DEFAULT_SPECKLE_M),
        ),
        widths_m=tuple(area["widths_m"]),
        safe_width_m=area["safe_width_m"],
        vegetated_water=VegetatedWater(vegetated["hh_min_db"], vegetated["ratio_min_db"], vegetated["near_water_m"])
        if vegetated
        else None,
        low_water=LowWater(low["gauge_max_cm"], tuple(low["seasonal_pct"]), low["patch_radius_m"], low["veto_radius_m"])
        if low
        else None,
        sentinel1=Sentinel1Method(
            s1["max_days"], s1.get("level_tolerance_cm", 0), s1["water_db"], s1.get("weak_db"), s1["land_db"]
        )
        if s1
        else None,
    )
    return AreaSpec(
        id=area["id"],
        name=area["name"],
        grid=grid,
        hub=_target(area["hub"]),
        river=_target(area["river"]),
        communities=tuple(
            Community(
                str(p["id"]), p.get("name", ""), p.get("place", "unknown"), p["lon"], p["lat"], _lonlat(p.get("access"))
            )
            for p in communities
        ),
        method=method,
    )


def granules(area, ps, token, rasters):
    """The pass's granule windows (exam cache, else ASF) as the job's granules; (granules, megabytes read)."""
    bands = ("HHHH", "HVHV") if area.get("vegetated_water") else ("HHHH",)
    out, read_mb = [], 0.0
    for i, g in enumerate(ps["granules"]):
        w = old_radar.granule_window(area, g, token, bands, cache_dir=config.cache_dir())
        if w is None:
            continue
        read_mb += w["mb"]

        def band(name, w=w, i=i):
            values = w["bands"][name]
            meta = RasterMeta(
                values.shape[1],
                values.shape[0],
                DType.FLOAT32,
                "NaN",
                Unit.LINEAR_POWER,
                f"EPSG:{w['epsg']}",
                XY(w["x0"], w["y0"]),
                XY(w["dx"], w["dy"]),
            )
            return rasters.add(f"/internal/worker/rasters/g{i}_{name}", meta, lambda: values)

        out.append(
            Granule(g.get("name", f"granule-{i}"), i, band("HHHH"), band("HVHV") if "HVHV" in w["bands"] else None)
        )
    return tuple(out), read_mb


def gauge_of(river):
    if not river or not river.get("series"):
        return None
    return Gauge("gauge", tuple(GaugeDay(dt.date.fromisoformat(d), float(v)) for d, v in river["series"]))


def sentinel1_candidates(area, spec, ps, gauge, rasters):
    """The usable Sentinel-1 dates of the pass, each date's tiles averaged onto the area grid (linear power), as the
    backend would hand them; cached as raw mosaics under MONITOR_CACHE_DIR/sentinel1_raw."""
    cfg = area.get("sentinel1")
    if not cfg:
        return ()
    day = dt.date.fromisoformat(ps["date"])
    scenes = old_s1.by_date(old_s1.search_scenes(area, day, cfg["max_days"], requests.post))
    candidates = tuple(Sentinel1Date(dt.date.fromisoformat(d), ()) for d in sorted(scenes))
    usable = s1_module.usable_dates(candidates, day, gauge, cfg["max_days"], cfg.get("level_tolerance_cm", 0))
    grid = spec.grid
    meta = RasterMeta(
        grid.width, grid.height, DType.FLOAT32, "NaN", Unit.LINEAR_POWER, grid.crs, grid.origin, grid.step
    )
    token = {}
    out = []
    for date in usable:
        d = date.date.isoformat()
        tiles = []
        for k, item in enumerate(scenes[d]):
            path = os.path.join(config.cache_dir(), "sentinel1_raw", area["id"], f"{d}_{item['id']}.npy")

            def load(item=item, path=path):
                if os.path.exists(path):
                    return np.load(path)
                if "t" not in token:
                    token["t"] = old_s1.access_token(requests.get)
                values = old_s1.read_band(old_s1.signed(item["assets"]["vv"]["href"], token["t"]), area)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                np.save(path, values)
                return values

            tiles.append(rasters.add(f"/internal/worker/rasters/s1_{d}_{k}", meta, load))
        out.append(Sentinel1Date(date.date, tuple(tiles)))
    return tuple(out)


def occurrence_of(area, river, spec, rasters):
    if not area.get("low_water") or not river:
        return None
    occ = load_occurrence(area, river["areas_dir"])
    if occ is None:
        return None
    grid = spec.grid
    meta = RasterMeta(grid.width, grid.height, DType.UINT8, 255.0, Unit.PERCENT, grid.crs, grid.origin, grid.step)
    return rasters.add("/internal/worker/rasters/occurrence", meta, lambda: occ)


# --- The replacement of lifeline.pipeline.process_pass ----------------------------------------------------------


def _route(r):
    return [STATUS[r.status.value], r.bottleneck_m, r.distance_to_water_m]


def process_pass(area, communities, ps, out_root, token, read=None, river=None):
    """Judges one pass with the worker's analyzer and writes what the exam reads, as the earlier code did."""
    t0 = time.time()
    Captured.hh = Captured.hv = Captured.view = None
    rasters = MemoryRasters()
    spec = area_spec(area, communities)
    gauge = gauge_of(river)
    grans, read_mb = granules(area, ps, token, rasters)
    if not grans:
        raise RuntimeError(f"pass {ps['id']}: no granule window reaches the area")
    inputs = AssessPassInputs(
        area=spec,
        pass_info=PassInfo(ps["id"], dt.date.fromisoformat(ps["date"]), ps["time_utc"], ps["track"], ps["direction"]),
        granules=grans,
        gauge=gauge,
        occurrence=occurrence_of(area, river, spec, rasters),
        sentinel1_candidates=sentinel1_candidates(area, spec, ps, gauge, rasters),
    )
    result = RadarPassAnalyzer().assess(inputs, rasters)

    codes = np.frombuffer(result.rasters["water"].data, np.uint8).reshape(spec.grid.height, spec.grid.width)
    valid = codes != WaterClass.NO_DATA
    water = np.isin(codes, (WaterClass.WIDE, WaterClass.NARROW))
    vegetated = (codes == WaterClass.VEGETATED) if result.vegetated_km2 is not None else None
    wide = wide_water(water, spec.grid.pixel_m, spec.method.safe_width_m)
    pdir = pass_dir(area, ps["id"], out_root)
    os.makedirs(pdir, exist_ok=True)
    old_layers.save_radar(Captured.hh, os.path.join(pdir, "radar.webp"))
    old_layers.save_water(water, wide, os.path.join(pdir, "water.png"), vegetated)
    old_layers.save_mask(water, valid, os.path.join(pdir, "water.npz"), vegetated)
    if config.keep_backscatter():
        old_layers.save_backscatter(Captured.hh, Captured.hv, os.path.join(pdir, "backscatter.npz"))
    s1_path = os.path.join(pdir, pipeline.SENTINEL1_FILE)
    view = Captured.view
    if view is not None:
        old_layers.save_sentinel1(
            {"dates": [d.isoformat() for d in view.dates], "water": view.water, "land": view.land, "seen": view.seen},
            s1_path,
        )
    elif os.path.exists(s1_path):
        os.remove(s1_path)

    t = result.threshold
    threshold = {"source": t.source, "strong_db": t.strong_db, "weak_db": t.weak_db}
    if t.measured_db is not None:
        threshold["measured_db"] = t.measured_db
    if t.tiles is not None:
        threshold["tiles"] = t.tiles
    entry = {k: ps[k] for k in pipeline.ENTRY_KEYS}
    entry.update(
        read_mb=round(read_mb, 1),
        spacing_m=result.spacing_m,
        files={"radar": "radar.webp", "water": "water.png"},
        threshold=threshold,
        vegetated_km2=result.vegetated_km2,
        water_km2=result.water_km2,
        narrow_km2=result.narrow_km2,
        coverage=result.coverage,
        shown=result.shown,
        evaluated=result.evaluated,
    )
    if result.evaluated:
        entry["status"] = {c.community_id: _route(c.route) for c in result.communities}
        entry["hub_link"] = _route(result.hub_link)
        entry["counts"] = {k: 0 for k in STATUS}
        for c in result.communities:
            entry["counts"][c.route.status.value] += 1
        if result.unverified_route:
            entry["unverified_route"] = list(result.unverified_route)
    if river:
        entry["river_level_cm"] = result.river_level_cm
    if result.low_water is not None:
        entry["low_water"] = result.low_water
    if result.sentinel1 is not None:
        a = result.sentinel1
        entry["sentinel1"] = {
            "dates": [d.isoformat() for d in a.dates],
            "judged_km2": a.judged_km2,
            "agreement": a.agreement,
            "both_water_km2": a.both_water_km2,
            "nisar_only_km2": a.nisar_only_km2,
            "sentinel1_only_km2": a.sentinel1_only_km2,
            "mud_km2": a.mud_km2,
        }
    entry["seconds"] = round(time.time() - t0, 1)
    return entry


def install():
    """Makes the exam judge every pass with the worker's analyzer."""
    analyzer_module.water_mask = _capture_water_mask
    analyzer_module.vegetated_water = _capture_vegetated_water
    s1_module.pass_view = _capture_pass_view
    pipeline.process_pass = process_pass


def main():
    from lifeline.benchmark import exam
    from lifeline.geometry.area import load_area

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--label", required=True)
    ap.add_argument("--area", default="tefe")
    ap.add_argument("--workers", type=int, default=1)
    ap.add_argument("--skip-real", action="store_true")
    a = ap.parse_args()
    cache = config.cache_dir()
    if not cache:
        raise SystemExit("MONITOR_CACHE_DIR is not set")
    install()
    result, json_path, md_path = exam.run(load_area(a.area), a.label, cache, a.workers, skip_real=a.skip_real)
    config.log(f"benchmark written to {md_path}")


if __name__ == "__main__":
    main()
