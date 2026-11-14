package com.riverlifeline.pipeline.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.pipeline.application.port.in.PipelineTasks;
import com.riverlifeline.pipeline.application.port.in.WorkerJobs;
import com.riverlifeline.pipeline.application.port.out.AreaCatalog;
import com.riverlifeline.pipeline.application.port.out.GaugeRepository;
import com.riverlifeline.pipeline.application.port.out.GaugeSource;
import com.riverlifeline.pipeline.application.port.out.GranuleWindows;
import com.riverlifeline.pipeline.application.port.out.JobInputsEncoder;
import com.riverlifeline.pipeline.application.port.out.JobRepository;
import com.riverlifeline.pipeline.application.port.out.JobResultDecoder;
import com.riverlifeline.pipeline.application.port.out.OccurrenceSource;
import com.riverlifeline.pipeline.application.port.out.PassRepository;
import com.riverlifeline.pipeline.application.port.out.PassSearch;
import com.riverlifeline.pipeline.application.port.out.RasterRepository;
import com.riverlifeline.pipeline.application.port.out.RasterStore;
import com.riverlifeline.pipeline.application.port.out.ResultRepository;
import com.riverlifeline.pipeline.application.port.out.Sentinel1Scenes;
import com.riverlifeline.pipeline.application.port.out.Transactions;
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
import com.riverlifeline.pipeline.domain.model.raster.RasterRole;
import com.riverlifeline.pipeline.infrastructure.adapter.in.schedule.PipelineScheduler;
import com.riverlifeline.pipeline.infrastructure.adapter.in.web.WorkerApiController;
import com.riverlifeline.pipeline.infrastructure.adapter.in.web.WorkerTokenFilter;
import com.riverlifeline.pipeline.infrastructure.adapter.out.ana.AnaGaugeSource;
import com.riverlifeline.pipeline.infrastructure.adapter.out.areafiles.AreaFiles;
import com.riverlifeline.pipeline.infrastructure.adapter.out.areafiles.PngOccurrenceSource;
import com.riverlifeline.pipeline.infrastructure.adapter.out.asf.AsfPassSearch;
import com.riverlifeline.pipeline.infrastructure.adapter.out.earthdata.GcovGranuleWindows;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.HttpCalls;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.HttpRangeChannel;
import com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc.JdbcGaugeRepository;
import com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc.JdbcJobRepository;
import com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc.JdbcPassRepository;
import com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc.JdbcRasterRepository;
import com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc.JdbcResultRepository;
import com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc.SpringTransactions;
import com.riverlifeline.pipeline.infrastructure.adapter.out.json.JacksonJobInputsEncoder;
import com.riverlifeline.pipeline.infrastructure.adapter.out.json.JacksonJobResultDecoder;
import com.riverlifeline.pipeline.infrastructure.adapter.out.openmeteo.OpenMeteoWeatherSource;
import com.riverlifeline.pipeline.infrastructure.adapter.out.planetarycomputer.PlanetaryComputerSentinel1;
import com.riverlifeline.pipeline.infrastructure.adapter.out.rasterstore.FileSystemRasterStore;
import com.riverlifeline.pipeline.infrastructure.json.ContractJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the pipeline's hexagon: the worker API, the job queue in MySQL, the raster store on disk and the external
 * sources. The contract's JSON mapper is private to it, so the public API keeps Spring's snake_case mapper.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "river.pipeline", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PipelineConfiguration {
    /** NISAR products are paged in 4 MB pages: a block is one page. */
    static final int GCOV_BLOCK = 4 << 20;

    static final int COG_BLOCK = 1 << 20;

    private final ObjectMapper contract = ContractJson.mapper();

    @Bean
    PipelineSettings pipelineSettings(PipelineProperties p) {
        return new PipelineSettings(
                p.lease(), p.maxAttempts(), p.stagedJobs(), p.stagingRetry(), p.forecastHorizonDays(), p.rasterGrace());
    }

    @Bean
    HttpCalls pipelineHttp(PipelineProperties p) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return new HttpCalls(client, p.userAgent(), Duration.ofMinutes(5));
    }

    /** Range reads follow redirects by hand (see {@link HttpRangeChannel}). */
    private static HttpClient rangeClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    AreaFiles areaFiles() throws IOException {
        return AreaFiles.fromClasspath(contract);
    }

    @Bean
    RasterStore rasterStore(PipelineProperties p) throws IOException {
        return new FileSystemRasterStore(Path.of(p.rasterDir()));
    }

    @Bean
    Transactions pipelineTransactions(TransactionTemplate template) {
        return new SpringTransactions(template);
    }

    @Bean
    JdbcRasterRepository rasterRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcRasterRepository(jdbc);
    }

    @Bean
    JdbcJobRepository jobRepository(NamedParameterJdbcTemplate jdbc, TransactionTemplate template) {
        return new JdbcJobRepository(jdbc, template);
    }

    @Bean
    JdbcPassRepository passRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcPassRepository(jdbc, contract);
    }

    @Bean
    JdbcResultRepository resultRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcResultRepository(jdbc, contract);
    }

    @Bean
    JdbcGaugeRepository gaugeRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcGaugeRepository(jdbc);
    }

    @Bean
    JobInputsEncoder jobInputsEncoder() {
        return new JacksonJobInputsEncoder(contract);
    }

    @Bean
    JobResultDecoder jobResultDecoder() {
        return new JacksonJobResultDecoder(contract);
    }

    @Bean
    GranuleWindows granuleWindows(PipelineProperties p) {
        HttpClient client = rangeClient();
        return new GcovGranuleWindows(
                p.earthdataToken(),
                (uri, token) -> new HttpRangeChannel(client, uri, token, p.userAgent(), GCOV_BLOCK, 8));
    }

    @Bean
    Sentinel1Scenes sentinel1Scenes(PipelineProperties p, HttpCalls http, Clock clock) {
        HttpClient client = rangeClient();
        return new PlanetaryComputerSentinel1(
                http,
                contract,
                URI.create(p.sources().stacSearch()),
                URI.create(p.sources().stacToken()),
                href -> new HttpRangeChannel(client, href, null, p.userAgent(), COG_BLOCK, 16),
                clock);
    }

    @Bean
    PassSearch passSearch(PipelineProperties p, HttpCalls http) {
        return new AsfPassSearch(http, contract, p.sources().asfSearch());
    }

    @Bean
    GaugeSource gaugeSource(PipelineProperties p, HttpCalls http) {
        return new AnaGaugeSource(http, p.sources().anaTelemetry());
    }

    @Bean
    WeatherSource weatherSource(PipelineProperties p, HttpCalls http) {
        return new OpenMeteoWeatherSource(http, contract, p.sources().openMeteoArchive());
    }

    @Bean
    OccurrenceSource occurrenceSource(AreaFiles files) {
        return new PngOccurrenceSource(files);
    }

    @Bean
    OccurrenceService occurrenceService(
            OccurrenceSource source, RasterRepository rasters, RasterStore store, Clock clock) {
        return new OccurrenceService(source, rasters, new RasterWriting(store, rasters, clock, RasterRole.OCCURRENCE));
    }

    @Bean
    JobFactory jobFactory(
            JobRepository jobs,
            JobInputsEncoder encoder,
            PassRepository passes,
            GaugeRepository gauges,
            OccurrenceService occurrence,
            PipelineSettings settings,
            Clock clock) {
        return new JobFactory(jobs, encoder, passes, gauges, occurrence, settings, clock);
    }

    @Bean
    FollowUps followUps(PassRepository passes, JobFactory factory) {
        return new FollowUps(passes, factory);
    }

    @Bean
    Housekeeping housekeeping(
            JobRepository jobs,
            PassRepository passes,
            RasterRepository rasters,
            RasterStore store,
            PipelineSettings settings,
            Clock clock) {
        return new Housekeeping(jobs, passes, rasters, store, settings, clock);
    }

    @Bean
    WorkerJobs workerJobs(
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
        return new WorkerJobService(
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
    }

    @Bean
    StagingService stagingService(
            AreaCatalog areas,
            PassRepository passes,
            JobRepository jobs,
            GranuleWindows granules,
            Sentinel1Scenes sentinel1,
            OccurrenceService occurrence,
            JobFactory factory,
            RasterStore store,
            RasterRepository rasters,
            PipelineSettings settings,
            Clock clock) {
        return new StagingService(
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
    }

    @Bean
    PipelineTasks pipelineTasks(
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
        return new PipelineTaskService(
                areas, search, passes, gaugeSource, gauges, weather, results, factory, staging, housekeeping, clock);
    }

    @Bean
    FilterRegistrationBean<WorkerTokenFilter> workerTokenFilter(PipelineProperties p) {
        FilterRegistrationBean<WorkerTokenFilter> registration =
                new FilterRegistrationBean<>(new WorkerTokenFilter(p.workerToken()));
        registration.addUrlPatterns(WorkerApiController.BASE + "/*");
        registration.setOrder(0);
        return registration;
    }

    /**
     * The recurring work, unless {@code river.pipeline.schedule.enabled=false}. A member class is registered on its own
     * conditions, so it repeats the pipeline's.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(
            prefix = "river.pipeline",
            name = {"enabled", "schedule.enabled"},
            havingValue = "true",
            matchIfMissing = true)
    static class Scheduling {
        @Bean
        PipelineScheduler pipelineScheduler(PipelineTasks tasks) {
            return new PipelineScheduler(tasks);
        }
    }
}
