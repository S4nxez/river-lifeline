"""Turns the domain's results into the JSON shapes of the contract (api/worker-api.yaml, JobResultData)."""

import math
from typing import Any

from worker.domain.connectivity import Classification, CommunityClass
from worker.domain.passes import CommunityRoute, PassAssessment, RouteResult, Sentinel1Agreement
from worker.domain.rasters import RasterMeta


def raster_meta(meta: RasterMeta) -> dict[str, Any]:
    nodata = meta.nodata
    if isinstance(nodata, float) and math.isnan(nodata):
        nodata = "NaN"
    elif isinstance(nodata, float) and nodata.is_integer():
        nodata = int(nodata)
    return {
        "width": meta.width,
        "height": meta.height,
        "dtype": meta.dtype.value,
        "nodata": nodata,
        "unit": meta.unit.value,
        "crs": meta.crs,
        "origin": {"x": meta.origin.x, "y": meta.origin.y},
        "step": {"x": meta.step.x, "y": meta.step.y},
    }


def route(result: RouteResult) -> dict[str, Any]:
    return {
        "status": result.status.value,
        "bottleneckM": result.bottleneck_m,
        "distanceToWaterM": result.distance_to_water_m,
    }


def community_route(item: CommunityRoute) -> dict[str, Any]:
    return {"communityId": item.community_id, **route(item.route)}


def sentinel1_agreement(agreement: Sentinel1Agreement) -> dict[str, Any]:
    return {
        "dates": [day.isoformat() for day in agreement.dates],
        "judgedKm2": agreement.judged_km2,
        "agreement": agreement.agreement,
        "bothWaterKm2": agreement.both_water_km2,
        "nisarOnlyKm2": agreement.nisar_only_km2,
        "sentinel1OnlyKm2": agreement.sentinel1_only_km2,
        "mudKm2": agreement.mud_km2,
    }


def pass_assessment(assessment: PassAssessment) -> dict[str, Any]:
    threshold: dict[str, Any] = {
        "source": assessment.threshold.source,
        "strongDb": assessment.threshold.strong_db,
        "weakDb": assessment.threshold.weak_db,
    }
    if assessment.threshold.measured_db is not None:
        threshold["measuredDb"] = assessment.threshold.measured_db
    if assessment.threshold.tiles is not None:
        threshold["tiles"] = assessment.threshold.tiles
    stats: dict[str, Any] = {"spacingM": assessment.spacing_m}
    if assessment.seconds is not None:
        stats["seconds"] = assessment.seconds
    return {
        "passId": assessment.pass_id,
        "coverage": assessment.coverage,
        "shown": assessment.shown,
        "evaluated": assessment.evaluated,
        "waterKm2": assessment.water_km2,
        "narrowKm2": assessment.narrow_km2,
        "vegetatedKm2": assessment.vegetated_km2,
        "threshold": threshold,
        "riverLevelCm": assessment.river_level_cm,
        "lowWater": assessment.low_water,
        "hubLink": route(assessment.hub_link) if assessment.hub_link else None,
        "communities": [community_route(item) for item in assessment.communities],
        "unverifiedRoute": list(assessment.unverified_route),
        "sentinel1": sentinel1_agreement(assessment.sentinel1) if assessment.sentinel1 else None,
        "stats": stats,
        "rasters": {name: raster_meta(raster.meta) for name, raster in assessment.rasters.items()},
    }


def classification(result: Classification) -> dict[str, Any]:
    totals = result.totals
    return {
        "areaId": result.area_id,
        "classes": {cid: kind.value for cid, kind in result.classes.items()},
        "totals": {
            "monitored": totals[CommunityClass.MONITORED],
            "unresolved": totals[CommunityClass.UNRESOLVED],
            "inland": totals[CommunityClass.INLAND],
            "noData": totals[CommunityClass.NO_DATA],
        },
        "passes": [
            {
                "passId": item.pass_id,
                "counts": {
                    "connected": item.counts.connected,
                    "atRisk": item.counts.at_risk,
                    "isolated": item.counts.isolated,
                    "noData": item.counts.no_data,
                },
                "unconfirmed": list(item.unconfirmed),
            }
            for item in result.passes
        ],
    }
