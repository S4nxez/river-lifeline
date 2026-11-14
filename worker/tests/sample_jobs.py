"""Jobs as the backend sends them (api/worker-api.yaml), for the adapter tests."""

import copy

GRID = {
    "crs": "EPSG:4326",
    "width": 4,
    "height": 3,
    "origin": {"x": -65.0, "y": -3.0},
    "step": {"x": 0.01, "y": -0.01},
    "pixelM": 1000.0,
    "bbox": [-65.005, -3.025, -64.965, -2.995],
    "circle": {"lon": -64.985, "lat": -3.01, "radiusKm": 2},
}


def raster(raster_id: str, **changes) -> dict:
    item = {
        "href": f"/internal/worker/rasters/{raster_id}",
        "width": 4,
        "height": 3,
        "dtype": "uint8",
        "nodata": 255,
        "unit": "class",
        "crs": "EPSG:4326",
        "origin": {"x": -65.0, "y": -3.0},
        "step": {"x": 0.01, "y": -0.01},
    }
    item.update(changes)
    return item


def band(raster_id: str) -> dict:
    return raster(
        raster_id,
        width=2,
        height=2,
        dtype="float32",
        nodata="NaN",
        unit="linear_power",
        crs="EPSG:32720",
        origin={"x": 612345.0, "y": 9654321.0},
        step={"x": 20.0, "y": -20.0},
    )


AREA = {
    "id": "tefe",
    "name": "Tefé",
    "grid": GRID,
    "hub": {"name": "Port of Tefé", "lon": -64.7188, "lat": -3.3542, "radiusM": 300, "access": None},
    "river": {"name": "Solimões", "lon": -64.6, "lat": -3.2, "radiusM": 500},
    "communities": [
        {"id": "uarini", "name": "Uarini", "place": "town", "lon": -65.11, "lat": -2.99, "access": None},
        {
            "id": "nogueira",
            "name": "Nogueira",
            "place": "village",
            "lon": -64.86,
            "lat": -3.29,
            "access": {"lon": -64.861, "lat": -3.291},
        },
    ],
    "method": {
        "accessRadiusM": 500,
        "minCoverage": 0.8,
        "minCoverageShown": 0.2,
        "validFloorDb": -40,
        "edgeMarginM": 1000,
        "waterDb": -16,
        "waterDbWeak": -14,
        "waterDbAdaptive": {"rangeDb": [-20, -12], "tileM": 5000, "minTiles": 4},
        "speckle": {"filter": "refined_lee", "looks": 4.4, "windowM": None},
        "widthsM": [40, 60, 100, 200, 400],
        "safeWidthM": 60,
        "vegetatedWater": {"hhMinDb": -8, "ratioMinDb": 6, "nearWaterM": 300},
        "lowWater": {"gaugeMaxCm": 1200, "seasonalPct": [10, 90], "patchRadiusM": 200, "vetoRadiusM": 400},
        "sentinel1": {"maxDays": 4, "levelToleranceCm": 30, "waterDb": -18, "weakDb": None, "landDb": -12},
    },
}

PASS = {"id": "2026-10-23_t161A", "date": "2026-10-23", "timeUtc": "10:02", "track": 161, "direction": "ascending"}

GAUGE = {"station": "12900001", "days": [{"day": "2026-10-22", "levelCm": 812.5}]}

ROUTES = [
    {"communityId": "uarini", "status": "connected", "bottleneckM": 100, "distanceToWaterM": 0},
    {"communityId": "nogueira", "status": "isolated", "bottleneckM": None, "distanceToWaterM": 900},
]

WEATHER = [{"day": "2026-10-31", "precipitationMm": 2.5, "temperatureC": 31.0, "soilMoisture": None}]

INPUTS = {
    "assess_pass": {
        "area": AREA,
        "pass": PASS,
        "granules": [
            {"id": "NISAR_L2_PR_GCOV_033_161_A_176", "frame": 176, "bands": {"HH": band("hh"), "HV": band("hv")}}
        ],
        "gauge": GAUGE,
        "occurrence": raster("occ", unit="percent"),
        "sentinel1Candidates": [{"date": "2026-10-25", "tiles": [band("s1a"), band("s1b")]}],
    },
    "reevaluate_pass": {
        "area": AREA,
        "pass": PASS,
        "water": raster("water"),
        "sentinel1": {"dates": ["2026-10-25"], "classes": raster("s1")},
        "gauge": None,
        "occurrence": None,
    },
    "detect_change": {
        "grid": GRID,
        "passId": "2026-10-23_t161A",
        "sincePassId": "2026-10-11_t161A",
        "previous": raster("previous"),
        "current": raster("current"),
    },
    "classify_communities": {
        "areaId": "tefe",
        "accessRadiusM": 500,
        "communityIds": ["uarini", "nogueira"],
        "passes": [{"passId": "2026-10-23_t161A", "date": "2026-10-23", "timeUtc": "10:02", "communities": ROUTES}],
    },
    "train_drought_model": {
        "region": "tefe",
        "observations": WEATHER,
        "droughts": [{"start": "2024-08-01", "end": "2024-10-15", "severity": 3}],
    },
    "forecast_drought": {
        "region": "tefe",
        "horizonDays": 30,
        "asOf": "2026-11-01",
        "model": {"kind": "baseline", "version": 1, "lookbackDays": 90, "parameters": {"bias": 0.1}},
        "recent": WEATHER,
    },
}


def job(job_type: str, job_id: str = "lease-7", **changes) -> dict:
    body = {
        "id": job_id,
        "type": job_type,
        "contractVersion": 1,
        "leaseSeconds": 600,
        "inputs": copy.deepcopy(INPUTS[job_type]),
    }
    body.update(changes)
    return body
