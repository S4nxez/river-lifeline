package com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc;

import static com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc.Sql.instant;
import static com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc.Sql.ts;

import com.riverlifeline.pipeline.application.port.out.JobRepository;
import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.job.Job;
import com.riverlifeline.pipeline.domain.model.job.JobId;
import com.riverlifeline.pipeline.domain.model.job.JobState;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.job.LeaseId;
import com.riverlifeline.pipeline.domain.model.job.LeasedJob;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The job queue in MySQL. A job is leased with {@code SELECT … FOR UPDATE SKIP LOCKED}, so two workers asking at once
 * never get the same job.
 */
public class JdbcJobRepository implements JobRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcJobRepository(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /**
     * The duplicate is caught inside the transaction callback: when this joins the caller's transaction (a result and
     * the jobs that follow from it), a refused duplicate must not mark the caller's work for rollback.
     */
    @Override
    public boolean create(NewJob job) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            try {
                jdbc.update(
                        """
                            INSERT INTO job (id, type, contract_version, area_id, subject, state, inputs, attempts,
                                max_attempts, created_at, updated_at)
                            VALUES (:id, :type, :version, :area, :subject, 'pending', :inputs, 0, :maxAttempts,
                                :now, :now)
                            """,
                        new MapSqlParameterSource()
                                .addValue("id", job.id().value())
                                .addValue("type", job.type().wire())
                                .addValue("version", job.contractVersion())
                                .addValue(
                                        "area",
                                        job.areaId() == null
                                                ? null
                                                : job.areaId().value())
                                .addValue("subject", job.subject())
                                .addValue("inputs", job.inputsJson())
                                .addValue("maxAttempts", job.maxAttempts())
                                .addValue("now", ts(job.createdAt())));
            } catch (DuplicateKeyException e) {
                return false;
            }
            List<SqlParameterSource> links = new ArrayList<>();
            for (RasterId raster : job.rasters()) {
                links.add(new MapSqlParameterSource("job", job.id().value()).addValue("raster", raster.value()));
            }
            if (!links.isEmpty()) {
                jdbc.batchUpdate(
                        "INSERT INTO job_raster (job_id, raster_id) VALUES (:job, :raster)",
                        links.toArray(SqlParameterSource[]::new));
            }
            return true;
        }));
    }

    @Override
    public Optional<LeasedJob> leaseNext(
            Set<JobType> types, Set<Integer> contractVersions, LeaseId lease, Instant now, Duration duration) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("types", types.stream().map(JobType::wire).toList())
                .addValue("versions", List.copyOf(contractVersions));
        List<String[]> found = jdbc.query("""
                SELECT id, type, contract_version, inputs FROM job
                WHERE state = 'pending' AND type IN (:types) AND contract_version IN (:versions)
                ORDER BY created_at, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """, p, (rs, i) -> new String[] {
            rs.getString("id"), rs.getString("type"), rs.getString("contract_version"), rs.getString("inputs")
        });
        if (found.isEmpty()) {
            return Optional.empty();
        }
        String[] row = found.get(0);
        Instant expires = now.plus(duration);
        MapSqlParameterSource q = new MapSqlParameterSource()
                .addValue("id", row[0])
                .addValue("lease", lease.value())
                .addValue("now", ts(now))
                .addValue("expires", ts(expires));
        jdbc.update("""
                UPDATE job SET state = 'leased', attempts = attempts + 1, lease_id = :lease,
                    lease_expires_at = :expires, updated_at = :now
                WHERE id = :id
                """, q);
        jdbc.update(
                "INSERT INTO job_lease (id, job_id, leased_at, expires_at) VALUES (:lease, :id, :now, :expires)", q);
        return Optional.of(new LeasedJob(
                lease, JobType.fromWire(row[1]), Integer.parseInt(row[2]), (int) duration.toSeconds(), row[3]));
    }

    @Override
    public Optional<Job> findByLease(LeaseId lease) {
        return jdbc
                .query(
                        "SELECT * FROM job WHERE lease_id = :lease",
                        new MapSqlParameterSource("lease", lease.value()),
                        JdbcJobRepository::map)
                .stream()
                .findFirst();
    }

    @Override
    public void extend(LeaseId lease, Instant until) {
        MapSqlParameterSource p = new MapSqlParameterSource("lease", lease.value()).addValue("until", ts(until));
        jdbc.update("UPDATE job SET lease_expires_at = :until WHERE lease_id = :lease AND state = 'leased'", p);
        jdbc.update("UPDATE job_lease SET expires_at = :until WHERE id = :lease", p);
    }

    @Override
    public void finish(Job job, boolean done, String error, Instant now) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", job.id().value())
                .addValue("state", done ? "done" : "failed")
                .addValue("error", error)
                .addValue("now", ts(now));
        jdbc.update("""
                UPDATE job SET state = :state, last_error = COALESCE(:error, last_error), updated_at = :now,
                    finished_at = :now, lease_expires_at = NULL
                WHERE id = :id
                """, p);
        endLease(job.lease(), done ? "done" : "failed", now);
    }

    @Override
    public void retry(Job job, String error, Instant now) {
        requeue(job, error, now, "retried");
    }

    private void requeue(Job job, String error, Instant now, String outcome) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", job.id().value())
                .addValue("error", error)
                .addValue("now", ts(now));
        try {
            jdbc.update("""
                    UPDATE job SET state = 'pending', lease_id = NULL, lease_expires_at = NULL, last_error = :error,
                        updated_at = :now
                    WHERE id = :id
                    """, p);
        } catch (DuplicateKeyException e) {
            // A newer job of the same subject already waits: this one is superseded
            jdbc.update("""
                    UPDATE job SET state = 'failed', last_error = 'superseded by a newer job', updated_at = :now,
                        finished_at = :now, lease_expires_at = NULL
                    WHERE id = :id
                    """, p);
        }
        endLease(job.lease(), outcome, now);
    }

    private void endLease(LeaseId lease, String outcome, Instant now) {
        if (lease != null) {
            jdbc.update(
                    "UPDATE job_lease SET ended_at = :now, outcome = :outcome WHERE id = :lease AND ended_at IS NULL",
                    new MapSqlParameterSource("lease", lease.value())
                            .addValue("outcome", outcome)
                            .addValue("now", ts(now)));
        }
    }

    @Override
    public List<Job> expireLeases(Instant now) {
        List<Job> failed = new ArrayList<>();
        transactions.executeWithoutResult(status -> {
            List<Job> expired = jdbc.query(
                    "SELECT * FROM job WHERE state = 'leased' AND lease_expires_at <= :now FOR UPDATE",
                    new MapSqlParameterSource("now", ts(now)),
                    JdbcJobRepository::map);
            for (Job job : expired) {
                if (job.canRetry()) {
                    requeue(job, "lease expired", now, "expired");
                } else {
                    jdbc.update("""
                            UPDATE job SET state = 'failed', last_error = 'lease expired after the last attempt',
                                updated_at = :now, finished_at = :now, lease_expires_at = NULL
                            WHERE id = :id
                            """, new MapSqlParameterSource("id", job.id().value()).addValue("now", ts(now)));
                    endLease(job.lease(), "expired", now);
                    failed.add(job);
                }
            }
        });
        return failed;
    }

    @Override
    public int countActive(JobType type) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM job WHERE type = :type AND state IN ('pending', 'leased')",
                new MapSqlParameterSource("type", type.wire()),
                Integer.class);
        return count == null ? 0 : count;
    }

    static Job map(ResultSet rs, int row) throws SQLException {
        String area = rs.getString("area_id");
        String lease = rs.getString("lease_id");
        return new Job(
                new JobId(rs.getString("id")),
                JobType.fromWire(rs.getString("type")),
                rs.getInt("contract_version"),
                area == null ? null : new AreaId(area),
                rs.getString("subject"),
                JobState.fromWire(rs.getString("state")),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                lease == null ? null : new LeaseId(lease),
                instant(rs, "lease_expires_at"));
    }
}
