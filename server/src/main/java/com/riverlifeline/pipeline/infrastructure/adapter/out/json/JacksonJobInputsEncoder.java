package com.riverlifeline.pipeline.infrastructure.adapter.out.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riverlifeline.pipeline.application.port.out.JobInputsEncoder;
import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.area.AreaGrid;
import com.riverlifeline.pipeline.domain.model.area.Community;
import com.riverlifeline.pipeline.domain.model.area.Method;
import com.riverlifeline.pipeline.domain.model.area.Target;
import com.riverlifeline.pipeline.domain.model.job.JobInputs;
import com.riverlifeline.pipeline.domain.model.job.JobInputs.Gauge;
import com.riverlifeline.pipeline.domain.model.pass.PassInfo;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.raster.XY;
import com.riverlifeline.pipeline.domain.model.result.CommunityRoute;
import com.riverlifeline.pipeline.domain.model.result.RouteResult;
import com.riverlifeline.pipeline.domain.model.source.WeatherDay;
import com.riverlifeline.shared.domain.GeoPoint;
import java.util.List;

/** Writes {@code Job.inputs} exactly as api/worker-api.yaml describes each input schema. */
public class JacksonJobInputsEncoder implements JobInputsEncoder {
    private final ObjectMapper json;

    public JacksonJobInputsEncoder(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String encode(JobInputs inputs) {
        ObjectNode node = json.createObjectNode();
        switch (inputs) {
            case JobInputs.AssessPass a -> {
                node.set("area", area(a.area()));
                node.set("pass", pass(a.pass()));
                ArrayNode granules = node.putArray("granules");
                for (JobInputs.GranuleWindow g : a.granules()) {
                    ObjectNode granule = granules.addObject().put("id", g.id()).put("frame", g.frame());
                    ObjectNode bands = granule.putObject("bands");
                    bands.set("HH", ref(g.hh()));
                    if (g.hv() != null) {
                        bands.set("HV", ref(g.hv()));
                    }
                }
                node.set("gauge", gauge(a.gauge()));
                node.set("occurrence", a.occurrence() == null ? null : ref(a.occurrence()));
                ArrayNode candidates = node.putArray("sentinel1Candidates");
                for (JobInputs.Sentinel1Date date : a.sentinel1Candidates()) {
                    ObjectNode d =
                            candidates.addObject().put("date", date.date().toString());
                    ArrayNode tiles = d.putArray("tiles");
                    date.tiles().forEach(t -> tiles.add(ref(t)));
                }
            }
            case JobInputs.ReevaluatePass r -> {
                node.set("area", area(r.area()));
                node.set("pass", pass(r.pass()));
                node.set("water", ref(r.water()));
                if (r.sentinel1() == null) {
                    node.putNull("sentinel1");
                } else {
                    ObjectNode s1 = node.putObject("sentinel1");
                    ArrayNode dates = s1.putArray("dates");
                    r.sentinel1().dates().forEach(d -> dates.add(d.toString()));
                    s1.set("classes", ref(r.sentinel1().classes()));
                }
                node.set("gauge", gauge(r.gauge()));
                node.set("occurrence", r.occurrence() == null ? null : ref(r.occurrence()));
            }
            case JobInputs.DetectChange d -> {
                node.set("grid", grid(d.grid()));
                node.put("passId", d.passId().value());
                node.put("sincePassId", d.sincePassId().value());
                node.set("previous", ref(d.previous()));
                node.set("current", ref(d.current()));
            }
            case JobInputs.ClassifyCommunities c -> {
                node.put("areaId", c.areaId().value());
                node.put("accessRadiusM", c.accessRadiusM());
                ArrayNode ids = node.putArray("communityIds");
                c.communityIds().forEach(ids::add);
                ArrayNode passes = node.putArray("passes");
                for (JobInputs.PassRoutes p : c.passes()) {
                    ObjectNode pass = passes.addObject()
                            .put("passId", p.passId().value())
                            .put("date", p.date().toString())
                            .put("timeUtc", p.timeUtc());
                    ArrayNode communities = pass.putArray("communities");
                    p.communities().forEach(route -> communities.add(communityRoute(route)));
                }
            }
            case JobInputs.TrainDroughtModel t -> {
                node.put("region", t.region());
                node.set("observations", weather(t.observations()));
                ArrayNode droughts = node.putArray("droughts");
                t.droughts()
                        .forEach(d -> droughts.addObject()
                                .put("start", d.start().toString())
                                .put("end", d.end().toString())
                                .put("severity", d.severity()));
            }
            case JobInputs.ForecastDrought f -> {
                node.put("region", f.region());
                node.put("horizonDays", f.horizonDays());
                node.put("asOf", f.asOf().toString());
                ObjectNode model = node.putObject("model")
                        .put("kind", f.model().kind())
                        .put("version", f.model().version())
                        .put("lookbackDays", f.model().lookbackDays());
                model.set("parameters", parse(f.model().parametersJson()));
                node.set("recent", weather(f.recent()));
            }
        }
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    ObjectNode area(Area area) {
        ObjectNode node = json.createObjectNode().put("id", area.id().value()).put("name", area.name());
        node.set("grid", grid(area.grid()));
        node.set("hub", target(area.hub()));
        node.set("river", target(area.river()));
        ArrayNode communities = node.putArray("communities");
        for (Community c : area.communities()) {
            ObjectNode community = communities
                    .addObject()
                    .put("id", c.id())
                    .put("name", c.name())
                    .put("place", c.place())
                    .put("lon", c.position().lon())
                    .put("lat", c.position().lat());
            community.set("access", lonLat(c.access()));
        }
        node.set("method", method(area.method()));
        return node;
    }

    ObjectNode grid(AreaGrid grid) {
        ObjectNode node = json.createObjectNode()
                .put("crs", AreaGrid.CRS)
                .put("width", grid.width())
                .put("height", grid.height());
        node.set("origin", xy(grid.origin()));
        node.set("step", xy(grid.step()));
        node.put("pixelM", grid.pixelM());
        node.putArray("bbox")
                .add(grid.bbox().lon0())
                .add(grid.bbox().lat0())
                .add(grid.bbox().lon1())
                .add(grid.bbox().lat1());
        if (grid.circle() == null) {
            node.putNull("circle");
        } else {
            node.putObject("circle")
                    .put("lon", grid.circle().lon())
                    .put("lat", grid.circle().lat())
                    .put("radiusKm", grid.circle().radiusKm());
        }
        return node;
    }

    private ObjectNode target(Target target) {
        ObjectNode node = json.createObjectNode()
                .put("name", target.name())
                .put("lon", target.position().lon())
                .put("lat", target.position().lat())
                .put("radiusM", target.radiusM());
        node.set("access", lonLat(target.access()));
        return node;
    }

    private JsonNode lonLat(GeoPoint point) {
        return point == null
                ? json.nullNode()
                : json.createObjectNode().put("lon", point.lon()).put("lat", point.lat());
    }

    private ObjectNode method(Method m) {
        ObjectNode node = json.createObjectNode()
                .put("accessRadiusM", m.accessRadiusM())
                .put("minCoverage", m.minCoverage())
                .put("minCoverageShown", m.minCoverageShown())
                .put("validFloorDb", m.validFloorDb())
                .put("edgeMarginM", m.edgeMarginM())
                .put("waterDb", m.waterDb())
                .put("waterDbWeak", m.waterDbWeak());
        if (m.waterDbAdaptive() == null) {
            node.putNull("waterDbAdaptive");
        } else {
            ObjectNode adaptive = node.putObject("waterDbAdaptive");
            adaptive.putArray("rangeDb")
                    .add(m.waterDbAdaptive().rangeDbLow())
                    .add(m.waterDbAdaptive().rangeDbHigh());
            adaptive.put("tileM", m.waterDbAdaptive().tileM())
                    .put("minTiles", m.waterDbAdaptive().minTiles());
        }
        node.putObject("speckle")
                .put("filter", m.speckle().filter())
                .put("looks", m.speckle().looks())
                .put("windowM", m.speckle().windowM());
        ArrayNode widths = node.putArray("widthsM");
        m.widthsM().forEach(widths::add);
        node.put("safeWidthM", m.safeWidthM());
        if (m.vegetatedWater() == null) {
            node.putNull("vegetatedWater");
        } else {
            node.putObject("vegetatedWater")
                    .put("hhMinDb", m.vegetatedWater().hhMinDb())
                    .put("ratioMinDb", m.vegetatedWater().ratioMinDb())
                    .put("nearWaterM", m.vegetatedWater().nearWaterM());
        }
        if (m.lowWater() == null) {
            node.putNull("lowWater");
        } else {
            ObjectNode low =
                    node.putObject("lowWater").put("gaugeMaxCm", m.lowWater().gaugeMaxCm());
            low.putArray("seasonalPct")
                    .add(m.lowWater().seasonalPctLow())
                    .add(m.lowWater().seasonalPctHigh());
            low.put("patchRadiusM", m.lowWater().patchRadiusM())
                    .put("vetoRadiusM", m.lowWater().vetoRadiusM());
        }
        if (m.sentinel1() == null) {
            node.putNull("sentinel1");
        } else {
            node.putObject("sentinel1")
                    .put("maxDays", m.sentinel1().maxDays())
                    .put("levelToleranceCm", m.sentinel1().levelToleranceCm())
                    .put("waterDb", m.sentinel1().waterDb())
                    .put("weakDb", m.sentinel1().weakDb())
                    .put("landDb", m.sentinel1().landDb());
        }
        return node;
    }

    private ObjectNode pass(PassInfo pass) {
        return json.createObjectNode()
                .put("id", pass.id().value())
                .put("date", pass.date().toString())
                .put("timeUtc", pass.timeUtc())
                .put("track", pass.track())
                .put("direction", pass.direction().wire());
    }

    private JsonNode gauge(Gauge gauge) {
        if (gauge == null) {
            return json.nullNode();
        }
        ObjectNode node = json.createObjectNode().put("station", gauge.station());
        ArrayNode days = node.putArray("days");
        gauge.days()
                .forEach(d -> days.addObject().put("day", d.day().toString()).put("levelCm", d.levelCm()));
        return node;
    }

    ObjectNode ref(RasterRef ref) {
        ObjectNode node = meta(ref.meta());
        node.put("href", ref.href());
        return node;
    }

    ObjectNode meta(RasterMeta meta) {
        ObjectNode node = json.createObjectNode()
                .put("width", meta.width())
                .put("height", meta.height())
                .put("dtype", meta.dtype().wire());
        switch (meta.nodata()) {
            case NoData.Value v -> {
                if (v.value() == Math.rint(v.value()) && Math.abs(v.value()) < 1e15) {
                    node.put("nodata", (long) v.value());
                } else {
                    node.put("nodata", v.value());
                }
            }
            case NoData.NotANumber n -> node.put("nodata", "NaN");
            case NoData.None n -> node.putNull("nodata");
        }
        node.put("unit", meta.unit().wire()).put("crs", meta.crs());
        node.set("origin", xy(meta.origin()));
        node.set("step", xy(meta.step()));
        return node;
    }

    private ObjectNode xy(XY xy) {
        return json.createObjectNode().put("x", xy.x()).put("y", xy.y());
    }

    private ObjectNode communityRoute(CommunityRoute route) {
        ObjectNode node = json.createObjectNode().put("communityId", route.communityId());
        RouteResult r = route.route();
        node.put("status", r.status().wire());
        node.put("bottleneckM", r.bottleneckM());
        node.put("distanceToWaterM", r.distanceToWaterM());
        return node;
    }

    private ArrayNode weather(List<WeatherDay> days) {
        ArrayNode node = json.createArrayNode();
        days.forEach(d -> node.addObject()
                .put("day", d.day().toString())
                .put("precipitationMm", d.precipitationMm())
                .put("temperatureC", d.temperatureC())
                .put("soilMoisture", d.soilMoisture()));
        return node;
    }

    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored model parameters are not JSON", e);
        }
    }
}
