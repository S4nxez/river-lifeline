package com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc;

import com.riverlifeline.pipeline.application.port.out.GaugeRepository;
import com.riverlifeline.pipeline.domain.model.source.GaugeDay;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** Daily river levels in the {@code gauge_day} table. */
public class JdbcGaugeRepository implements GaugeRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcGaugeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String station, List<GaugeDay> days) {
        if (days.isEmpty()) {
            return;
        }
        SqlParameterSource[] rows = days.stream()
                .map(d -> new MapSqlParameterSource("station", station)
                        .addValue("day", d.day())
                        .addValue("level", BigDecimal.valueOf(d.levelCm())))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO gauge_day (station, day, level_cm) VALUES (:station, :day, :level)
                ON DUPLICATE KEY UPDATE level_cm = VALUES(level_cm)
                """, rows);
    }

    @Override
    public List<GaugeDay> between(String station, LocalDate from, LocalDate to) {
        return jdbc.query(
                """
                SELECT day, level_cm FROM gauge_day
                WHERE station = :station AND day BETWEEN :from AND :to ORDER BY day
                """,
                new MapSqlParameterSource("station", station)
                        .addValue("from", from)
                        .addValue("to", to),
                (rs, i) -> new GaugeDay(rs.getDate("day").toLocalDate(), rs.getDouble("level_cm")));
    }

    @Override
    public Optional<LocalDate> latestDay(String station) {
        return jdbc
                .query(
                        "SELECT MAX(day) AS day FROM gauge_day WHERE station = :station",
                        new MapSqlParameterSource("station", station),
                        (rs, i) -> Optional.ofNullable(Sql.date(rs, "day")))
                .stream()
                .findFirst()
                .flatMap(d -> d);
    }
}
