package com.riverlifeline.pipeline.application.service;

import com.riverlifeline.pipeline.application.port.out.GaugeRepository;
import com.riverlifeline.pipeline.application.port.out.JobInputsEncoder;
import com.riverlifeline.pipeline.application.port.out.JobRepository;
import com.riverlifeline.pipeline.application.port.out.JobRepository.NewJob;
import com.riverlifeline.pipeline.application.port.out.PassRepository;
import com.riverlifeline.pipeline.application.port.out.PassRepository.StoredPass;
import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.job.JobId;
import com.riverlifeline.pipeline.domain.model.job.JobInputs;
import com.riverlifeline.pipeline.domain.model.job.JobInputs.Gauge;
import com.riverlifeline.pipeline.domain.model.job.JobInputs.Sentinel1Classes;
import com.riverlifeline.pipeline.domain.model.job.JobSubject;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.source.DroughtModel;
import com.riverlifeline.pipeline.domain.model.source.GaugeDay;
import com.riverlifeline.pipeline.domain.model.source.WeatherDay;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Builds the inputs of every job type from what the backend stores and queues the job. */
public final class JobFactory {
    /** The contract version every job carries (api/worker-api.md). */
    public static final int CONTRACT_VERSION = 1;

    private final JobRepository jobs;
    private final JobInputsEncoder encoder;
    private final PassRepository passes;
    private final GaugeRepository gauges;
    private final OccurrenceService occurrence;
    private final PipelineSettings settings;
    private final Clock clock;

    public JobFactory(
            JobRepository jobs,
            JobInputsEncoder encoder,
            PassRepository passes,
            GaugeRepository gauges,
            OccurrenceService occurrence,
            PipelineSettings settings,
            Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.passes = Objects.requireNonNull(passes, "passes");
        this.gauges = Objects.requireNonNull(gauges, "gauges");
        this.occurrence = Objects.requireNonNull(occurrence, "occurrence");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Queues a job; false when the same job is already waiting. */
    public boolean submit(JobInputs inputs, AreaId area, String subject) {
        List<com.riverlifeline.pipeline.domain.model.raster.RasterId> rasters =
                inputs.rasters().stream().map(RasterRef::id).distinct().toList();
        return jobs.create(new NewJob(
                JobId.random(),
                inputs.type(),
                CONTRACT_VERSION,
                area,
                subject,
                encoder.encode(inputs),
                rasters,
                settings.maxAttempts(),
                clock.instant()));
    }

    /** The gauge days a pass job carries: the pass date ± (Sentinel-1 window + 1); null without gauge or days. */
    public Gauge gauge(Area area, LocalDate day) {
        if (area.gauge() == null) {
            return null;
        }
        int around = area.method().gaugeDays();
        List<GaugeDay> days = gauges.between(area.gauge().code(), day.minusDays(around), day.plusDays(around));
        return days.isEmpty() ? null : new Gauge(area.gauge().code(), days);
    }

    public boolean reevaluate(Area area, StoredPass pass) throws IOException {
        Sentinel1Classes sentinel1 = pass.sentinel1Classes() == null
                ? null
                : new Sentinel1Classes(pass.sentinel1Dates(), pass.sentinel1Classes());
        JobInputs inputs = new JobInputs.ReevaluatePass(
                area,
                pass.info(),
                pass.water(),
                sentinel1,
                gauge(area, pass.info().date()),
                occurrence.occurrence(area).orElse(null));
        return submit(inputs, area.id(), JobSubject.pass(area.id(), pass.info().id()));
    }

    /** Water lost and gained on {@code pass} since {@code since}, an earlier shown pass of the same track. */
    public boolean change(Area area, StoredPass since, StoredPass pass) {
        JobInputs inputs = new JobInputs.DetectChange(
                area.id(), area.grid(), pass.info().id(), since.info().id(), since.water(), pass.water());
        return submit(
                inputs,
                area.id(),
                JobSubject.change(area.id(), pass.info().id(), since.info().id()));
    }

    public boolean classify(Area area) {
        JobInputs inputs = new JobInputs.ClassifyCommunities(
                area.id(), area.method().accessRadiusM(), area.communityIds(), passes.evaluatedRoutes(area.id()));
        return submit(inputs, area.id(), JobSubject.area(area.id()));
    }

    public boolean forecast(String region, LocalDate asOf, DroughtModel model, List<WeatherDay> recent) {
        int horizon = settings.forecastHorizonDays();
        JobInputs inputs = new JobInputs.ForecastDrought(region, horizon, asOf, model, recent);
        return submit(inputs, null, JobSubject.forecast(region, asOf, horizon));
    }
}
