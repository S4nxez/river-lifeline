package com.riverlifeline.pipeline.infrastructure.adapter.out.areafiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.pipeline.application.port.out.AreaCatalog;
import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.area.AreaGrid;
import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.area.Bbox;
import com.riverlifeline.pipeline.domain.model.area.Circle;
import com.riverlifeline.pipeline.domain.model.area.Community;
import com.riverlifeline.pipeline.domain.model.area.GaugeStation;
import com.riverlifeline.pipeline.domain.model.area.Method;
import com.riverlifeline.pipeline.domain.model.area.Target;
import com.riverlifeline.shared.domain.GeoPoint;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * The study areas from {@code areas/<id>.json} on the classpath ({@code server/src/main/resources/areas/}), with their
 * communities GeoJSON and occurrence PNG next to them. Loaded once: the files are part of the release. A file that
 * cannot be read is logged and left out; the other areas still load.
 */
public class AreaFiles implements AreaCatalog {
    private static final Logger log = LoggerFactory.getLogger(AreaFiles.class);
    static final double DEFAULT_VALID_FLOOR_DB = -40.0;
    static final double DEFAULT_SPECKLE_M = 100.0;

    private final List<Area> areas;
    private final Map<AreaId, String> occurrenceFiles = new HashMap<>();
    private final Function<String, byte[]> files;

