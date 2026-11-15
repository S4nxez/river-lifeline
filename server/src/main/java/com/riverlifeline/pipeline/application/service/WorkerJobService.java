package com.riverlifeline.pipeline.application.service;

import com.riverlifeline.pipeline.application.exception.InvalidRequestException;
import com.riverlifeline.pipeline.application.exception.InvalidResultException;
import com.riverlifeline.pipeline.application.exception.NotLeasedException;
import com.riverlifeline.pipeline.application.exception.RasterTooLargeException;
import com.riverlifeline.pipeline.application.port.in.WorkerJobs;
import com.riverlifeline.pipeline.application.port.out.AreaCatalog;
import com.riverlifeline.pipeline.application.port.out.JobRepository;
import com.riverlifeline.pipeline.application.port.out.JobResultDecoder;
import com.riverlifeline.pipeline.application.port.out.PassRepository;
import com.riverlifeline.pipeline.application.port.out.RasterRepository;
import com.riverlifeline.pipeline.application.port.out.RasterRepository.StoredRaster;
import com.riverlifeline.pipeline.application.port.out.RasterStore;
import com.riverlifeline.pipeline.application.port.out.RasterStore.StoredBody;
import com.riverlifeline.pipeline.application.port.out.ResultRepository;
import com.riverlifeline.pipeline.application.port.out.Transactions;
import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.job.Job;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.job.LeaseId;
import com.riverlifeline.pipeline.domain.model.job.LeasedJob;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterRole;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import com.riverlifeline.pipeline.domain.model.result.ChangeResult;
import com.riverlifeline.pipeline.domain.model.result.Classification;
import com.riverlifeline.pipeline.domain.model.result.DroughtForecast;
import com.riverlifeline.pipeline.domain.model.result.JobResult;
import com.riverlifeline.pipeline.domain.model.result.PassAssessment;
import com.riverlifeline.pipeline.domain.model.result.TrainedDroughtModel;
import com.riverlifeline.pipeline.domain.service.ResultCheck;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The worker's side of the queue: leases, uploads, results and failures (api/worker-api.yaml). */
public final class WorkerJobService implements WorkerJobs {
    private static final Logger log = LoggerFactory.getLogger(WorkerJobService.class);

    private final JobRepository jobs;
    private final RasterRepository rasters;
    private final RasterStore store;
    private final AreaCatalog areas;
    private final PassRepository passes;
    private final ResultRepository results;
    private final JobResultDecoder decoder;
    private final FollowUps followUps;
    private final Housekeeping housekeeping;
    private final Transactions transactions;
    private final PipelineSettings settings;
    private final Clock clock;

