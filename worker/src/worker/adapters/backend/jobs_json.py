"""Reads the JSON of a leased job (api/worker-api.yaml, `Job`) into the domain's inputs objects.

Anything missing or malformed makes the job invalid: the same job would fail again, so it is not retried.
"""

import math
from collections.abc import Callable
from datetime import date
from typing import Any

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
from worker.domain.connectivity import ClassifyCommunitiesInputs
from worker.domain.drought import (
    DroughtEvent,
    DroughtModel,
    ForecastDroughtInputs,
    TrainDroughtModelInputs,
    WeatherObservation,
)
from worker.domain.errors import InvalidJob
from worker.domain.jobs import Job, JobType
from worker.domain.passes import (
    AssessPassInputs,
    CommunityRoute,
    DetectChangeInputs,
    EvaluatedPass,
    Gauge,
    GaugeDay,
    Granule,
    PassInfo,
    ReevaluatePassInputs,
    RouteResult,
    RouteStatus,
    Sentinel1Date,
    StoredSentinel1,
)
from worker.domain.rasters import XY, DType, RasterMeta, RasterRef, Unit

Json = dict[str, Any]


def parse_job(body: Json) -> Job:
    """The job of a `GET /jobs/next` answer whose type and contract version the caller already accepted."""
    job_type = JobType(body["type"])
    try:
        inputs = _INPUTS[job_type](body["inputs"])
        return Job(
            id=str(body["id"]),
            type=job_type,
            inputs=inputs,
            lease_seconds=int(body["leaseSeconds"]),
            contract_version=int(body["contractVersion"]),
        )
    except InvalidJob:
        raise
    except (KeyError, TypeError, ValueError, AttributeError) as error:
        raise InvalidJob(f"malformed {job_type} job: {type(error).__name__}: {error}") from error


# --- Rasters ----------------------------------------------------------------------------------------------------


def _xy(item: Json) -> XY:
    return XY(float(item["x"]), float(item["y"]))


def raster_meta(item: Json) -> RasterMeta:
    nodata = item["nodata"]
    if isinstance(nodata, int | float) and not isinstance(nodata, bool):
        nodata = float(nodata)
    elif nodata is not None and nodata != "NaN":
        raise ValueError(f"nodata must be a number, 'NaN' or null, got {nodata!r}")
    return RasterMeta(
        width=int(item["width"]),
        height=int(item["height"]),
        dtype=DType(item["dtype"]),
        nodata=nodata,
        unit=Unit(item["unit"]),
        crs=str(item["crs"]),
        origin=_xy(item["origin"]),
        step=_xy(item["step"]),
    )


def _raster_ref(item: Json) -> RasterRef:
    return RasterRef(href=str(item["href"]), meta=raster_meta(item))


def _optional(item: Any, read: Callable[[Any], Any]) -> Any:
    return None if item is None else read(item)


def _day(value: str) -> date:
    return date.fromisoformat(value)


def _number(value: Any) -> float:
    number = float(value)
    if not math.isfinite(number):
        raise ValueError(f"expected a finite number, got {value!r}")
    return number


# --- Area -------------------------------------------------------------------------------------------------------


def _grid(item: Json) -> AreaGrid:
    bbox = tuple(_number(value) for value in item["bbox"])
    if len(bbox) != 4:
        raise ValueError(f"bbox needs 4 numbers, got {len(bbox)}")
    circle = item.get("circle")
    return AreaGrid(
        crs=str(item["crs"]),
        width=int(item["width"]),
        height=int(item["height"]),
        origin=_xy(item["origin"]),
        step=_xy(item["step"]),
        pixel_m=_number(item["pixelM"]),
        bbox=bbox,
        circle=_optional(circle, lambda c: Circle(_number(c["lon"]), _number(c["lat"]), _number(c["radiusKm"]))),
    )


def _lonlat(item: Json) -> LonLat:
    return LonLat(_number(item["lon"]), _number(item["lat"]))


def _target(item: Json) -> Target:
    return Target(
        name=str(item["name"]),
        lon=_number(item["lon"]),
        lat=_number(item["lat"]),
        radius_m=_number(item["radiusM"]),
        access=_optional(item.get("access"), _lonlat),
    )


