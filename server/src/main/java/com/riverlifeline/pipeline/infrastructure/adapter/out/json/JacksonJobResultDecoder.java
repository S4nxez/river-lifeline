package com.riverlifeline.pipeline.infrastructure.adapter.out.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.pipeline.application.exception.InvalidResultException;
import com.riverlifeline.pipeline.application.port.out.JobResultDecoder;
import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.pass.PassId;
import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import com.riverlifeline.pipeline.domain.model.raster.XY;
import com.riverlifeline.pipeline.domain.model.result.ChangeResult;
import com.riverlifeline.pipeline.domain.model.result.Classification;
import com.riverlifeline.pipeline.domain.model.result.CommunityClass;
import com.riverlifeline.pipeline.domain.model.result.CommunityRoute;
import com.riverlifeline.pipeline.domain.model.result.DroughtForecast;
import com.riverlifeline.pipeline.domain.model.result.JobResult;
import com.riverlifeline.pipeline.domain.model.result.PassAssessment;
import com.riverlifeline.pipeline.domain.model.result.RouteResult;
import com.riverlifeline.pipeline.domain.model.result.RouteStatus;
import com.riverlifeline.pipeline.domain.model.result.TrainedDroughtModel;
import com.riverlifeline.pipeline.domain.model.source.DroughtModel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Reads a posted {@code data} as the result schema of the job's type. A missing required field, a value of the wrong
 * kind or out of range, an unknown raster name: every one is an {@link InvalidResultException} (422).
 */
public class JacksonJobResultDecoder implements JobResultDecoder {
    private final ObjectMapper json;

    public JacksonJobResultDecoder(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public JobResult decode(JobType type, String dataJson) {
        JsonNode data;
        try {
            data = json.readTree(dataJson);
        } catch (JsonProcessingException e) {
            throw new InvalidResultException("data is not JSON: " + e.getOriginalMessage());
        }
        if (data == null || !data.isObject()) {
            throw new InvalidResultException("data must be an object");
        }
        try {
            return switch (type) {
                case ASSESS_PASS, REEVALUATE_PASS -> passAssessment(data);
                case DETECT_CHANGE -> change(data);
                case CLASSIFY_COMMUNITIES -> classification(data);
                case TRAIN_DROUGHT_MODEL -> trained(data);
                case FORECAST_DROUGHT -> forecast(data);
            };
        } catch (InvalidResultException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidResultException(e.getMessage());
        }
    }

    private PassAssessment passAssessment(JsonNode d) {
        JsonNode t = object(d, "threshold");
        JsonNode s1 = nullable(d, "sentinel1");
        JsonNode stats = d.get("stats");
        JsonNode hub = nullable(d, "hubLink");
        double coverage = number(d, "coverage");
        if (coverage < 0 || coverage > 1) {
            throw new InvalidResultException("coverage must be within [0, 1]");
        }
        return new PassAssessment(
                new PassId(text(d, "passId")),
                coverage,
                bool(d, "shown"),
                bool(d, "evaluated"),
                number(d, "waterKm2"),
                number(d, "narrowKm2"),
                nullableNumber(d, "vegetatedKm2"),
                new PassAssessment.Threshold(
                        text(t, "source"),
                        number(t, "strongDb"),
                        nullableNumber(t, "weakDb"),
                        optionalNumber(t, "measuredDb"),
                        t.hasNonNull("tiles") ? t.get("tiles").asInt() : null),
                nullableNumber(d, "riverLevelCm"),
                nullableBool(d, "lowWater"),
                hub == null ? null : route(hub),
                list(array(d, "communities"), c -> new CommunityRoute(text(c, "communityId"), route(c))),
                list(array(d, "unverifiedRoute"), JsonNode::asText),
                s1 == null
                        ? null
                        : new PassAssessment.Sentinel1Agreement(
                                list(array(s1, "dates"), n -> LocalDate.parse(n.asText())),
                                number(s1, "judgedKm2"),
                                nullableNumber(s1, "agreement"),
                                number(s1, "bothWaterKm2"),
                                number(s1, "nisarOnlyKm2"),
                                number(s1, "sentinel1OnlyKm2"),
                                number(s1, "mudKm2")),
                stats == null || !stats.isObject()
                        ? null
                        : new PassAssessment.Stats(optionalNumber(stats, "seconds"), optionalNumber(stats, "spacingM")),
                rasters(object(d, "rasters")));
    }

    private ChangeResult change(JsonNode d) {
        double lost = number(d, "lostKm2");
        double gained = number(d, "gainedKm2");
        if (lost < 0 || gained < 0) {
            throw new InvalidResultException("lostKm2 and gainedKm2 must not be negative");
        }
        return new ChangeResult(
                new PassId(text(d, "passId")),
                new PassId(text(d, "sincePassId")),
                lost,
                gained,
                rasters(object(d, "rasters")));
    }

    private Classification classification(JsonNode d) {
        Map<String, CommunityClass> classes = new LinkedHashMap<>();
        JsonNode node = object(d, "classes");
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            classes.put(e.getKey(), CommunityClass.fromWire(e.getValue().asText()));
        }
        List<Classification.PassCounts> passes = list(array(d, "passes"), p -> {
            JsonNode counts = object(p, "counts");
            return new Classification.PassCounts(
                    new PassId(text(p, "passId")),
                    integer(counts, "connected"),
                    integer(counts, "atRisk"),
                    integer(counts, "isolated"),
                    integer(counts, "noData"),
                    list(array(p, "unconfirmed"), JsonNode::asText));
        });
        return new Classification(new AreaId(text(d, "areaId")), classes, passes);
    }