    public WorkerJobService(
            JobRepository jobs,
            RasterRepository rasters,
            RasterStore store,
            AreaCatalog areas,
            PassRepository passes,
            ResultRepository results,
            JobResultDecoder decoder,
            FollowUps followUps,
            Housekeeping housekeeping,
            Transactions transactions,
            PipelineSettings settings,
            Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.rasters = Objects.requireNonNull(rasters, "rasters");
        this.store = Objects.requireNonNull(store, "store");
        this.areas = Objects.requireNonNull(areas, "areas");
        this.passes = Objects.requireNonNull(passes, "passes");
        this.results = Objects.requireNonNull(results, "results");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.followUps = Objects.requireNonNull(followUps, "followUps");
        this.housekeeping = Objects.requireNonNull(housekeeping, "housekeeping");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<LeasedJob> next(Set<String> typeNames, Set<Integer> contractVersions) {
        Set<JobType> types = EnumSet.noneOf(JobType.class);
        for (String name : typeNames) {
            JobType type = JobType.fromWireOrNull(name.strip());
            if (type != null) {
                types.add(type);
            }
        }
        if (types.isEmpty() || !contractVersions.contains(JobFactory.CONTRACT_VERSION)) {
            return Optional.empty();
        }
        housekeeping.expireLeases();
        Optional<LeasedJob> leased = transactions.inTransaction(() -> jobs.leaseNext(
                types, Set.of(JobFactory.CONTRACT_VERSION), LeaseId.random(), clock.instant(), settings.lease()));
        leased.ifPresent(job -> log.info("Leased a {} job as {}", job.type().wire(), job.lease()));
        return leased;
    }

    @Override
    public int heartbeat(String lease) {
        Job job = held(lease);
        jobs.extend(job.lease(), clock.instant().plus(settings.lease()));
        return settings.leaseSeconds();
    }

    @Override
    public void uploadRaster(String lease, String name, InputStream body) throws IOException {
        ResultRasterName rasterName = rasterName(name);
        Job job = held(lease);
        long limit = areaOf(job).map(a -> a.grid().maxRasterBytes()).orElse(0L);
        RasterId id = RasterId.random();
        StoredBody stored;
        try (RasterStore.Writer writer = store.create(id)) {
            copy(body, writer.body(), limit);
            stored = writer.commit();
        }
        Optional<StoredRaster> earlier = rasters.pending(job.lease(), rasterName);
        rasters.save(new StoredRaster(id, RasterRole.PENDING, null, stored, job.lease(), rasterName, clock.instant()));
        if (earlier.isPresent()) {
            discard(earlier.get().id());
        }
    }

    @Override
    public void complete(String lease, String dataJson) {
        Job job = held(lease);
        JobResult result = decoder.decode(job.type(), dataJson);
        Area area = areaOf(job).orElse(null);
        List<String> problems = new ArrayList<>(ResultCheck.problems(job, result, area));
        Map<ResultRasterName, RasterId> uploaded = new EnumMap<>(ResultRasterName.class);
        result.rasters().forEach((name, meta) -> {
            Optional<StoredRaster> pending = rasters.pending(job.lease(), name);
            if (pending.isEmpty()) {
                problems.add("raster " + name.wire() + " was not uploaded");
            } else if (pending.get().body().byteLength() != meta.byteLength()) {
                problems.add("raster " + name.wire() + " has "
                        + pending.get().body().byteLength() + " bytes, its layout says " + meta.byteLength());
            } else {
                uploaded.put(name, pending.get().id());
            }
        });
        if (!problems.isEmpty()) {
            throw new InvalidResultException(String.join("; ", problems));
        }
        transactions.run(() -> {
            Job current = held(lease);
            Instant now = clock.instant();
            if (!(result instanceof PassAssessment pass) || pass.evaluated()) {
                uploaded.forEach(
                        (name, id) -> rasters.promote(id, result.rasters().get(name)));
            }
            store(current, result, area, uploaded, now);
            jobs.finish(current, true, null, now);
            if (result instanceof PassAssessment pass) {
                followUps.afterPass(area, pass.passId());
            }
        });
        log.info("Stored the result of {} ({})", job.subject(), job.type().wire());
        discardPendingOf(job.lease());
    }

    private void store(Job job, JobResult result, Area area, Map<ResultRasterName, RasterId> uploaded, Instant now) {
        switch (result) {
            case PassAssessment pass -> {
                if (pass.evaluated()) {
                    passes.saveAssessment(area.id(), pass, uploaded, area.digest(), job.lease(), now);
                } else {
                    passes.saveUnevaluated(area.id(), pass.passId(), pass.coverage(), area.digest(), job.lease(), now);
                }
            }
            case ChangeResult change ->
                results.saveChange(area.id(), change, uploaded.get(ResultRasterName.CHANGE), now);
            case Classification classification -> results.saveClassification(classification, now);
            case TrainedDroughtModel trained -> results.saveDroughtModel(trained, now);
            case DroughtForecast forecast -> results.saveForecast(forecast, now);
        }
    }

    @Override
    public void fail(String lease, String reason, boolean retryable) {
        Job job = held(lease);
        String why = reason == null || reason.isBlank() ? "no reason given" : reason.strip();
        Instant now = clock.instant();
        transactions.run(() -> {
            Job current = held(lease);
            if (retryable && current.canRetry()) {
                jobs.retry(current, why, now);
            } else {
                jobs.finish(current, false, why, now);
                housekeeping.jobFailedForGood(current, why, retryable, now);
            }
        });
        log.warn("Job {} ({}) failed: {}{}", job.subject(), job.type().wire(), why, retryable ? " (retryable)" : "");
        discardPendingOf(job.lease());
    }

    @Override
    public Optional<RasterDownload> raster(String rasterId) {
        if (!RasterId.isValid(rasterId)) {
            return Optional.empty();
        }
        RasterId id = new RasterId(rasterId);
        Optional<StoredRaster> stored = rasters.find(id);
        if (stored.isEmpty() || !rasters.referencedByLeasedJob(id, clock.instant())) {
            return Optional.empty();
        }
        long length = stored.get().body().byteLength();
        return Optional.of(new RasterDownload() {
            @Override
            public long byteLength() {
                return length;
            }

            @Override
            public InputStream openGzip() throws IOException {
                return store.openGzip(id).orElseThrow(() -> new IOException("Raster " + id + " has no body"));
            }
        });
    }

    private Job held(String lease) {
        if (!LeaseId.isValid(lease)) {
            throw new NotLeasedException(String.valueOf(lease));
        }
        LeaseId id = new LeaseId(lease);
        return jobs.findByLease(id)
                .filter(job -> job.heldBy(id, clock.instant()))
                .orElseThrow(() -> new NotLeasedException(lease));
    }

    private Optional<Area> areaOf(Job job) {
        return job.areaId() == null ? Optional.empty() : areas.find(job.areaId());
    }

    private static ResultRasterName rasterName(String name) {
        try {
            return ResultRasterName.fromWire(name);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    private static void copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        for (int n; (n = in.read(buffer)) > 0; ) {
            total += n;
            if (total > limit) {
                throw new RasterTooLargeException(limit);
            }
            out.write(buffer, 0, n);
        }
    }

    private void discardPendingOf(LeaseId lease) {
        for (StoredRaster pending : rasters.pendingOf(lease)) {
            if (pending.role() == RasterRole.PENDING) {
                discard(pending.id());
            }
        }
    }

    private void discard(RasterId id) {
        try {
            store.delete(id);
            rasters.delete(id);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete raster {} (the sweep will): {}", id, e.getMessage());
        }
    }
}
