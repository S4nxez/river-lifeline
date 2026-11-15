package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.ChangeRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.CountsRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.PassRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.RasterBox;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** A small circular area, its communities and pass rows, as the pipeline would store them. */
final class TestArea {
    static final ObjectMapper JSON = new ObjectMapper();

    static final String AREA = """
            {"id": "tefe", "name": "Lake Tefé", "country": "Brazil (Amazonas)",
             "circle": {"lon": -64.75, "lat": -3.40, "radius_km": 55}, "step_deg": 0.00018,
             "hub": {"name": "Tefé", "lon": -64.7102, "lat": -3.3485, "radius_m": 2000,
                     "access": {"lon": -64.71, "lat": -3.35}},
             "river": {"name": "Solimões", "lon": -64.625, "lat": -3.287, "radius_m": 1500},
             "gauge": {"name": "Tefé-Missões", "ana_code": "12900001"},
             "access_radius_m": 1000, "min_coverage": 0.8, "min_coverage_shown": 0.05,
             "valid_floor_db": -40.0, "edge_margin_m": 1000, "water_db": -16.0, "water_db_weak": -14.0,
             "widths_m": [40, 60, 100, 200, 400], "safe_width_m": 60,
             "low_water": {"gauge_max_cm": 1200, "seasonal_pct": [30, 90], "patch_radius_m": 60, "veto_radius_m": 20},
             "communities": "tefe_communities.geojson"}
            """;

    static final String COMMUNITIES = """
            {"type": "FeatureCollection", "features": [
              {"type": "Feature", "properties": {"id": "a", "name": "Alvarães", "place": "town",
                "access": [-64.80, -3.22]}, "geometry": {"type": "Point", "coordinates": [-64.81, -3.21]}},
              {"type": "Feature", "properties": {"id": "b", "name": "Boa Vista", "place": "village"},
                "geometry": {"type": "Point", "coordinates": [-64.70, -3.30]}},
              {"type": "Feature", "properties": {"id": "c", "name": "Caiambé", "place": "hamlet"},
                "geometry": {"type": "Point", "coordinates": [-64.60, -3.50]}},
              {"type": "Feature", "properties": {"id": "d", "name": "Dry Hill", "place": "isolated_dwelling"},
                "geometry": {"type": "Point", "coordinates": [-64.90, -3.60]}}]}
            """;

    static final RasterBox WATER = new RasterBox("w1", 4, 3, "uint8", -65.0, -3.0, 0.01, -0.01);

    private TestArea() {}

    static AreaFileReader reader() {
        Map<String, String> files = Map.of("tefe.json", AREA, "tefe_communities.geojson", COMMUNITIES);
        return new AreaFileReader(
                JSON, List.of("tefe.json"), name -> files.get(name).getBytes(StandardCharsets.UTF_8));
    }

    static AreaFile area() {
        return reader().find("tefe").orElseThrow();
    }

    /** An evaluated pass on `day` at 10:02, with the water raster and the given counts (null: not classified). */
    static PassRow pass(String id, String day, boolean evaluated, CountsRow counts) {
        return new PassRow(
                id,
                LocalDate.parse(day),
                "10:02",
                161,
                "ascending",
                "[{\"name\": \"NISAR_L2_GCOV_" + id + "\", \"url\": \"https://x\", \"collection\": \"c\"}]",
                Instant.parse(day + "T12:00:00Z"),
                "0123456789abcdef0123",
                evaluated ? 0.95 : 0.4,
                evaluated,
                12.5,
                null,
                812.0,
                false,
                20.0,
                WATER,
                null,
                counts,
                List.of(),
                null);
    }

    static PassRow withChange(PassRow row, ChangeRow change) {
        return new PassRow(
                row.passId(),
                row.date(),
                row.timeUtc(),
                row.track(),
                row.direction(),
                row.granulesJson(),
                row.assessedAt(),
                row.methodDigest(),
                row.coverage(),
                row.evaluated(),
                row.waterKm2(),
                3.2,
                row.riverLevelCm(),
                true,
                row.spacingM(),
                row.water(),
                new RasterBox("r1", 2, 1, "float32", -64.995, -3.005, 0.02, -0.02),
                row.counts(),
                List.of(LocalDate.parse("2026-10-25")),
                change);
    }
}
