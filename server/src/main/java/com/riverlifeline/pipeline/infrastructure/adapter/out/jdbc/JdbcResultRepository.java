package com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc;

import static com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc.Sql.ts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.pipeline.application.port.out.ResultRepository;
import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.result.ChangeResult;
import com.riverlifeline.pipeline.domain.model.result.Classification;
import com.riverlifeline.pipeline.domain.model.result.DroughtForecast;
import com.riverlifeline.pipeline.domain.model.result.TrainedDroughtModel;
import com.riverlifeline.pipeline.domain.model.source.DroughtModel;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** Changes, whole-record classes, drought models and forecasts. */
public class JdbcResultRepository implements ResultRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcResultRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void saveChange(AreaId area, ChangeResult change, RasterId changeRaster, Instant now) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("area", area.value())
                .addValue("pass", change.passId().value())
                .addValue("since", change.sincePassId().value())
                .addValue("lost", change.lostKm2())
                .addValue("gained", change.gainedKm2())
                .addValue("raster", changeRaster.value())
                .addValue("now", ts(now));
        jdbc.update("DELETE FROM pass_change WHERE area_id = :area AND pass_id = :pass", p);
        jdbc.update("""
                INSERT INTO pass_change (area_id, pass_id, since_pass_id, lost_km2, gained_km2, change_raster_id,
                    created_at)
                VALUES (:area, :pass, :since, :lost, :gained, :raster, :now)
                """, p);
    }

    @Override
    public void saveClassification(Classification c, Instant now) {
        MapSqlParameterSource area =
                new MapSqlParameterSource("area", c.areaId().value());
        jdbc.update("DELETE FROM community_class WHERE area_id = :area", area);
        jdbc.update("DELETE FROM pass_counts WHERE area_id = :area", area);
        SqlParameterSource[] classes = c.classes().entrySet().stream()
                .map(e -> new MapSqlParameterSource("area", c.areaId().value())
                        .addValue("community", e.getKey())
                        .addValue("class", e.getValue().wire())
                        .addValue("now", ts(now)))
                .toArray(SqlParameterSource[]::new);
        if (classes.length > 0) {
            jdbc.batchUpdate("""
                    INSERT INTO community_class (area_id, community_id, class, updated_at)
                    VALUES (:area, :community, :class, :now)
                    """, classes);
        }
        SqlParameterSource[] counts = c.passes().stream()
                .map(pc -> new MapSqlParameterSource("area", c.areaId().value())
                        .addValue("pass", pc.passId().value())
                        .addValue("connected", pc.connected())
                        .addValue("atRisk", pc.atRisk())
                        .addValue("isolated", pc.isolated())
                        .addValue("noData", pc.noData())
                        .addValue("unconfirmed", write(pc.unconfirmed()))
                        .addValue("now", ts(now)))
                .toArray(SqlParameterSource[]::new);
        if (counts.length > 0) {
            jdbc.batchUpdate("""
                    INSERT INTO pass_counts (area_id, pass_id, connected, at_risk, isolated, no_data, unconfirmed,
                        updated_at)
                    VALUES (:area, :pass, :connected, :atRisk, :isolated, :noData, :unconfirmed, :now)
                    """, counts);
        }
    }

    @Override
    public void saveDroughtModel(TrainedDroughtModel trained, Instant now) {
        DroughtModel model = trained.model();
        jdbc.update(
                """
                INSERT INTO drought_model (region, version, kind, lookback_days, parameters, observations, droughts,
                    trained_at)
                VALUES (:region, :version, :kind, :lookback, :parameters, :observations, :droughts, :now)
                ON DUPLICATE KEY UPDATE kind = VALUES(kind), lookback_days = VALUES(lookback_days),
                    parameters = VALUES(parameters), observations = VALUES(observations),
                    droughts = VALUES(droughts), trained_at = VALUES(trained_at)
                """,
                new MapSqlParameterSource()
                        .addValue("region", trained.region())
                        .addValue("version", model.version())
                        .addValue("kind", model.kind())
                        .addValue("lookback", model.lookbackDays())
                        .addValue("parameters", model.parametersJson())
                        .addValue("observations", trained.observations())
                        .addValue("droughts", trained.droughts())
                        .addValue("now", ts(now)));
    }

    @Override
    public Optional<DroughtModel> latestDroughtModel(String region) {
        return jdbc
                .query(
                        """
                        SELECT kind, version, lookback_days, parameters FROM drought_model
                        WHERE region = :region ORDER BY version DESC LIMIT 1
                        """,
                        new MapSqlParameterSource("region", region),
                        (rs, i) -> new DroughtModel(
                                rs.getString("kind"),
                                rs.getInt("version"),
                                rs.getInt("lookback_days"),
                                rs.getString("parameters")))
                .stream()
                .findFirst();
    }

    @Override
    public void saveForecast(DroughtForecast forecast, Instant now) {
        jdbc.update(
                """
                INSERT INTO drought_forecast (region, as_of, horizon_days, probability, risk_level, created_at)
                VALUES (:region, :asOf, :horizon, :probability, :risk, :now)
                ON DUPLICATE KEY UPDATE probability = VALUES(probability), risk_level = VALUES(risk_level),
                    created_at = VALUES(created_at)
                """,
                new MapSqlParameterSource()
                        .addValue("region", forecast.region())
                        .addValue("asOf", forecast.asOf())
                        .addValue("horizon", forecast.horizonDays())
                        .addValue("probability", forecast.probability())
                        .addValue("risk", forecast.riskLevel())
                        .addValue("now", ts(now)));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