    private TrainedDroughtModel trained(JsonNode d) {
        JsonNode m = object(d, "model");
        JsonNode parameters = object(m, "parameters");
        try {
            return new TrainedDroughtModel(
                    text(d, "region"),
                    integer(d, "observations"),
                    integer(d, "droughts"),
                    new DroughtModel(
                            text(m, "kind"),
                            integer(m, "version"),
                            integer(m, "lookbackDays"),
                            json.writeValueAsString(parameters)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private DroughtForecast forecast(JsonNode d) {
        return new DroughtForecast(
                text(d, "region"),
                integer(d, "horizonDays"),
                LocalDate.parse(text(d, "asOf")),
                number(d, "probability"),
                text(d, "riskLevel"));
    }

    private static RouteResult route(JsonNode node) {
        return new RouteResult(
                RouteStatus.fromWire(text(node, "status")),
                nullableInteger(node, "bottleneckM"),
                nullableInteger(node, "distanceToWaterM"));
    }

    private static Map<ResultRasterName, RasterMeta> rasters(JsonNode node) {
        Map<ResultRasterName, RasterMeta> rasters = new EnumMap<>(ResultRasterName.class);
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            ResultRasterName name;
            try {
                name = ResultRasterName.fromWire(e.getKey());
            } catch (IllegalArgumentException ex) {
                throw new InvalidResultException("unknown raster " + e.getKey());
            }
            rasters.put(name, meta(e.getValue()));
        }
        return rasters;
    }

    static RasterMeta meta(JsonNode m) {
        if (m == null || !m.isObject()) {
            throw new InvalidResultException("a raster layout must be an object");
        }
        JsonNode nodata = m.get("nodata");
        NoData nd;
        if (nodata == null || nodata.isNull()) {
            nd = NoData.NONE;
        } else if (nodata.isTextual() && "NaN".equals(nodata.asText())) {
            nd = NoData.NAN;
        } else if (nodata.isNumber()) {
            nd = NoData.value(nodata.asDouble());
        } else {
            throw new InvalidResultException("nodata must be a number, \"NaN\" or null");
        }
        JsonNode origin = object(m, "origin");
        JsonNode step = object(m, "step");
        return new RasterMeta(
                integer(m, "width"),
                integer(m, "height"),
                DataType.fromWire(text(m, "dtype")),
                nd,
                RasterUnit.fromWire(text(m, "unit")),
                text(m, "crs"),
                new XY(number(origin, "x"), number(origin, "y")),
                new XY(number(step, "x"), number(step, "y")));
    }

    private static JsonNode field(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null) {
            throw new InvalidResultException(name + " is missing");
        }
        return value;
    }

    private static JsonNode nullable(JsonNode node, String name) {
        JsonNode value = field(node, name);
        return value.isNull() ? null : value;
    }

    private static JsonNode object(JsonNode node, String name) {
        JsonNode value = field(node, name);
        if (!value.isObject()) {
            throw new InvalidResultException(name + " must be an object");
        }
        return value;
    }

    private static JsonNode array(JsonNode node, String name) {
        JsonNode value = field(node, name);
        if (!value.isArray()) {
            throw new InvalidResultException(name + " must be an array");
        }
        return value;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = field(node, name);
        if (!value.isTextual()) {
            throw new InvalidResultException(name + " must be a string");
        }
        return value.asText();
    }

    private static double number(JsonNode node, String name) {
        JsonNode value = field(node, name);
        if (!value.isNumber()) {
            throw new InvalidResultException(name + " must be a number");
        }
        return value.asDouble();
    }

    private static Double nullableNumber(JsonNode node, String name) {
        JsonNode value = field(node, name);
        return value.isNull() ? null : number(node, name);
    }

    private static Double optionalNumber(JsonNode node, String name) {
        return node.hasNonNull(name) ? number(node, name) : null;
    }

    private static int integer(JsonNode node, String name) {
        JsonNode value = field(node, name);
        if (!value.isIntegralNumber() && !(value.isNumber() && value.asDouble() == Math.rint(value.asDouble()))) {
            throw new InvalidResultException(name + " must be an integer");
        }
        return value.asInt();
    }

    private static Integer nullableInteger(JsonNode node, String name) {
        JsonNode value = field(node, name);
        return value.isNull() ? null : integer(node, name);
    }

    private static boolean bool(JsonNode node, String name) {
        JsonNode value = field(node, name);
        if (!value.isBoolean()) {
            throw new InvalidResultException(name + " must be a boolean");
        }
        return value.asBoolean();
    }

    private static Boolean nullableBool(JsonNode node, String name) {
        JsonNode value = field(node, name);
        return value.isNull() ? null : bool(node, name);
    }

    private static <T> List<T> list(JsonNode array, Function<JsonNode, T> item) {
        List<T> out = new ArrayList<>();
        array.forEach(n -> out.add(item.apply(n)));
        return out;
    }
}
