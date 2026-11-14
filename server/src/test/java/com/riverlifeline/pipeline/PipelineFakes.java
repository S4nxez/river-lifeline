package com.riverlifeline.pipeline;

import com.riverlifeline.pipeline.application.port.out.AreaCatalog;
import com.riverlifeline.pipeline.application.port.out.GaugeRepository;
import com.riverlifeline.pipeline.application.port.out.JobInputsEncoder;
import com.riverlifeline.pipeline.application.port.out.JobRepository;
import com.riverlifeline.pipeline.application.port.out.JobResultDecoder;
import com.riverlifeline.pipeline.application.port.out.OccurrenceSource;
import com.riverlifeline.pipeline.application.port.out.PassRepository;
import com.riverlifeline.pipeline.application.port.out.RasterRepository;
import com.riverlifeline.pipeline.application.port.out.RasterSink;
import com.riverlifeline.pipeline.application.port.out.RasterStore;
import com.riverlifeline.pipeline.application.port.out.ResultRepository;
import com.riverlifeline.pipeline.application.port.out.Transactions;
import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.job.Job;
import com.riverlifeline.pipeline.domain.model.job.JobInputs;
import com.riverlifeline.pipeline.domain.model.job.JobInputs.PassRoutes;
import com.riverlifeline.pipeline.domain.model.job.JobState;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.job.LeaseId;
import com.riverlifeline.pipeline.domain.model.job.LeasedJob;
import com.riverlifeline.pipeline.domain.model.pass.NisarPass;
import com.riverlifeline.pipeline.domain.model.pass.PassId;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.raster.RasterRole;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import com.riverlifeline.pipeline.domain.model.result.ChangeResult;
import com.riverlifeline.pipeline.domain.model.result.Classification;
import com.riverlifeline.pipeline.domain.model.result.DroughtForecast;
import com.riverlifeline.pipeline.domain.model.result.JobResult;
import com.riverlifeline.pipeline.domain.model.result.PassAssessment;
import com.riverlifeline.pipeline.domain.model.result.TrainedDroughtModel;
import com.riverlifeline.pipeline.domain.model.source.DroughtModel;
import com.riverlifeline.pipeline.domain.model.source.GaugeDay;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

/** In-memory implementations of the pipeline's ports, with the behaviour the MySQL adapters have. */
public final class PipelineFakes {
    private PipelineFakes() {}

    public static final class Areas implements AreaCatalog {
        public final List<Area> areas = new ArrayList<>();

        public Areas(Area... areas) {
            this.areas.addAll(List.of(areas));
        }

        @Override
        public List<Area> all() {
            return areas;
        }
    }

    /** A stored job, as the job table holds it. */
    public static final class StoredJob {
        public Job job;
        public final String inputs;
        public final List<RasterId> rasters;
        public final Instant createdAt;
        public String lastError;

        StoredJob(Job job, String inputs, List<RasterId> rasters, Instant createdAt) {
            this.job = job;
            this.inputs = inputs;
            this.rasters = rasters;
            this.createdAt = createdAt;
        }
    }

    public static final class Jobs implements JobRepository {
        public final List<StoredJob> jobs = new ArrayList<>();

        public List<StoredJob> ofType(JobType type) {
            return jobs.stream().filter(j -> j.job.type() == type).toList();
        }

        @Override
        public boolean create(NewJob n) {
            boolean duplicate = jobs.stream()
                    .anyMatch(j -> j.job.state() == JobState.PENDING
                            && j.job.type() == n.type()
                            && j.job.subject().equals(n.subject()));
            if (duplicate) {
                return false;
            }
            jobs.add(new StoredJob(
                    new Job(
                            n.id(),
                            n.type(),
                            n.contractVersion(),
                            n.areaId(),
                            n.subject(),
                            JobState.PENDING,
                            0,
                            n.maxAttempts(),
                            null,
                            null),
                    n.inputsJson(),
                    n.rasters(),
                    n.createdAt()));
            return true;
        }

        @Override
        public Optional<LeasedJob> leaseNext(
                Set<JobType> types, Set<Integer> versions, LeaseId lease, Instant now, Duration duration) {
            Optional<StoredJob> next = jobs.stream()
                    .filter(j -> j.job.state() == JobState.PENDING
                            && types.contains(j.job.type())
                            && versions.contains(j.job.contractVersion()))
                    .min(Comparator.comparing(j -> j.createdAt));
            next.ifPresent(j -> j.job = with(j.job, JobState.LEASED, j.job.attempts() + 1, lease, now.plus(duration)));
            return next.map(j ->
                    new LeasedJob(lease, j.job.type(), j.job.contractVersion(), (int) duration.toSeconds(), j.inputs));
        }

