package com.riverlifeline.pipeline.application.service;

import com.riverlifeline.pipeline.application.port.in.PipelineTasks;
import com.riverlifeline.pipeline.application.port.out.AreaCatalog;
import com.riverlifeline.pipeline.application.port.out.GaugeRepository;
import com.riverlifeline.pipeline.application.port.out.GaugeSource;
import com.riverlifeline.pipeline.application.port.out.PassRepository;
import com.riverlifeline.pipeline.application.port.out.PassRepository.StoredPass;
import com.riverlifeline.pipeline.application.port.out.PassSearch;
import com.riverlifeline.pipeline.application.port.out.ResultRepository;
import com.riverlifeline.pipeline.application.port.out.WeatherSource;
import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.pass.NisarPass;
import com.riverlifeline.pipeline.domain.model.source.DroughtModel;
import com.riverlifeline.pipeline.domain.model.source.GaugeDay;
import com.riverlifeline.pipeline.domain.model.source.WeatherDay;
import com.riverlifeline.pipeline.domain.service.DailyMedian;
import com.riverlifeline.pipeline.domain.service.GranuleChoice;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The backend's recurring work: each task runs area by area, and one area's failure never stops the others. */
public final class PipelineTaskService implements PipelineTasks {
    private static final Logger log = LoggerFactory.getLogger(PipelineTaskService.class);
    /** Days of gauge history kept before the first pass of an area. */
    static final int GAUGE_LOOKBACK_DAYS = 15;
    /** The longest range asked of the gauge service at once. */
    static final int GAUGE_CHUNK_DAYS = 60;
    /** How far back the gauge is read for an area with no pass and no stored day. */
    static final int GAUGE_FIRST_DAYS = 365;

    private final AreaCatalog areas;
    private final PassSearch search;
    private final PassRepository passes;
    private final GaugeSource gaugeSource;
    private final GaugeRepository gauges;
    private final WeatherSource weather;
    private final ResultRepository results;
    private final JobFactory factory;
    private final StagingService staging;
    private final Housekeeping housekeeping;
    private final Clock clock;

    public PipelineTaskService(
            AreaCatalog areas,
            PassSearch search,
            PassRepository passes,
            GaugeSource gaugeSource,
            GaugeRepository gauges,
            WeatherSource weather,
            ResultRepository results,
            JobFactory factory,
            StagingService staging,
            Housekeeping housekeeping,
            Clock clock) {
        this.areas = Objects.requireNonNull(areas, "areas");
        this.search = Objects.requireNonNull(search, "search");
        this.passes = Objects.requireNonNull(passes, "passes");
        this.gaugeSource = Objects.requireNonNull(gaugeSource, "gaugeSource");
        this.gauges = Objects.requireNonNull(gauges, "gauges");
        this.weather = Objects.requireNonNull(weather, "weather");
        this.results = Objects.requireNonNull(results, "results");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.staging = Objects.requireNonNull(staging, "staging");
        this.housekeeping = Objects.requireNonNull(housekeeping, "housekeeping");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void discoverPasses() {
        for (Area area : areas.all()) {
            try {
                List<NisarPass> found =
                        GranuleChoice.passes(search.granules(area.grid().bbox()));
                found.forEach(pass -> passes.saveDiscovered(area.id(), pass, clock.instant()));
                log.info("Pass search over {}: {} passes", area.id(), found.size());
            } catch (Exception e) {
                log.warn("Pass search over {} failed: {}", area.id(), e.toString());
            }
        }
    }

    @Override
    public void refreshGauges() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        for (Area area : areas.all()) {
            if (area.gauge() == null) {
                continue;
            }
            String station = area.gauge().code();
            try {
                LocalDate from = gauges.latestDay(station)
                        .map(d -> d.minusDays(2))
                        .or(() -> passes.earliestDate(area.id()).map(d -> d.minusDays(GAUGE_LOOKBACK_DAYS)))
                        .orElse(today.minusDays(GAUGE_FIRST_DAYS));
                int stored = 0;
                for (LocalDate start = from; !start.isAfter(today); start = start.plusDays(GAUGE_CHUNK_DAYS)) {
                    LocalDate end = start.plusDays(GAUGE_CHUNK_DAYS - 1L);
                    List<GaugeDay> days =
                            DailyMedian.of(gaugeSource.readings(station, start, end.isAfter(today) ? today : end));
                    gauges.save(station, days);
                    stored += days.size();
                }
                log.info("Gauge {} of {}: {} days refreshed since {}", station, area.id(), stored, from);
            } catch (Exception e) {
                log.warn("Gauge {} of {} not refreshed: {}", station, area.id(), e.toString());
            }
        }
    }

    @Override
    public void stageAssessments() {
        staging.stageAssessments();
    }

    @Override
    public void queueReevaluations() {
        for (Area area : areas.all()) {
            try {
                int queued = 0;
                for (StoredPass pass : passes.stale(area.id(), area.digest())) {
                    if (factory.reevaluate(area, pass)) {
                        queued++;
                    }
                }
                if (queued > 0) {
                    log.info("{}: {} passes queued for re-evaluation (the area files changed)", area.id(), queued);
                }
            } catch (Exception e) {
                log.warn("Re-evaluations of {} not queued: {}", area.id(), e.toString());
            }
        }
    }

    @Override
    public void queueForecasts() {
        LocalDate asOf = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1);
        for (Area area : areas.all()) {
            String region = area.id().value();
            try {
                Optional<DroughtModel> model = results.latestDroughtModel(region);
                if (model.isEmpty()) {
                    continue;
                }
                List<WeatherDay> recent = weather.daily(
                        area.hub().position(), asOf.minusDays(model.get().lookbackDays() - 1L), asOf);
                factory.forecast(region, asOf, model.get(), recent);
            } catch (Exception e) {
                log.warn("Drought forecast of {} not queued: {}", region, e.toString());
            }
        }
    }

    @Override
    public void expireLeases() {
        housekeeping.expireLeases();
    }

    @Override
    public void sweepRasters() {
        housekeeping.sweepRasters();
    }
}
