package com.riverlifeline.pipeline.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code river.pipeline.*}: the internal worker API and the backend's work around it (api/worker-api.md).
 *
 * @param enabled false leaves the whole pipeline out (the public API still runs)
 * @param workerToken {@code WORKER_TOKEN}: the secret every worker request carries
 * @param rasterDir {@code RIVER_RASTER_DIR}: where raster bodies are kept
 * @param earthdataToken {@code EARTHDATA_TOKEN}: NASA Earthdata user token to read NISAR products
 * @param lease how long a worker holds a job without a heartbeat
 * @param maxAttempts hand-outs of a job before it is marked failed
 * @param stagedJobs most {@code assess_pass} jobs with inputs on disk at once
 * @param stagingRetry wait before trying again a pass whose staging or assessment failed
 * @param forecastHorizonDays horizon of the drought forecasts the backend queues
 * @param rasterGrace age before an unreferenced raster body may be deleted
 * @param userAgent sent to every external service
 * @param sources the external services' addresses
 */
@ConfigurationProperties("river.pipeline")
public record PipelineProperties(
        @DefaultValue("true") boolean enabled,
        String workerToken,
        @DefaultValue("./rasters") String rasterDir,
        String earthdataToken,
        @DefaultValue("15m") Duration lease,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("2") int stagedJobs,
        @DefaultValue("24h") Duration stagingRetry,
        @DefaultValue("30") int forecastHorizonDays,
        @DefaultValue("15m") Duration rasterGrace,
        @DefaultValue("river-lifeline-backend") String userAgent,
        @DefaultValue Sources sources) {

    public record Sources(
            @DefaultValue("https://api.daac.asf.alaska.edu/services/search/param")
            String asfSearch,

            @DefaultValue("https://planetarycomputer.microsoft.com/api/stac/v1/search")
            String stacSearch,

            @DefaultValue("https://planetarycomputer.microsoft.com/api/sas/v1/token/sentinel-1-rtc")
            String stacToken,

            @DefaultValue("http://telemetriaws1.ana.gov.br/ServiceANA.asmx/DadosHidrometeorologicos")
            String anaTelemetry,

            @DefaultValue("https://archive-api.open-meteo.com/v1/archive")
            String openMeteoArchive) {}
}