def _community(item: Json) -> Community:
    return Community(
        id=str(item["id"]),
        name=str(item["name"]),
        place=str(item["place"]),
        lon=_number(item["lon"]),
        lat=_number(item["lat"]),
        access=_optional(item["access"], _lonlat),
    )


def _pair(values: list[Any]) -> tuple[float, float]:
    first, second = (_number(value) for value in values)
    return first, second


def _method(item: Json) -> Method:
    speckle = item["speckle"]
    return Method(
        access_radius_m=_number(item["accessRadiusM"]),
        min_coverage=_number(item["minCoverage"]),
        min_coverage_shown=_number(item["minCoverageShown"]),
        valid_floor_db=_number(item["validFloorDb"]),
        edge_margin_m=_number(item["edgeMarginM"]),
        water_db=_number(item["waterDb"]),
        water_db_weak=_optional(item["waterDbWeak"], _number),
        water_db_adaptive=_optional(
            item["waterDbAdaptive"],
            lambda a: AdaptiveThreshold(_pair(a["rangeDb"]), _number(a["tileM"]), int(a["minTiles"])),
        ),
        speckle=Speckle(
            filter=str(speckle["filter"]),
            looks=_optional(speckle["looks"], _number),
            window_m=_optional(speckle["windowM"], _number),
        ),
        widths_m=tuple(_number(width) for width in item["widthsM"]),
        safe_width_m=_number(item["safeWidthM"]),
        vegetated_water=_optional(
            item["vegetatedWater"],
            lambda v: VegetatedWater(_number(v["hhMinDb"]), _number(v["ratioMinDb"]), _number(v["nearWaterM"])),
        ),
        low_water=_optional(
            item["lowWater"],
            lambda w: LowWater(
                _number(w["gaugeMaxCm"]),
                _pair(w["seasonalPct"]),
                _number(w["patchRadiusM"]),
                _number(w["vetoRadiusM"]),
            ),
        ),
        sentinel1=_optional(
            item["sentinel1"],
            lambda s: Sentinel1Method(
                max_days=int(s["maxDays"]),
                level_tolerance_cm=_number(s["levelToleranceCm"]),
                water_db=_number(s["waterDb"]),
                weak_db=_optional(s["weakDb"], _number),
                land_db=_number(s["landDb"]),
            ),
        ),
    )


def _area(item: Json) -> AreaSpec:
    return AreaSpec(
        id=str(item["id"]),
        name=str(item["name"]),
        grid=_grid(item["grid"]),
        hub=_target(item["hub"]),
        river=_target(item["river"]),
        communities=tuple(_community(c) for c in item["communities"]),
        method=_method(item["method"]),
    )


# --- Passes -----------------------------------------------------------------------------------------------------


def _pass_info(item: Json) -> PassInfo:
    return PassInfo(
        id=str(item["id"]),
        date=_day(item["date"]),
        time_utc=str(item["timeUtc"]),
        track=int(item["track"]),
        direction=str(item["direction"]),
    )


def _granule(item: Json) -> Granule:
    bands = item["bands"]
    return Granule(
        id=str(item["id"]),
        frame=int(item["frame"]),
        hh=_raster_ref(bands["HH"]),
        hv=_optional(bands.get("HV"), _raster_ref),
    )


def _gauge(item: Json) -> Gauge:
    return Gauge(
        station=str(item["station"]),
        days=tuple(GaugeDay(_day(d["day"]), _number(d["levelCm"])) for d in item["days"]),
    )


def _sentinel1_date(item: Json) -> Sentinel1Date:
    tiles = tuple(_raster_ref(tile) for tile in item["tiles"])
    if not tiles:
        raise ValueError(f"Sentinel-1 date {item['date']} has no tiles")
    return Sentinel1Date(_day(item["date"]), tiles)


def _assess_pass(item: Json) -> AssessPassInputs:
    granules = tuple(_granule(g) for g in item["granules"])
    if not granules:
        raise InvalidJob("an assess_pass job needs at least one granule")
    return AssessPassInputs(
        area=_area(item["area"]),
        pass_info=_pass_info(item["pass"]),
        granules=granules,
        gauge=_optional(item["gauge"], _gauge),
        occurrence=_optional(item["occurrence"], _raster_ref),
        sentinel1_candidates=tuple(_sentinel1_date(d) for d in item["sentinel1Candidates"]),
    )


