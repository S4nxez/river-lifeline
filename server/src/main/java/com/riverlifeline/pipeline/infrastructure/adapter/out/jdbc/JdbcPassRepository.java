package com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc;

import static com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc.Sql.ts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.pipeline.application.port.out.PassRepository;
import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.job.JobInputs.PassRoutes;
import com.riverlifeline.pipeline.domain.model.job.LeaseId;
import com.riverlifeline.pipeline.domain.model.pass.Direction;
import com.riverlifeline.pipeline.domain.model.pass.GranuleRef;
import com.riverlifeline.pipeline.domain.model.pass.NisarPass;
import com.riverlifeline.pipeline.domain.model.pass.PassId;
import com.riverlifeline.pipeline.domain.model.pass.PassInfo;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import com.riverlifeline.pipeline.domain.model.result.CommunityRoute;
import com.riverlifeline.pipeline.domain.model.result.PassAssessment;
import com.riverlifeline.pipeline.domain.model.result.RouteResult;
import com.riverlifeline.pipeline.domain.model.result.RouteStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** Passes and their assessments: {@code radar_pass}, {@code pass_sentinel1} and {@code connectivity_assessment}. */
public class JdbcPassRepository implements PassRepository {
    private static final TypeReference<List<Map<String, String>>> GRANULES = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final String SELECT_STORED = "SELECT p.*, ps.dates AS s1_dates"
            + Sql.metaColumns("w", "w_")
            + Sql.metaColumns("s", "s_")
            + " FROM radar_pass p"
            + " LEFT JOIN raster w ON w.id = p.water_raster_id"
            + " LEFT JOIN raster s ON s.id = p.sentinel1_raster_id"
            + " LEFT JOIN pass_sentinel1 ps ON ps.area_id = p.area_id AND ps.pass_id = p.pass_id";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcPassRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void saveDiscovered(AreaId area, NisarPass pass, Instant now) {
        PassInfo info = pass.info();
        List<Map<String, String>> granules = pass.granules().stream()
                .map(g -> Map.of("name", g.name(), "url", g.url(), "collection", g.collection()))
                .toList();
        jdbc.update(
                """
                INSERT INTO radar_pass (area_id, pass_id, acquired_on, time_utc, track, direction, granules,
                    discovered_at)
                VALUES (:area, :pass, :date, :time, :track, :direction, :granules, :now) AS new
                ON DUPLICATE KEY UPDATE
                    granules = IF(radar_pass.assessed_at IS NULL, new.granules, radar_pass.granules),
                    time_utc = IF(radar_pass.assessed_at IS NULL, new.time_utc, radar_pass.time_utc)
                """,
                new MapSqlParameterSource()
                        .addValue("area", area.value())
                        .addValue("pass", info.id().value())
                        .addValue("date", info.date())
                        .addValue("time", info.timeUtc())
                        .addValue("track", info.track())
                        .addValue("direction", info.direction().wire())
                        .addValue("granules", write(granules))
                        .addValue("now", ts(now)));
    }

    @Override
    public Optional<StagingCandidate> nextToStage(Instant now) {
        return jdbc
                .query(
                        """
                        SELECT p.* FROM radar_pass p
                        WHERE p.assessed_at IS NULL AND p.staging_given_up = FALSE
                          AND (p.staging_after IS NULL OR p.staging_after <= :now)
                          AND NOT EXISTS (SELECT 1 FROM job j WHERE j.type = 'assess_pass'
                              AND j.subject = CONCAT(p.area_id, '/', p.pass_id) AND j.state IN ('pending', 'leased'))
                        ORDER BY p.acquired_on DESC, p.time_utc DESC, p.pass_id
                        LIMIT 1
                        """,
                        new MapSqlParameterSource("now", ts(now)),
                        (rs, i) -> new StagingCandidate(
                                new AreaId(rs.getString("area_id")),
                                new NisarPass(info(rs), granules(rs.getString("granules"))),
                                rs.getInt("staging_attempts")))
                .stream()
                .findFirst();
    }

    @Override
    public void stagingFailed(AreaId area, PassId pass, String error, Instant retryAfter) {
        jdbc.update(
                """
                UPDATE radar_pass SET staging_attempts = staging_attempts + 1, staging_error = :error,
                    staging_after = :after, staging_given_up = :givenUp
                WHERE area_id = :area AND pass_id = :pass
                """,
                new MapSqlParameterSource()
                        .addValue("area", area.value())
                        .addValue("pass", pass.value())
                        .addValue("error", error)
                        .addValue("after", ts(retryAfter))
                        .addValue("givenUp", retryAfter == null));
    }

    @Override
    public Optional<StoredPass> find(AreaId area, PassId pass) {
        return jdbc
                .query(
                        SELECT_STORED + " WHERE p.area_id = :area AND p.pass_id = :pass",
                        new MapSqlParameterSource("area", area.value()).addValue("pass", pass.value()),
                        this::stored)
                .stream()
                .findFirst();
    }