        @Override
        public Optional<Job> findByLease(LeaseId lease) {
            return jobs.stream()
                    .map(j -> j.job)
                    .filter(j -> lease.equals(j.lease()))
                    .findFirst();
        }

        @Override
        public void extend(LeaseId lease, Instant until) {
            jobs.stream()
                    .filter(j -> lease.equals(j.job.lease()) && j.job.state() == JobState.LEASED)
                    .forEach(j -> j.job = with(j.job, JobState.LEASED, j.job.attempts(), lease, until));
        }

        @Override
        public void finish(Job job, boolean done, String error, Instant now) {
            StoredJob s = byId(job);
            s.job = with(s.job, done ? JobState.DONE : JobState.FAILED, s.job.attempts(), s.job.lease(), null);
            s.lastError = error;
        }

        @Override
        public void retry(Job job, String error, Instant now) {
            StoredJob s = byId(job);
            s.job = with(s.job, JobState.PENDING, s.job.attempts(), null, null);
            s.lastError = error;
        }

        @Override
        public List<Job> expireLeases(Instant now) {
            List<Job> failed = new ArrayList<>();
            for (StoredJob s : jobs) {
                Job j = s.job;
                if (j.state() == JobState.LEASED && !j.leaseExpiresAt().isAfter(now)) {
                    if (j.canRetry()) {
                        s.job = with(j, JobState.PENDING, j.attempts(), null, null);
                    } else {
                        s.job = with(j, JobState.FAILED, j.attempts(), j.lease(), null);
                        failed.add(j);
                    }
                }
            }
            return failed;
        }

        @Override
        public int countActive(JobType type) {
            return (int) jobs.stream()
                    .filter(j -> j.job.type() == type
                            && (j.job.state() == JobState.PENDING || j.job.state() == JobState.LEASED))
                    .count();
        }

        private StoredJob byId(Job job) {
            return jobs.stream()
                    .filter(j -> j.job.id().equals(job.id()))
                    .findFirst()
                    .orElseThrow();
        }

        private static Job with(Job j, JobState state, int attempts, LeaseId lease, Instant expires) {
            return new Job(
                    j.id(),
                    j.type(),
                    j.contractVersion(),
                    j.areaId(),
                    j.subject(),
                    state,
                    attempts,
                    j.maxAttempts(),
                    lease,
                    expires);
        }
    }

    public static final class Store implements RasterStore {
        public final Map<RasterId, byte[]> bodies = new HashMap<>();

