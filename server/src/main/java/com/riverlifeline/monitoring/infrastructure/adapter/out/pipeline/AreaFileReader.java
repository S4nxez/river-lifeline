package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.shared.domain.BoundingBox;
import com.riverlifeline.shared.domain.GeoPoint;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/** The area files of the classpath ({@code areas/<id>.json}, with the communities file each one names). */
public final class AreaFileReader {
    private static final Logger log = LoggerFactory.getLogger(AreaFileReader.class);

    private final Map<String, AreaFile> byId;

    /** {@code files} reads a file of the areas folder by name. */
    public AreaFileReader(ObjectMapper json, List<String> areaFileNames, Function<String, byte[]> files) {
        Map<String, AreaFile> loaded = new LinkedHashMap<>();
        for (String name : areaFileNames.stream().sorted().toList()) {
            try {
                AreaFile area = read(json, files, name);
                loaded.put(area.id(), area);
            } catch (IOException | RuntimeException e) {
                log.error("Area file {} not loaded: {}", name, e.getMessage());
            }
        }
        this.byId = Map.copyOf(loaded);
    }

    public static AreaFileReader fromClasspath(ObjectMapper json) throws IOException {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] found = resolver.getResources("classpath*:areas/*.json");
        Arrays.sort(found, Comparator.comparing(Resource::getFilename));
        List<String> names = Arrays.stream(found).map(Resource::getFilename).toList();
        return new AreaFileReader(json, names, name -> {
            try (var in = resolver.getResource("classpath:areas/" + name).getInputStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** Every area id, sorted. */
    public List<String> ids() {
        return byId.keySet().stream().sorted().toList();
    }

    public Optional<AreaFile> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    private static AreaFile read(ObjectMapper json, Function<String, byte[]> files, String name) throws IOException {
        JsonNode a = json.readTree(files.apply(name));
        AreaFile.Circle circle = a.hasNonNull("circle")
                ? new AreaFile.Circle(
                        num(a.get("circle"), "lon"), num(a.get("circle"), "lat"), num(a.get("circle"), "radius_km"))
                : null;
        BoundingBox bbox;
        if (circle != null) {
            bbox = AreaFile.around(circle);
        } else {
            JsonNode b = required(a, "bbox");
            bbox = new BoundingBox(
                    b.get(0).asDouble(),
                    b.get(1).asDouble(),
                    b.get(2).asDouble(),
                    b.get(3).asDouble());
        }
        JsonNode gauge = a.get("gauge");
        List<AreaFile.CommunityPlace> communities = a.hasNonNull("communities")
                ? communities(json.readTree(files.apply(a.get("communities").asText())))
                : List.of();
        return new AreaFile(
                text(a, "id"),
                text(a, "name"),
                a.path("country").asText(null),
                circle,
                bbox,
                place(required(a, "hub")),
                gauge == null || gauge.isNull()
                        ? null
                        : new AreaFile.Gauge(
                                text(gauge, "ana_code"), gauge.path("name").asText(null)),
                method(a),
                communities);
    }

    private static AreaFile.Method method(JsonNode a) {
        List<Integer> widths = new ArrayList<>();
        required(a, "widths_m").forEach(w -> widths.add(w.asInt()));
        JsonNode low = a.get("low_water");
        return new AreaFile.Method(
                widths,
                (int) num(a, "safe_width_m"),
                num(a, "access_radius_m"),
                num(a, "min_coverage"),
                num(a, "water_db"),
                a.hasNonNull("water_db_weak") ? a.get("water_db_weak").asDouble() : null,
                a.path("valid_floor_db").asDouble(-40.0),
                a.path("edge_margin_m").asDouble(0),
                low == null || low.isNull() ? null : num(low, "gauge_max_cm"));
    }

    private static List<AreaFile.CommunityPlace> communities(JsonNode geojson) {
        List<AreaFile.CommunityPlace> out = new ArrayList<>();
        for (JsonNode f : required(geojson, "features")) {
            JsonNode p = required(f, "properties");
            JsonNode c = required(required(f, "geometry"), "coordinates");
            out.add(new AreaFile.CommunityPlace(
                    text(p, "id"),
                    text(p, "name"),
                    p.path("place").asText("unknown"),
                    new GeoPoint(c.get(0).asDouble(), c.get(1).asDouble()),
                    point(p.get("access"))));
        }
        return out;
    }

    private static AreaFile.Place place(JsonNode t) {
        return new AreaFile.Place(text(t, "name"), new GeoPoint(num(t, "lon"), num(t, "lat")), point(t.get("access")));
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
}