    @Override
    public List<StoredPass> shownOnTrack(AreaId area, int track) {
        return jdbc.query(
                SELECT_STORED
                        + " WHERE p.area_id = :area AND p.track = :track AND p.shown = TRUE"
                        + " AND p.water_raster_id IS NOT NULL ORDER BY p.acquired_on, p.time_utc, p.pass_id",
                new MapSqlParameterSource("area", area.value()).addValue("track", track),
                this::stored);
    }

    @Override
    public List<StoredPass> stale(AreaId area, String digest) {
        return jdbc.query(
                SELECT_STORED
                        + " WHERE p.area_id = :area AND p.assessed_at IS NOT NULL AND p.water_raster_id IS NOT NULL"
                        + " AND (p.method_digest IS NULL OR p.method_digest <> :digest)"
                        + " ORDER BY p.acquired_on, p.time_utc, p.pass_id",
                new MapSqlParameterSource("area", area.value()).addValue("digest", digest),
                this::stored);
    }

    @Override
    public List<PassRoutes> evaluatedRoutes(AreaId area) {
        Map<String, PassInfo> passes = new LinkedHashMap<>();
        Map<String, List<CommunityRoute>> routes = new LinkedHashMap<>();
        MapSqlParameterSource p = new MapSqlParameterSource("area", area.value());
        jdbc.query("""
                SELECT p.* FROM radar_pass p WHERE p.area_id = :area AND p.evaluated = TRUE
                ORDER BY p.acquired_on, p.time_utc, p.pass_id
                """, p, rs -> {
            PassInfo info = info(rs);
            passes.put(info.id().value(), info);
            routes.put(info.id().value(), new ArrayList<>());
        });
        jdbc.query("""
                SELECT a.pass_id, a.community_id, a.status, a.bottleneck_m, a.distance_to_water_m
                FROM connectivity_assessment a WHERE a.area_id = :area ORDER BY a.pass_id, a.community_id
                """, p, rs -> {
            List<CommunityRoute> list = routes.get(rs.getString("pass_id"));
            if (list != null) {
                list.add(new CommunityRoute(
                        rs.getString("community_id"),
                        new RouteResult(
                                RouteStatus.fromWire(rs.getString("status")),
                                Sql.integer(rs, "bottleneck_m"),
                                Sql.integer(rs, "distance_to_water_m"))));
            }
        });
        List<PassRoutes> out = new ArrayList<>();
        passes.forEach((id, info) -> out.add(new PassRoutes(info.id(), info.date(), info.timeUtc(), routes.get(id))));
        return out;
    }

    @Override
    public Optional<LocalDate> earliestDate(AreaId area) {
        return jdbc
                .query(
                        "SELECT MIN(acquired_on) AS day FROM radar_pass WHERE area_id = :area",
                        new MapSqlParameterSource("area", area.value()),
                        (rs, i) -> Optional.ofNullable(Sql.date(rs, "day")))
                .stream()
                .findFirst()
                .flatMap(d -> d);
    }