        @Override
        public Writer create(RasterId id) {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            return new Writer() {
                @Override
                public OutputStream body() {
                    return raw;
                }

                @Override
                public StoredBody commit() throws IOException {
                    byte[] bytes = raw.toByteArray();
                    bodies.put(id, bytes);
                    try {
                        String sha = HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                        return new StoredBody(bytes.length, sha, bytes.length);
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public Optional<InputStream> openGzip(RasterId id) throws IOException {
            byte[] body = bodies.get(id);
            if (body == null) {
                return Optional.empty();
            }
            ByteArrayOutputStream gz = new ByteArrayOutputStream();
            try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
                out.write(body);
            }
            return Optional.of(new ByteArrayInputStream(gz.toByteArray()));
        }

        @Override
        public void delete(RasterId id) {
            bodies.remove(id);
        }
    }

    public static final class Rasters implements RasterRepository {
        public final Map<RasterId, StoredRaster> rasters = new LinkedHashMap<>();
        public Jobs jobs;

        @Override
        public void save(StoredRaster raster) {
            rasters.put(raster.id(), raster);
        }

        @Override
        public Optional<StoredRaster> find(RasterId id) {
            return Optional.ofNullable(rasters.get(id));
        }

        @Override
        public boolean referencedByLeasedJob(RasterId id, Instant now) {
            return jobs.jobs.stream()
                    .anyMatch(j -> j.rasters.contains(id)
                            && j.job.state() == JobState.LEASED
                            && j.job.leaseExpiresAt().isAfter(now));
        }

        @Override
        public Optional<StoredRaster> pending(LeaseId lease, ResultRasterName name) {
            return rasters.values().stream()
                    .filter(r -> r.role() == RasterRole.PENDING && lease.equals(r.lease()) && name == r.name())
                    .findFirst();
        }

        @Override
        public List<StoredRaster> pendingOf(LeaseId lease) {
            return rasters.values().stream()
                    .filter(r -> r.role() == RasterRole.PENDING && lease.equals(r.lease()))
                    .toList();
        }

        @Override
        public void promote(RasterId id, RasterMeta meta) {
            StoredRaster r = rasters.get(id);
            rasters.put(
                    id, new StoredRaster(id, RasterRole.RESULT, meta, r.body(), r.lease(), r.name(), r.createdAt()));
        }

        @Override
        public void delete(RasterId id) {
            rasters.remove(id);
        }

        public final List<RasterId> unreferenced = new ArrayList<>();

        @Override
        public List<RasterId> unreferenced(Instant createdBefore) {
            return List.copyOf(unreferenced);
        }
    }

    /** A pass as the pass table holds it. */
    public static final class PassRow {
        public final AreaId area;
        public NisarPass pass;
        public PassAssessment assessment;
        public final Map<ResultRasterName, RasterId> rasters = new HashMap<>();
        public String digest;
        public int stagingAttempts;
        public Instant stagingAfter;
        public boolean givenUp;
        public String stagingError;

        PassRow(AreaId area, NisarPass pass) {
            this.area = area;
            this.pass = pass;
        }
    }

    public static final class Passes implements PassRepository {
        public final Map<String, PassRow> rows = new TreeMap<>();
        public Rasters rasters;
        public Jobs jobs;

        public PassRow row(AreaId area, PassId pass) {
            return rows.get(area + "/" + pass);
        }

        @Override
        public void saveDiscovered(AreaId area, NisarPass pass, Instant now) {
            PassRow row = rows.computeIfAbsent(area + "/" + pass.info().id(), k -> new PassRow(area, pass));
            if (row.assessment == null) {
                row.pass = pass;
            }
        }

        @Override
        public Optional<StagingCandidate> nextToStage(Instant now) {
            return rows.values().stream()
                    .filter(r -> r.assessment == null
                            && !r.givenUp
                            && (r.stagingAfter == null || !r.stagingAfter.isAfter(now))
                            && jobs.jobs.stream()
                                    .noneMatch(j -> j.job.type() == JobType.ASSESS_PASS
                                            && j.job.subject()
                                                    .equals(r.area + "/"
                                                            + r.pass.info().id())
                                            && (j.job.state() == JobState.PENDING || j.job.state() == JobState.LEASED)))
                    .max(Comparator.comparing(r -> r.pass.info().date()))
                    .map(r -> new StagingCandidate(r.area, r.pass, r.stagingAttempts));
        }

        @Override
        public void stagingFailed(AreaId area, PassId pass, String error, Instant retryAfter) {
            PassRow row = row(area, pass);
            row.stagingAttempts++;
            row.stagingError = error;
            row.stagingAfter = retryAfter;
            row.givenUp = retryAfter == null;
        }

        @Override
        public Optional<StoredPass> find(AreaId area, PassId pass) {
            return Optional.ofNullable(row(area, pass)).map(this::stored);
        }

        @Override
        public List<StoredPass> shownOnTrack(AreaId area, int track) {
            return rows.values().stream()
                    .filter(r -> r.area.equals(area)
                            && r.pass.info().track() == track
                            && r.assessment != null
                            && r.assessment.shown()
                            && r.rasters.containsKey(ResultRasterName.WATER))
                    .sorted(Comparator.comparing((PassRow r) -> r.pass.info().date()))
                    .map(this::stored)
                    .toList();
        }

        @Override
        public List<StoredPass> stale(AreaId area, String digest) {
            return rows.values().stream()
                    .filter(r -> r.area.equals(area)
                            && r.assessment != null
                            && r.rasters.containsKey(ResultRasterName.WATER)
                            && !digest.equals(r.digest))
                    .map(this::stored)
                    .toList();
        }

        @Override
        public List<PassRoutes> evaluatedRoutes(AreaId area) {
            return rows.values().stream()
                    .filter(r -> r.area.equals(area) && r.assessment != null && r.assessment.evaluated())
                    .sorted(Comparator.comparing((PassRow r) -> r.pass.info().date()))
                    .map(r -> new PassRoutes(
                            r.pass.info().id(),
                            r.pass.info().date(),
                            r.pass.info().timeUtc(),
                            r.assessment.communities()))
                    .toList();
        }

        @Override
        public Optional<LocalDate> earliestDate(AreaId area) {
            return rows.values().stream()
                    .filter(r -> r.area.equals(area))
                    .map(r -> r.pass.info().date())
                    .min(Comparator.naturalOrder());
        }

        @Override
        public void saveAssessment(
                AreaId area,
                PassAssessment assessment,
                Map<ResultRasterName, RasterId> rasters,
                String digest,
                LeaseId lease,
                Instant now) {
            PassRow row = row(area, assessment.passId());
            row.assessment = assessment;
            row.rasters.putAll(rasters);
            row.digest = digest;
        }

        private StoredPass stored(PassRow r) {
            return new StoredPass(
                    r.area,
                    r.pass.info(),
                    r.assessment != null,
                    r.assessment != null && r.assessment.shown(),
                    r.assessment != null && r.assessment.evaluated(),
                    ref(r.rasters.get(ResultRasterName.WATER)),
                    ref(r.rasters.get(ResultRasterName.SENTINEL1)),
                    r.assessment == null || r.assessment.sentinel1() == null
                            ? List.of()
                            : r.assessment.sentinel1().dates(),
                    r.digest);
        }

        private RasterRef ref(RasterId id) {
            if (id == null) {
                return null;
            }
            RasterMeta meta = rasters == null
                    ? PipelineTestData.classes()
                    : rasters.find(id).map(r -> r.meta()).orElse(PipelineTestData.classes());
            return new RasterRef(id, meta);
        }
    }

    public static final class Results implements ResultRepository {
        public final List<ChangeResult> changes = new ArrayList<>();
        public final List<Classification> classifications = new ArrayList<>();
        public final List<TrainedDroughtModel> models = new ArrayList<>();
        public final List<DroughtForecast> forecasts = new ArrayList<>();

        @Override
        public void saveChange(AreaId area, ChangeResult change, RasterId changeRaster, Instant now) {
            changes.add(change);
        }

        @Override
        public void saveClassification(Classification classification, Instant now) {
            classifications.add(classification);
        }

        @Override
        public void saveDroughtModel(TrainedDroughtModel trained, Instant now) {
            models.add(trained);
        }

        @Override
        public Optional<DroughtModel> latestDroughtModel(String region) {
            return models.stream()
                    .filter(m -> m.region().equals(region))
                    .map(TrainedDroughtModel::model)
                    .max(Comparator.comparingInt(DroughtModel::version));
        }

        @Override
        public void saveForecast(DroughtForecast forecast, Instant now) {
            forecasts.add(forecast);
        }
    }

    public static final class Gauges implements GaugeRepository {
        public final Map<String, TreeMap<LocalDate, Double>> days = new HashMap<>();

        @Override
        public void save(String station, List<GaugeDay> list) {
            TreeMap<LocalDate, Double> s = days.computeIfAbsent(station, k -> new TreeMap<>());
            list.forEach(d -> s.put(d.day(), d.levelCm()));
        }

        @Override
        public List<GaugeDay> between(String station, LocalDate from, LocalDate to) {
            return days.getOrDefault(station, new TreeMap<>()).subMap(from, true, to, true).entrySet().stream()
                    .map(e -> new GaugeDay(e.getKey(), e.getValue()))
                    .toList();
        }

        @Override
        public Optional<LocalDate> latestDay(String station) {
            TreeMap<LocalDate, Double> s = days.get(station);
            return s == null || s.isEmpty() ? Optional.empty() : Optional.of(s.lastKey());
        }
    }

    /** Writes inputs as their type and rasters, enough for tests to read back what a job carries. */
    public static final class Encoder implements JobInputsEncoder {
        public final List<JobInputs> encoded = new ArrayList<>();

        @Override
        public String encode(JobInputs inputs) {
            encoded.add(inputs);
            return "{\"type\":\"" + inputs.type().wire() + "\"}";
        }

        public <T extends JobInputs> List<T> of(Class<T> type) {
            return encoded.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    /** Hands back the result registered for the JSON text. */
    public static final class Decoder implements JobResultDecoder {
        public final Map<String, JobResult> results = new HashMap<>();

        @Override
        public JobResult decode(JobType type, String dataJson) {
            JobResult result = results.get(dataJson);
            if (result == null) {
                throw new com.riverlifeline.pipeline.application.exception.InvalidResultException(
                        "unknown " + dataJson);
            }
            return result;
        }
    }

    public static final class NoTransactions implements Transactions {
        public int count;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            count++;
            return work.get();
        }
    }

    /** An occurrence of the right size, or none. */
    public static final class Occurrence implements OccurrenceSource {
        public boolean present = true;
        public int writes;

        @Override
        public Optional<RasterRef> write(Area area, RasterId id, RasterSink sink) throws IOException {
            if (!present) {
                return Optional.empty();
            }
            writes++;
            RasterMeta meta = area.grid()
                    .meta(
                            com.riverlifeline.pipeline.domain.model.raster.DataType.UINT8,
                            com.riverlifeline.pipeline.domain.model.raster.NoData.value(255),
                            com.riverlifeline.pipeline.domain.model.raster.RasterUnit.PERCENT);
            return Optional.of(sink.write(id, meta, out -> out.write(new byte[(int) meta.byteLength()])));
        }
    }
}
