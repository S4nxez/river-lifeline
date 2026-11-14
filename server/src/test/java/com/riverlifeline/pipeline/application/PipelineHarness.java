package com.riverlifeline.pipeline.application;

import com.riverlifeline.pipeline.PipelineFakes;
import com.riverlifeline.pipeline.PipelineTestData;
import com.riverlifeline.pipeline.application.port.out.GaugeSource;
import com.riverlifeline.pipeline.application.port.out.GranuleWindows;
import com.riverlifeline.pipeline.application.port.out.PassSearch;
import com.riverlifeline.pipeline.application.port.out.RasterSink;
import com.riverlifeline.pipeline.application.port.out.Sentinel1Scenes;
import com.riverlifeline.pipeline.application.port.out.WeatherSource;
import com.riverlifeline.pipeline.application.service.FollowUps;
import com.riverlifeline.pipeline.application.service.Housekeeping;
import com.riverlifeline.pipeline.application.service.JobFactory;
import com.riverlifeline.pipeline.application.service.OccurrenceService;
import com.riverlifeline.pipeline.application.service.PipelineSettings;
import com.riverlifeline.pipeline.application.service.PipelineTaskService;
import com.riverlifeline.pipeline.application.service.RasterWriting;
import com.riverlifeline.pipeline.application.service.StagingService;
import com.riverlifeline.pipeline.application.service.WorkerJobService;
import com.riverlifeline.pipeline.domain.model.area.AreaGrid;
import com.riverlifeline.pipeline.domain.model.area.Bbox;
import com.riverlifeline.pipeline.domain.model.job.JobInputs.GranuleWindow;
import com.riverlifeline.pipeline.domain.model.pass.GranuleRef;
import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.raster.RasterRole;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import com.riverlifeline.pipeline.domain.model.raster.XY;
import com.riverlifeline.pipeline.domain.model.source.GaugeReading;
import com.riverlifeline.pipeline.domain.model.source.WeatherDay;
import com.riverlifeline.shared.domain.GeoPoint;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The application services wired to in-memory ports, with a clock the tests move. */
public class PipelineHarness {
    public static final Instant START = Instant.parse("2026-11-14T12:00:00Z");

    public final MutableClock clock = new MutableClock(START);
    public final PipelineFakes.Areas areas = new PipelineFakes.Areas(PipelineTestData.area());
    public final PipelineFakes.Jobs jobs = new PipelineFakes.Jobs();
    public final PipelineFakes.Store store = new PipelineFakes.Store();
    public final PipelineFakes.Rasters rasters = new PipelineFakes.Rasters();
    public final PipelineFakes.Passes passes = new PipelineFakes.Passes();
    public final PipelineFakes.Results results = new PipelineFakes.Results();
    public final PipelineFakes.Gauges gauges = new PipelineFakes.Gauges();
    public final PipelineFakes.Encoder encoder = new PipelineFakes.Encoder();
    public final PipelineFakes.Decoder decoder = new PipelineFakes.Decoder();
    public final PipelineFakes.NoTransactions transactions = new PipelineFakes.NoTransactions();
    public final PipelineFakes.Occurrence occurrenceSource = new PipelineFakes.Occurrence();
    public final FakeGranules granules = new FakeGranules();
    public final FakeSentinel1 sentinel1 = new FakeSentinel1();
    public final List<GranuleRef> found = new ArrayList<>();
    public final List<GaugeReading> readings = new ArrayList<>();
    public final List<LocalDate[]> gaugeCalls = new ArrayList<>();
    public final List<WeatherDay> weatherDays = new ArrayList<>();
    public final PipelineSettings settings =
            new PipelineSettings(Duration.ofMinutes(15), 2, 2, Duration.ofHours(24), 30, Duration.ofMinutes(15));

    public final OccurrenceService occurrence;
    public final JobFactory factory;
    public final FollowUps followUps;
    public final Housekeeping housekeeping;
    public final WorkerJobService worker;
    public final StagingService staging;
    public final PipelineTaskService tasks;