    /** Reads every {@code areas/*.json} of the classpath. */
    public static AreaFiles fromClasspath(ObjectMapper json) throws IOException {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] found = resolver.getResources("classpath*:areas/*.json");
        Arrays.sort(found, Comparator.comparing(Resource::getFilename));
        List<String> names = new ArrayList<>();
        for (Resource r : found) {
            names.add(r.getFilename());
        }
        return new AreaFiles(json, names, name -> {
            try (var in = resolver.getResource("classpath:areas/" + name).getInputStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** {@code files} reads a file of the areas folder by name. */
    public AreaFiles(ObjectMapper json, List<String> areaFileNames, Function<String, byte[]> files) {
        this.files = files;
        List<Area> loaded = new ArrayList<>();
        for (String name : areaFileNames) {
            try {
                loaded.add(load(json, name));
            } catch (RuntimeException | IOException e) {
                log.error("Area file {} not loaded: {}", name, e.getMessage());
            }
        }
        this.areas = List.copyOf(loaded);
        log.info("Areas: {}", areas.stream().map(a -> a.id().value()).toList());
    }

    @Override
    public List<Area> all() {
        return areas;
    }

    /** The bytes of the area's occurrence PNG, when the area names one. */
    public Optional<byte[]> occurrencePng(AreaId area) {
        String file = occurrenceFiles.get(area);
        return file == null ? Optional.empty() : Optional.of(files.apply(file));
    }

    private Area load(ObjectMapper json, String name) throws IOException {
        byte[] areaBytes = files.apply(name);
        JsonNode a = json.readTree(areaBytes);
        AreaId id = new AreaId(text(a, "id"));
        if (!name.equals(id.value() + ".json")) {
            throw new IllegalArgumentException("its id is " + id + ", not its file name");
        }
        Circle circle = a.hasNonNull("circle")
                ? new Circle(
                        num(a.get("circle"), "lon"), num(a.get("circle"), "lat"), num(a.get("circle"), "radius_km"))
                : null;
        Bbox bbox = null;
        if (circle == null) {
            JsonNode b = required(a, "bbox");
            bbox = new Bbox(
                    b.get(0).asDouble(),
                    b.get(1).asDouble(),
                    b.get(2).asDouble(),
                    b.get(3).asDouble());
        }
        AreaGrid grid = AreaGrid.of(bbox, circle, num(a, "step_deg"));
        byte[] communityBytes = new byte[0];
        List<Community> communities = List.of();
        if (a.hasNonNull("communities")) {
            communityBytes = files.apply(text(a, "communities"));
            communities = communities(json.readTree(communityBytes));
        }
        if (a.hasNonNull("occurrence")) {
            occurrenceFiles.put(id, text(a, "occurrence"));
        }
        GaugeStation gauge = a.hasNonNull("gauge")
                ? new GaugeStation(a.get("gauge").path("name").asText(""), text(a.get("gauge"), "ana_code"))
                : null;
        return new Area(
                id,
                text(a, "name"),
                grid,
                target(required(a, "hub")),
                target(required(a, "river")),
                communities,
                method(a),
                gauge,
                digest(areaBytes, communityBytes));
    }

    private static Method method(JsonNode a) {
        JsonNode adaptive = a.get("water_db_adaptive");
        JsonNode vegetated = a.get("vegetated_water");
        JsonNode low = a.get("low_water");
        JsonNode s1 = a.get("sentinel1");
        List<Double> widths = new ArrayList<>();
        required(a, "widths_m").forEach(w -> widths.add(w.asDouble()));
        return new Method(
                num(a, "access_radius_m"),
                num(a, "min_coverage"),
                num(a, "min_coverage_shown"),
                a.path("valid_floor_db").asDouble(DEFAULT_VALID_FLOOR_DB),
                a.path("edge_margin_m").asDouble(0),
                num(a, "water_db"),
                optional(a, "water_db_weak"),
                adaptive == null || adaptive.isNull()
                        ? null
                        : new Method.AdaptiveThreshold(
                                required(adaptive, "range_db").get(0).asDouble(),
                                required(adaptive, "range_db").get(1).asDouble(),
                                num(adaptive, "tile_m"),
                                (int) num(adaptive, "min_tiles")),
                new Method.Speckle(
                        a.path("speckle_filter").asText("refined_lee"),
                        optional(a, "speckle_looks"),
                        a.hasNonNull("speckle_m") ? num(a, "speckle_m") : DEFAULT_SPECKLE_M),
                widths,
                num(a, "safe_width_m"),
                vegetated == null || vegetated.isNull()
                        ? null
                        : new Method.VegetatedWater(
                                num(vegetated, "hh_min_db"),
                                num(vegetated, "ratio_min_db"),
                                num(vegetated, "near_water_m")),
                low == null || low.isNull()
                        ? null
                        : new Method.LowWater(
                                num(low, "gauge_max_cm"),
                                required(low, "seasonal_pct").get(0).asDouble(),
                                required(low, "seasonal_pct").get(1).asDouble(),
                                num(low, "patch_radius_m"),
                                num(low, "veto_radius_m")),
                s1 == null || s1.isNull()
                        ? null
                        : new Method.Sentinel1Method(
                                (int) num(s1, "max_days"),
                                s1.path("level_tolerance_cm").asDouble(0),
                                num(s1, "water_db"),
                                optional(s1, "weak_db"),
                                num(s1, "land_db")));
    }

    private static Target target(JsonNode t) {
        return new Target(
                text(t, "name"),
                new GeoPoint(num(t, "lon"), num(t, "lat")),
                num(t, "radius_m"),
                point(t.get("access")));
    }

    private static List<Community> communities(JsonNode geojson) {
        List<Community> out = new ArrayList<>();
        for (JsonNode f : required(geojson, "features")) {
            JsonNode p = required(f, "properties");
            JsonNode c = required(required(f, "geometry"), "coordinates");
            out.add(new Community(
                    text(p, "id"),
                    text(p, "name"),
                    p.path("place").asText("unknown"),
                    new GeoPoint(c.get(0).asDouble(), c.get(1).asDouble()),
                    point(p.get("access"))));
        }
        return out;
    }

    /** A point written as {@code [lon, lat]} or {@code {"lon", "lat"}}; null when absent. */
    private static GeoPoint point(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            return new GeoPoint(node.get(0).asDouble(), node.get(1).asDouble());
        }
        return new GeoPoint(num(node, "lon"), num(node, "lat"));
    }

    private static JsonNode required(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(name + " is missing");
        }
        return value;
    }

    private static String text(JsonNode node, String name) {
        return required(node, name).asText();
    }

    private static double num(JsonNode node, String name) {
        JsonNode value = required(node, name);
        if (!value.isNumber()) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        return value.asDouble();
    }

    private static Double optional(JsonNode node, String name) {
        return node.hasNonNull(name) ? num(node, name) : null;
    }

    static String digest(byte[]... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                sha.update(part);
                sha.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
