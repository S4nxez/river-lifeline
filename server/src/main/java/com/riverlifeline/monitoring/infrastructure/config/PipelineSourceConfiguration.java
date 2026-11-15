package com.riverlifeline.monitoring.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.monitoring.application.port.out.EarlyWarningRepository;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.AreaFileReader;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.JdbcPipelineReader;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineMonitoringRepository;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.RasterLayerImageRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code river.source=pipeline}: the worker's results as the pipeline stored them (MySQL and the raster volume), with
 * the areas of the area files. One adapter serves the catalog, the communities, the statuses and the river level.
 */
@Configuration
@ConditionalOnProperty(prefix = "river", name = "source", havingValue = "pipeline")
public class PipelineSourceConfiguration {
    private final ObjectMapper json = new ObjectMapper();

    @Bean
    AreaFileReader monitoredAreas() throws IOException {
        return AreaFileReader.fromClasspath(json);
    }

    @Bean
    PipelineMonitoringRepository pipelineMonitoringRepository(
            AreaFileReader areas, NamedParameterJdbcTemplate jdbc, Clock clock, RiverProperties properties) {
        return new PipelineMonitoringRepository(
                areas, new JdbcPipelineReader(jdbc, json), json, clock, properties.catalogCheckInterval());
    }

    /** The early warning is computed offline and published as a file: the pipeline has none yet (404). */
    @Bean
    EarlyWarningRepository earlyWarningRepository() {
        return aoiId -> Optional.empty();
    }

    @Bean
    RasterLayerImageRepository rasterLayerImageRepository(
            AreaFileReader areas, @Value("${river.pipeline.raster-dir}") String rasterDir) {
        return new RasterLayerImageRepository(Path.of(rasterDir), areas);
    }
}