    public PipelineHarness() {
        rasters.jobs = jobs;
        passes.rasters = rasters;
        passes.jobs = jobs;
        occurrence = new OccurrenceService(
                occurrenceSource, rasters, new RasterWriting(store, rasters, clock, RasterRole.OCCURRENCE));
        factory = new JobFactory(jobs, encoder, passes, gauges, occurrence, settings, clock);
        followUps = new FollowUps(passes, factory);
        housekeeping = new Housekeeping(jobs, passes, rasters, store, settings, clock);
        worker = new WorkerJobService(
                jobs,
                rasters,
                store,
                areas,
                passes,
                results,
                decoder,
                followUps,
                housekeeping,
                transactions,
                settings,
                clock);
        staging = new StagingService(
                areas,
                passes,
                jobs,
                granules,
                sentinel1,
                occurrence,
                factory,
                new RasterWriting(store, rasters, clock, RasterRole.INPUT),
                settings,
                clock);
        PassSearch search = bbox -> List.copyOf(found);
        GaugeSource gaugeSource = (station, from, to) -> {
            gaugeCalls.add(new LocalDate[] {from, to});
            return readings.stream()
                    .filter(r -> !r.day().isBefore(from) && !r.day().isAfter(to))
                    .toList();
        };
        WeatherSource weather = (at, from, to) -> weatherDays.stream()
                .filter(d -> !d.day().isBefore(from) && !d.day().isAfter(to))
                .toList();
        tasks = new PipelineTaskService(
                areas, search, passes, gaugeSource, gauges, weather, results, factory, staging, housekeeping, clock);
    }

    /** NISAR windows: a small float32 window per granule, HV on dual-polarisation ones; some granules miss. */
    public static class FakeGranules implements GranuleWindows {
        public boolean available = true;
        public final Set<String> missing = new HashSet<>();
        public final List<String> read = new ArrayList<>();

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Optional<GranuleWindow> read(GranuleRef granule, AreaGrid grid, RasterSink sink) throws IOException {
            read.add(granule.name());
            if (missing.contains(granule.name())) {
                return Optional.empty();
            }
            RasterMeta meta = new RasterMeta(
                    3,
                    2,
                    DataType.FLOAT32,
                    NoData.NAN,
                    RasterUnit.LINEAR_POWER,
                    "EPSG:32720",
                    new XY(1, 2),
                    new XY(20, -20));
            RasterRef hh = sink.write(meta, out -> out.write(new byte[24]));
            RasterRef hv =
                    granule.polarization().startsWith("D") ? sink.write(meta, out -> out.write(new byte[24])) : null;
            return Optional.of(new GranuleWindow(granule.name(), granule.frame(), hh, hv));
        }
    }

    /** Sentinel-1 scenes on given dates; a scene whose id starts with "off" misses the area. */
    public static class FakeSentinel1 implements Sentinel1Scenes {
        public final List<Scene> scenes = new ArrayList<>();
        public final List<LocalDate[]> searches = new ArrayList<>();
        public RuntimeException fail;

        @Override
        public List<Scene> search(Bbox bbox, LocalDate from, LocalDate to) {
            if (fail != null) {
                throw fail;
            }
            searches.add(new LocalDate[] {from, to});
            return scenes.stream()
                    .filter(s -> !s.date().isBefore(from) && !s.date().isAfter(to))
                    .toList();
        }

        @Override
        public Optional<RasterRef> read(Scene scene, AreaGrid grid, RasterSink sink) throws IOException {
            if (scene.id().startsWith("off")) {
                return Optional.empty();
            }
            RasterMeta meta = new RasterMeta(
                    2,
                    2,
                    DataType.FLOAT32,
                    NoData.value(-32768),
                    RasterUnit.LINEAR_POWER,
                    "EPSG:32720",
                    new XY(1, 2),
                    new XY(20, -20));
            return Optional.of(sink.write(meta, out -> out.write(new byte[16])));
        }
    }

    public static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    public static GeoPoint anywhere() {
        return new GeoPoint(0, 0);
    }
}
