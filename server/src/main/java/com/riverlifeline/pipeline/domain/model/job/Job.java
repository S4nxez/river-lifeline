package com.riverlifeline.pipeline.domain.model.job;

import com.riverlifeline.pipeline.domain.model.area.AreaId;
import java.time.Instant;
import java.util.Objects;

/**
 * A job as the queue keeps it. {@code areaId} is null for drought jobs; {@code lease} and {@code leaseExpiresAt} are
 * the current hand-out, when there is one.
 */
public record Job(
        JobId id,
        JobType type,
        int contractVersion,
        AreaId areaId,
        String subject,
        JobState state,
        int attempts,
        int maxAttempts,
        LeaseId lease,
        Instant leaseExpiresAt) {

    public Job {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(state, "state");
    }

    /** Whether this lease still holds the job at {@code now}: the only case in which the worker may act on it. */
    public boolean heldBy(LeaseId candidate, Instant now) {
        return state == JobState.LEASED
                && lease != null
                && lease.equals(candidate)
                && leaseExpiresAt != null
                && leaseExpiresAt.isAfter(now);
    }

    /** Whether a failed attempt may be tried again. */
    public boolean canRetry() {
        return attempts < maxAttempts;
    }
}
