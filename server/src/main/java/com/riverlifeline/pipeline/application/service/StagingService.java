package com.riverlifeline.pipeline.application.service;

import com.riverlifeline.pipeline.application.exception.StagingException;
import com.riverlifeline.pipeline.application.port.out.AreaCatalog;
import com.riverlifeline.pipeline.application.port.out.GranuleWindows;
import com.riverlifeline.pipeline.application.port.out.JobRepository;
import com.riverlifeline.pipeline.application.port.out.PassRepository;
import com.riverlifeline.pipeline.application.port.out.PassRepository.StagingCandidate;
import com.riverlifeline.pipeline.application.port.out.RasterSink;
import com.riverlifeline.pipeline.application.port.out.Sentinel1Scenes;
import com.riverlifeline.pipeline.application.port.out.Sentinel1Scenes.Scene;
import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.area.Method;
import com.riverlifeline.pipeline.domain.model.job.JobInputs;
import com.riverlifeline.pipeline.domain.model.job.JobInputs.GranuleWindow;
import com.riverlifeline.pipeline.domain.model.job.JobInputs.Sentinel1Date;
import com.riverlifeline.pipeline.domain.model.job.JobSubject;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.pass.GranuleRef;
import com.riverlifeline.pipeline.domain.model.pass.NisarPass;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prepares {@code assess_pass} jobs: reads the area's window of every granule of the pass and of every Sentinel-1
 * scene around it, then queues the job with all its inputs, so a worker never waits on a remote product. At most
 * {@code stagedJobs} such jobs wait or run at once, which caps the disk their inputs take.
 */
public final class StagingService {
    private static final Logger log = LoggerFactory.getLogger(StagingService.class);
    private static final int MAX_BACKOFF_STEPS = 5;

    private final AreaCatalog areas;
    private final PassRepository passes;
    private final JobRepository jobs;
    private final GranuleWindows granules;
    private final Sentinel1Scenes sentinel1;
    private final OccurrenceService occurrence;
    private final JobFactory factory;
    private final RasterSink inputs;
    private final PipelineSettings settings;
    private final Clock clock;
    private boolean warnedUnavailable;

    public StagingService(
            AreaCatalog areas,
            PassRepository passes,
            JobRepository jobs,
            GranuleWindows granules,
            Sentinel1Scenes sentinel1,
            OccurrenceService occurrence,
            JobFactory factory,
            RasterSink inputSink,
            PipelineSettings settings,
            Clock clock) {
        this.areas = Objects.requireNonNull(areas, "areas");
        this.passes = Objects.requireNonNull(passes, "passes");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.granules = Objects.requireNonNull(granules, "granules");
        this.sentinel1 = Objects.requireNonNull(sentinel1, "sentinel1");
        this.occurrence = Objects.requireNonNull(occurrence, "occurrence");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.inputs = Objects.requireNonNull(inputSink, "inputSink");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Stages passes until the budget is used or none is due; returns how many jobs were queued. */
    public int stageAssessments() {
        if (!granules.available()) {
            if (!warnedUnavailable) {
                log.warn("NISAR products cannot be read (EARTHDATA_TOKEN is not set): no pass is staged");
                warnedUnavailable = true;
            }
            return 0;
        }
        int queued = 0;
        while (jobs.countActive(JobType.ASSESS_PASS) < settings.stagedJobs()) {
            Optional<StagingCandidate> next = passes.nextToStage(clock.instant());
            if (next.isEmpty()) {
                break;
            }
            if (stage(next.get())) {
                queued++;
            }
        }
        return queued;
    }

    private boolean stage(StagingCandidate candidate) {
        NisarPass pass = candidate.pass();
        Instant now = clock.instant();
        Optional<Area> area = areas.find(candidate.area());
        if (area.isEmpty()) {
            passes.stagingFailed(candidate.area(), pass.info().id(), "the area has no file any more", null);
            return false;
        }
        try {
            long started = System.nanoTime();
            JobInputs.AssessPass inputs = inputs(area.get(), pass);
            factory.submit(
                    inputs,
                    area.get().id(),
                    JobSubject.pass(area.get().id(), pass.info().id()));
            log.info(
                    "Staged {} {} ({} granules, {} Sentinel-1 dates) in {} s",
                    area.get().id(),
                    pass.info().id(),
                    inputs.granules().size(),
                    inputs.sentinel1Candidates().size(),
                    (System.nanoTime() - started) / 1_000_000_000);
            return true;
        } catch (StagingException e) {
            log.warn("Pass {} of {} not staged: {}", pass.info().id(), candidate.area(), e.getMessage());
            passes.stagingFailed(
                    candidate.area(), pass.info().id(), e.getMessage(), e.permanent() ? null : retry(candidate, now));
        } catch (IOException | RuntimeException e) {
            log.warn("Pass {} of {} not staged: {}", pass.info().id(), candidate.area(), e.toString());
            passes.stagingFailed(candidate.area(), pass.info().id(), e.toString(), retry(candidate, now));
        }
        return false;
    }

    /** Waits longer after every failed staging of the same pass, up to 2^5 times the base wait. */
    private Instant retry(StagingCandidate candidate, Instant now) {
        Duration base = settings.stagingRetry().dividedBy(1L << MAX_BACKOFF_STEPS);
        int step = Math.min(candidate.stagingAttempts(), MAX_BACKOFF_STEPS);
        return now.plus(base.multipliedBy(1L << step));
    }

    JobInputs.AssessPass inputs(Area area, NisarPass pass) throws IOException {
        List<GranuleWindow> windows = new ArrayList<>();
        for (GranuleRef granule : pass.granules()) {
            granules.read(granule, area.grid(), inputs).ifPresent(windows::add);
        }
        if (windows.isEmpty()) {
            throw new StagingException("no granule reaches the area", true);
        }
        LocalDate day = pass.info().date();
        return new JobInputs.AssessPass(
                area,
                pass.info(),
                windows,
                factory.gauge(area, day),
                occurrence.occurrence(area).orElse(null),
                sentinel1(area, day));
    }

    private List<Sentinel1Date> sentinel1(Area area, LocalDate day) throws IOException {
        Method.Sentinel1Method method = area.method().sentinel1();
        if (method == null) {
            return List.of();
        }
        List<Scene> scenes =
                sentinel1.search(area.grid().bbox(), day.minusDays(method.maxDays()), day.plusDays(method.maxDays()));
        Map<LocalDate, List<Scene>> byDate = new TreeMap<>();
        for (Scene scene : scenes) {
            byDate.computeIfAbsent(scene.date(), d -> new ArrayList<>()).add(scene);
        }
        Map<LocalDate, List<RasterRef>> tiles = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, List<Scene>> date : byDate.entrySet()) {
            for (Scene scene : date.getValue()) {
                sentinel1
                        .read(scene, area.grid(), inputs)
                        .ifPresent(t -> tiles.computeIfAbsent(date.getKey(), d -> new ArrayList<>())
                                .add(t));
            }
        }
        List<Sentinel1Date> dates = new ArrayList<>();
        tiles.forEach((date, refs) -> dates.add(new Sentinel1Date(date, refs)));
        return dates;
    }
}
