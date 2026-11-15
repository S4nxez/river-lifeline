package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.AssessmentRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.ChangeRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.CountsRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.GaugeRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.PassRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.RasterBox;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Reads the pipeline's tables (V5) with plain SQL: the monitoring context shares the database, not the code. */
public class JdbcPipelineReader implements PipelineReader {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcPipelineReader(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<PassRow> passes(String areaId) {
        MapSqlParameterSource area = new MapSqlParameterSource("area", areaId);
        Map<String, ChangeRow> changes = new HashMap<>();
        jdbc.query("""
                SELECT c.pass_id, c.since_pass_id, c.lost_km2, c.gained_km2, %s
                FROM pass_change c JOIN raster r ON r.id = c.change_raster_id
                WHERE c.area_id = :area ORDER BY c.created_at
                """.formatted(boxColumns("r")), area, rs -> {
            changes.put(
                    rs.getString("pass_id"),
                    new ChangeRow(
                            rs.getString("since_pass_id"),
                            rs.getDouble("lost_km2"),
                            rs.getDouble("gained_km2"),
                            box(rs, "r_")));
        });
        return jdbc.query("""
                SELECT p.*, %s, %s,
                    k.connected, k.at_risk, k.isolated, k.no_data, k.unconfirmed, s.dates AS s1_dates
                FROM radar_pass p
                LEFT JOIN raster w ON w.id = p.water_raster_id
                LEFT JOIN raster r ON r.id = p.radar_raster_id
                LEFT JOIN pass_counts k ON k.area_id = p.area_id AND k.pass_id = p.pass_id
                LEFT JOIN pass_sentinel1 s ON s.area_id = p.area_id AND s.pass_id = p.pass_id
                WHERE p.area_id = :area AND p.assessed_at IS NOT NULL AND p.shown = TRUE
                ORDER BY p.acquired_on, p.time_utc, p.pass_id
                """.formatted(boxColumns("w"), boxColumns("r")), area, (rs, i) -> pass(rs, changes));
    }

    private PassRow pass(ResultSet rs, Map<String, ChangeRow> changes) throws SQLException {
        String passId = rs.getString("pass_id");
        String unconfirmed = rs.getString("unconfirmed");
        CountsRow counts = unconfirmed == null
                ? null
                : new CountsRow(
                        rs.getInt("connected"),
                        rs.getInt("at_risk"),
                        rs.getInt("isolated"),
                        rs.getInt("no_data"),
                        strings(unconfirmed));
        String dates = rs.getString("s1_dates");
        Timestamp assessed = rs.getTimestamp("assessed_at");
        return new PassRow(
                passId,
                rs.getObject("acquired_on", LocalDate.class),
                rs.getString("time_utc"),
                rs.getInt("track"),
                rs.getString("direction"),
                rs.getString("granules"),
                assessed == null ? null : assessed.toInstant(),
                rs.getString("method_digest"),
                rs.getDouble("coverage"),
                rs.getBoolean("evaluated"),
                rs.getDouble("water_km2"),
                nullableDouble(rs, "vegetated_km2"),
                nullableDouble(rs, "river_level_cm"),
                nullableBoolean(rs, "low_water"),
                nullableDouble(rs, "stats_spacing_m"),
                box(rs, "w_"),
                box(rs, "r_"),
                counts,
                dates == null
                        ? List.of()
                        : strings(dates).stream().map(LocalDate::parse).toList(),
                changes.get(passId));
    }

    @Override
    public List<AssessmentRow> assessments(String areaId) {
        return jdbc.query(
                """
                SELECT pass_id, community_id, status, bottleneck_m, distance_to_water_m, unverified_route
                FROM connectivity_assessment WHERE area_id = :area
                """,
                new MapSqlParameterSource("area", areaId),
                (rs, i) -> new AssessmentRow(
                        rs.getString("pass_id"),
                        rs.getString("community_id"),
                        rs.getString("status"),
                        nullableInt(rs, "bottleneck_m"),
                        nullableInt(rs, "distance_to_water_m"),
                        rs.getBoolean("unverified_route")));
    }

    @Override
    public Map<String, String> communityClasses(String areaId) {
        Map<String, String> classes = new HashMap<>();
        jdbc.query(
                "SELECT community_id, class FROM community_class WHERE area_id = :area",
                new MapSqlParameterSource("area", areaId),
                rs -> {
                    classes.put(rs.getString("community_id"), rs.getString("class"));
                });
        return classes;
    }

    @Override
    public List<GaugeRow> gauge(String stationCode) {
        return jdbc.query(
                "SELECT day, level_cm FROM gauge_day WHERE station = :station ORDER BY day",
                new MapSqlParameterSource("station", stationCode),
                (rs, i) -> new GaugeRow(rs.getObject("day", LocalDate.class), rs.getDouble("level_cm")));
    }

    private static String boxColumns(String alias) {
        return String.join(
                ", ",
                List.of("id", "width", "height", "dtype", "origin_x", "origin_y", "step_x", "step_y").stream()
                        .map(c -> alias + "." + c + " AS " + alias + "_" + c)
                        .toList());
    }

    private static RasterBox box(ResultSet rs, String prefix) throws SQLException {
        String id = rs.getString(prefix + "id");
        if (id == null || rs.getObject(prefix + "width") == null) {
            return null;
        }
        return new RasterBox(
                id,
                rs.getInt(prefix + "width"),
                rs.getInt(prefix + "height"),
                rs.getString(prefix + "dtype"),
                rs.getDouble(prefix + "origin_x"),
                rs.getDouble(prefix + "origin_y"),
                rs.getDouble(prefix + "step_x"),
                rs.getDouble(prefix + "step_y"));
    }

    private List<String> strings(String text) {
        try {
            return new ArrayList<>(json.readValue(text, STRINGS));
        } catch (Exception e) {
            throw new IllegalStateException("Not a JSON list of strings: " + text, e);
        }
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }
}