def _reevaluate_pass(item: Json) -> ReevaluatePassInputs:
    return ReevaluatePassInputs(
        area=_area(item["area"]),
        pass_info=_pass_info(item["pass"]),
        water=_raster_ref(item["water"]),
        sentinel1=_optional(
            item["sentinel1"],
            lambda s: StoredSentinel1(tuple(_day(d) for d in s["dates"]), _raster_ref(s["classes"])),
        ),
        gauge=_optional(item["gauge"], _gauge),
        occurrence=_optional(item["occurrence"], _raster_ref),
    )


def _detect_change(item: Json) -> DetectChangeInputs:
    return DetectChangeInputs(
        grid=_grid(item["grid"]),
        pass_id=str(item["passId"]),
        since_pass_id=str(item["sincePassId"]),
        previous=_raster_ref(item["previous"]),
        current=_raster_ref(item["current"]),
    )


def _optional_int(value: Any) -> int | None:
    return None if value is None else int(value)


def _community_route(item: Json) -> CommunityRoute:
    return CommunityRoute(
        community_id=str(item["communityId"]),
        route=RouteResult(
            status=RouteStatus(item["status"]),
            bottleneck_m=_optional_int(item["bottleneckM"]),
            distance_to_water_m=_optional_int(item["distanceToWaterM"]),
        ),
    )


def _classify_communities(item: Json) -> ClassifyCommunitiesInputs:
    return ClassifyCommunitiesInputs(
        area_id=str(item["areaId"]),
        access_radius_m=_number(item["accessRadiusM"]),
        community_ids=tuple(str(cid) for cid in item["communityIds"]),
        passes=tuple(
            EvaluatedPass(
                pass_id=str(p["passId"]),
                date=_day(p["date"]),
                time_utc=str(p["timeUtc"]),
                communities=tuple(_community_route(c) for c in p["communities"]),
            )
            for p in item["passes"]
        ),
    )


# --- Droughts ---------------------------------------------------------------------------------------------------


def _weather(item: Json) -> WeatherObservation:
    return WeatherObservation(
        day=_day(item["day"]),
        precipitation_mm=_number(item["precipitationMm"]),
        temperature_c=_number(item["temperatureC"]),
        soil_moisture=_optional(item["soilMoisture"], _number),
    )


def _model(item: Json) -> DroughtModel:
    parameters = item["parameters"]
    if not isinstance(parameters, dict):
        raise ValueError("model parameters must be an object")
    return DroughtModel(str(item["kind"]), int(item["version"]), int(item["lookbackDays"]), parameters)


def _train_drought_model(item: Json) -> TrainDroughtModelInputs:
    return TrainDroughtModelInputs(
        region=str(item["region"]),
        observations=tuple(_weather(o) for o in item["observations"]),
        droughts=tuple(DroughtEvent(_day(d["start"]), _day(d["end"]), int(d["severity"])) for d in item["droughts"]),
    )


def _forecast_drought(item: Json) -> ForecastDroughtInputs:
    horizon = int(item["horizonDays"])
    if horizon < 1:
        raise ValueError(f"horizonDays must be at least 1, got {horizon}")
    return ForecastDroughtInputs(
        region=str(item["region"]),
        horizon_days=horizon,
        as_of=_day(item["asOf"]),
        model=_model(item["model"]),
        recent=tuple(_weather(o) for o in item["recent"]),
    )


_INPUTS: dict[JobType, Callable[[Json], Any]] = {
    JobType.ASSESS_PASS: _assess_pass,
    JobType.REEVALUATE_PASS: _reevaluate_pass,
    JobType.DETECT_CHANGE: _detect_change,
    JobType.CLASSIFY_COMMUNITIES: _classify_communities,
    JobType.TRAIN_DROUGHT_MODEL: _train_drought_model,
    JobType.FORECAST_DROUGHT: _forecast_drought,
}