    @Override
    public void saveAssessment(
            AreaId area,
            PassAssessment a,
            Map<ResultRasterName, RasterId> rasters,
            String digest,
            LeaseId lease,
            Instant now) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("area", area.value())
                .addValue("pass", a.passId().value())
                .addValue("now", ts(now))
                .addValue("lease", lease == null ? null : lease.value())
                .addValue("digest", digest)
                .addValue("coverage", a.coverage())
                .addValue("shown", a.shown())
                .addValue("evaluated", a.evaluated())
                .addValue("water", a.waterKm2())
                .addValue("narrow", a.narrowKm2())
                .addValue("vegetated", a.vegetatedKm2())
                .addValue("tSource", a.threshold().source())
                .addValue("tStrong", a.threshold().strongDb())
                .addValue("tWeak", a.threshold().weakDb())
                .addValue("tMeasured", a.threshold().measuredDb())
                .addValue("tTiles", a.threshold().tiles())
                .addValue("level", a.riverLevelCm())
                .addValue("lowWater", a.lowWater())
                .addValue(
                        "hubStatus",
                        a.hubLink() == null ? null : a.hubLink().status().wire())
                .addValue(
                        "hubBottleneck",
                        a.hubLink() == null ? null : a.hubLink().bottleneckM())
                .addValue(
                        "hubDistance", a.hubLink() == null ? null : a.hubLink().distanceToWaterM())
                .addValue("seconds", a.stats() == null ? null : a.stats().seconds())
                .addValue("spacing", a.stats() == null ? null : a.stats().spacingM())
                .addValue("waterRaster", id(rasters, ResultRasterName.WATER))
                .addValue("radarRaster", id(rasters, ResultRasterName.RADAR))
                .addValue("s1Raster", id(rasters, ResultRasterName.SENTINEL1))
                .addValue("s1VvRaster", id(rasters, ResultRasterName.SENTINEL1_VV));
        int updated = jdbc.update("""
                UPDATE radar_pass SET assessed_at = :now, lease_id = :lease, method_digest = :digest,
                    coverage = :coverage, shown = :shown, evaluated = :evaluated, water_km2 = :water,
                    narrow_km2 = :narrow, vegetated_km2 = :vegetated, threshold_source = :tSource,
                    threshold_strong_db = :tStrong, threshold_weak_db = :tWeak, threshold_measured_db = :tMeasured,
                    threshold_tiles = :tTiles, river_level_cm = :level, low_water = :lowWater,
                    hub_status = :hubStatus, hub_bottleneck_m = :hubBottleneck,
                    hub_distance_to_water_m = :hubDistance, stats_seconds = :seconds, stats_spacing_m = :spacing,
                    water_raster_id = COALESCE(:waterRaster, water_raster_id),
                    radar_raster_id = COALESCE(:radarRaster, radar_raster_id),
                    sentinel1_raster_id = COALESCE(:s1Raster, sentinel1_raster_id),
                    sentinel1_vv_raster_id = COALESCE(:s1VvRaster, sentinel1_vv_raster_id),
                    staging_error = NULL
                WHERE area_id = :area AND pass_id = :pass
                """, p);
        if (updated == 0) {
            throw new IllegalStateException("No pass " + a.passId() + " in " + area);
        }
        jdbc.update("DELETE FROM connectivity_assessment WHERE area_id = :area AND pass_id = :pass", p);
        Set<String> unverified = new HashSet<>(a.unverifiedRoute());
        SqlParameterSource[] rows = a.communities().stream()
                .map(c -> new MapSqlParameterSource()
                        .addValue("area", area.value())
                        .addValue("pass", a.passId().value())
                        .addValue("community", c.communityId())
                        .addValue("status", c.route().status().wire())
                        .addValue("bottleneck", c.route().bottleneckM())
                        .addValue("distance", c.route().distanceToWaterM())
                        .addValue("unverified", unverified.contains(c.communityId())))
                .toArray(SqlParameterSource[]::new);
        if (rows.length > 0) {
            jdbc.batchUpdate("""
                    INSERT INTO connectivity_assessment (area_id, pass_id, community_id, status, bottleneck_m,
                        distance_to_water_m, unverified_route)
                    VALUES (:area, :pass, :community, :status, :bottleneck, :distance, :unverified)
                    """, rows);
        }
        jdbc.update("DELETE FROM pass_sentinel1 WHERE area_id = :area AND pass_id = :pass", p);
        PassAssessment.Sentinel1Agreement s1 = a.sentinel1();
        if (s1 != null) {
            jdbc.update(
                    """
                    INSERT INTO pass_sentinel1 (area_id, pass_id, dates, judged_km2, agreement, both_water_km2,
                        nisar_only_km2, sentinel1_only_km2, mud_km2)
                    VALUES (:area, :pass, :dates, :judged, :agreement, :both, :nisarOnly, :s1Only, :mud)
                    """,
                    new MapSqlParameterSource()
                            .addValue("area", area.value())
                            .addValue("pass", a.passId().value())
                            .addValue(
                                    "dates",
                                    write(s1.dates().stream()
                                            .map(LocalDate::toString)
                                            .toList()))
                            .addValue("judged", s1.judgedKm2())
                            .addValue("agreement", s1.agreement())
                            .addValue("both", s1.bothWaterKm2())
                            .addValue("nisarOnly", s1.nisarOnlyKm2())
                            .addValue("s1Only", s1.sentinel1OnlyKm2())
                            .addValue("mud", s1.mudKm2()));
        }
    }

    private static String id(Map<ResultRasterName, RasterId> rasters, ResultRasterName name) {
        RasterId id = rasters.get(name);
        return id == null ? null : id.value();
    }

    private StoredPass stored(ResultSet rs, int row) throws SQLException {
        RasterMeta water = Sql.meta(rs, "w_");
        RasterMeta s1 = Sql.meta(rs, "s_");
        String s1Dates = rs.getString("s1_dates");
        Boolean shown = Sql.bool(rs, "shown");
        Boolean evaluated = Sql.bool(rs, "evaluated");
        return new StoredPass(
                new AreaId(rs.getString("area_id")),
                info(rs),
                rs.getTimestamp("assessed_at") != null,
                Boolean.TRUE.equals(shown),
                Boolean.TRUE.equals(evaluated),
                water == null ? null : new RasterRef(new RasterId(rs.getString("w_id")), water),
                s1 == null ? null : new RasterRef(new RasterId(rs.getString("s_id")), s1),
                s1Dates == null
                        ? List.of()
                        : read(s1Dates, STRINGS).stream().map(LocalDate::parse).toList(),
                rs.getString("method_digest"));
    }

    private static PassInfo info(ResultSet rs) throws SQLException {
        return new PassInfo(
                new PassId(rs.getString("pass_id")),
                rs.getDate("acquired_on").toLocalDate(),
                rs.getString("time_utc"),
                rs.getInt("track"),
                Direction.fromWire(rs.getString("direction")));
    }

    private List<GranuleRef> granules(String text) {
        return read(text, GRANULES).stream()
                .map(g -> GranuleRef.parse(g.get("name"), g.get("url"), g.get("collection")))
                .toList();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable JSON column: " + e.getOriginalMessage(), e);
        }
    }
}
