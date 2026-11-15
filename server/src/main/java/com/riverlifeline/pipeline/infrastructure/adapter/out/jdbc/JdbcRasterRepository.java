package com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc;

import static com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc.Sql.instant;
import static com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc.Sql.ts;

import com.riverlifeline.pipeline.application.port.out.RasterRepository;
import com.riverlifeline.pipeline.application.port.out.RasterStore.StoredBody;
import com.riverlifeline.pipeline.domain.model.job.LeaseId;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterRole;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Raster descriptions in the {@code raster} table. */
public class JdbcRasterRepository implements RasterRepository {
    private static final String SELECT = "SELECT r.*" + Sql.metaColumns("r", "m_") + " FROM raster r";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRasterRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(StoredRaster raster) {
        RasterMeta meta = raster.meta();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", raster.id().value())
                .addValue("role", role(raster.role()))
                .addValue("byteLength", raster.body().byteLength())
                .addValue("storedBytes", raster.body().storedBytes())
                .addValue("sha256", raster.body().sha256())
                .addValue(
                        "lease", raster.lease() == null ? null : raster.lease().value())
                .addValue("name", raster.name() == null ? null : raster.name().wire())
                .addValue("createdAt", ts(raster.createdAt()));
        metaParams(p, meta);
        jdbc.update("""
                INSERT INTO raster (id, role, width, height, dtype, nodata, unit, crs, origin_x, origin_y, step_x,
                    step_y, byte_length, stored_bytes, sha256, lease_id, result_name, created_at)
                VALUES (:id, :role, :width, :height, :dtype, :nodata, :unit, :crs, :originX, :originY, :stepX,
                    :stepY, :byteLength, :storedBytes, :sha256, :lease, :name, :createdAt)
                ON DUPLICATE KEY UPDATE role = VALUES(role), width = VALUES(width), height = VALUES(height),
                    dtype = VALUES(dtype), nodata = VALUES(nodata), unit = VALUES(unit), crs = VALUES(crs),
                    origin_x = VALUES(origin_x), origin_y = VALUES(origin_y), step_x = VALUES(step_x),
                    step_y = VALUES(step_y), byte_length = VALUES(byte_length), stored_bytes = VALUES(stored_bytes),
                    sha256 = VALUES(sha256), lease_id = VALUES(lease_id), result_name = VALUES(result_name),
                    created_at = VALUES(created_at)
                """, p);
    }

    static void metaParams(MapSqlParameterSource p, RasterMeta meta) {
        p.addValue("width", meta == null ? null : meta.width())
                .addValue("height", meta == null ? null : meta.height())
                .addValue("dtype", meta == null ? null : meta.dtype().wire())
                .addValue("nodata", meta == null ? null : meta.nodata().text())
                .addValue("unit", meta == null ? null : meta.unit().wire())
                .addValue("crs", meta == null ? null : meta.crs())
                .addValue("originX", meta == null ? null : meta.origin().x())
                .addValue("originY", meta == null ? null : meta.origin().y())
                .addValue("stepX", meta == null ? null : meta.step().x())
                .addValue("stepY", meta == null ? null : meta.step().y());
    }

    @Override
    public Optional<StoredRaster> find(RasterId id) {
        return jdbc.query(SELECT + " WHERE r.id = :id", new MapSqlParameterSource("id", id.value()), this::map).stream()
                .findFirst();
    }

    @Override
    public boolean referencedByLeasedJob(RasterId id, Instant now) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM job_raster jr JOIN job j ON j.id = jr.job_id
                WHERE jr.raster_id = :id AND j.state = 'leased' AND j.lease_expires_at > :now
                """, new MapSqlParameterSource("id", id.value()).addValue("now", ts(now)), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public Optional<StoredRaster> pending(LeaseId lease, ResultRasterName name) {
        return jdbc
                .query(
                        SELECT
                                + " WHERE r.lease_id = :lease AND r.result_name = :name AND r.role = 'pending'"
                                + " ORDER BY r.created_at DESC",
                        new MapSqlParameterSource("lease", lease.value()).addValue("name", name.wire()),
                        this::map)
                .stream()
                .findFirst();
    }

    @Override
    public List<StoredRaster> pendingOf(LeaseId lease) {
        return jdbc.query(
                SELECT + " WHERE r.lease_id = :lease AND r.role = 'pending'",
                new MapSqlParameterSource("lease", lease.value()),
                this::map);
    }

    @Override
    public void promote(RasterId id, RasterMeta meta) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id.value());
        metaParams(p, meta);
        jdbc.update("""
                UPDATE raster SET role = 'result', width = :width, height = :height, dtype = :dtype,
                    nodata = :nodata, unit = :unit, crs = :crs, origin_x = :originX, origin_y = :originY,
                    step_x = :stepX, step_y = :stepY
                WHERE id = :id
                """, p);
    }

    @Override
    public void delete(RasterId id) {
        jdbc.update("DELETE FROM raster WHERE id = :id", new MapSqlParameterSource("id", id.value()));
    }

    @Override
    public List<RasterId> unreferenced(Instant createdBefore) {
        return jdbc.query(
                """
                SELECT r.id FROM raster r
                WHERE r.created_at < :before AND (
                    (r.role = 'input' AND NOT EXISTS (
                        SELECT 1 FROM job_raster jr JOIN job j ON j.id = jr.job_id
                        WHERE jr.raster_id = r.id AND j.state IN ('pending', 'leased')))
                    OR (r.role = 'pending' AND NOT EXISTS (
                        SELECT 1 FROM job j WHERE j.lease_id = r.lease_id AND j.state = 'leased'))
                    OR (r.role = 'result'
                        AND NOT EXISTS (SELECT 1 FROM radar_pass p
                            WHERE r.id IN (p.water_raster_id, p.radar_raster_id, p.sentinel1_raster_id,
                                p.sentinel1_vv_raster_id))
                        AND NOT EXISTS (SELECT 1 FROM pass_change c WHERE c.change_raster_id = r.id)
                        AND NOT EXISTS (SELECT 1 FROM job_raster jr JOIN job j ON j.id = jr.job_id
                            WHERE jr.raster_id = r.id AND j.state IN ('pending', 'leased'))))
                LIMIT 500
                """,
                new MapSqlParameterSource("before", ts(createdBefore)),
                (rs, i) -> new RasterId(rs.getString("id")));
    }

    private StoredRaster map(ResultSet rs, int row) throws SQLException {
        String lease = rs.getString("lease_id");
        String name = rs.getString("result_name");
        return new StoredRaster(
                new RasterId(rs.getString("id")),
                RasterRole.valueOf(rs.getString("role").toUpperCase(Locale.ROOT)),
                Sql.meta(rs, "m_"),
                new StoredBody(rs.getLong("byte_length"), rs.getString("sha256"), rs.getLong("stored_bytes")),
                lease == null ? null : new LeaseId(lease),
                name == null ? null : ResultRasterName.fromWire(name),
                instant(rs, "created_at"));
    }

    static String role(RasterRole role) {
        return role.name().toLowerCase(Locale.ROOT);
    }
}
