package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.job.Job;
import com.riverlifeline.pipeline.domain.model.job.JobId;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.job.LeaseId;
import com.riverlifeline.pipeline.domain.model.job.LeasedJob;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The job queue and its leases. */
public interface JobRepository {
    /** Queues a job; false (and nothing stored) when a pending job of the same type and subject exists. */
    boolean create(NewJob job);

    /** Leases the oldest pending job of these types and versions to a new lease, atomically. */
    Optional<LeasedJob> leaseNext(
            Set<JobType> types, Set<Integer> contractVersions, LeaseId lease, Instant now, Duration duration);

    Optional<Job> findByLease(LeaseId lease);

    void extend(LeaseId lease, Instant until);

    /** Ends the job's lease and marks the job done or failed. */
    void finish(Job job, boolean done, String error, Instant now);

    /** Ends the job's lease and puts the job back in the queue. */
    void retry(Job job, String error, Instant now);

    /**
     * Ends every lease that ran out before {@code now}: its job goes back in the queue, or fails when it had no
     * attempt left. Returns the jobs that failed.
     */
    List<Job> expireLeases(Instant now);

    /** Jobs of this type waiting or running. */
    int countActive(JobType type);

    /** A job queued with the backend's id and its inputs as the contract's JSON. */
    record NewJob(
            JobId id,
            JobType type,
            int contractVersion,
            AreaId areaId,
            String subject,
            String inputsJson,
            List<RasterId> rasters,
            int maxAttempts,
            Instant createdAt) {
        public NewJob {
            rasters = List.copyOf(rasters);
        }
    }
}
