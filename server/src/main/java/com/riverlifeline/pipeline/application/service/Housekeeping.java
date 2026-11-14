package com.riverlifeline.pipeline.application.service;

import com.riverlifeline.pipeline.application.port.out.JobRepository;
import com.riverlifeline.pipeline.application.port.out.PassRepository;
import com.riverlifeline.pipeline.application.port.out.RasterRepository;
import com.riverlifeline.pipeline.application.port.out.RasterStore;
import com.riverlifeline.pipeline.domain.model.job.Job;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.pass.PassId;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Leases that ran out, passes whose assessment failed for good, and raster bodies nothing needs. */
public final class Housekeeping {
    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);

    private final JobRepository jobs;
    private final PassRepository passes;
    private final RasterRepository rasters;
    private final RasterStore store;
    private final PipelineSettings settings;
    private final Clock clock;

    public Housekeeping(
            JobRepository jobs,
            PassRepository passes,
            RasterRepository rasters,
            RasterStore store,
            PipelineSettings settings,
            Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.passes = Objects.requireNonNull(passes, "passes");
        this.rasters = Objects.requireNonNull(rasters, "rasters");
        this.store = Objects.requireNonNull(store, "store");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void expireLeases() {
        Instant now = clock.instant();
        for (Job failed : jobs.expireLeases(now)) {
            log.warn(
                    "Job {} ({}) failed: its last lease ran out",
                    failed.subject(),
                    failed.type().wire());
            jobFailedForGood(failed, "the worker's last lease ran out", true, now);
        }
    }

    /** An {@code assess_pass} that failed for good leaves its pass aside: for a while, or for ever if not retryable. */
    void jobFailedForGood(Job job, String reason, boolean retryable, Instant now) {
        if (job.type() == JobType.ASSESS_PASS && job.areaId() != null) {
            String subject = job.subject();
            PassId pass = new PassId(subject.substring(subject.indexOf('/') + 1));
            passes.stagingFailed(job.areaId(), pass, reason, retryable ? now.plus(settings.stagingRetry()) : null);
        }
    }

    public void sweepRasters() {
        List<RasterId> unused = rasters.unreferenced(clock.instant().minus(settings.rasterGrace()));
        for (RasterId id : unused) {
            try {
                store.delete(id);
                rasters.delete(id);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not delete raster {}: {}", id, e.getMessage());
            }
        }
        if (!unused.isEmpty()) {
            log.info("Deleted {} raster bodies nothing references", unused.size());
        }
    }
}
