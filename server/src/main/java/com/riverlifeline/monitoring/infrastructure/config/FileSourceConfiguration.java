package com.riverlifeline.monitoring.infrastructure.config;

import com.riverlifeline.monitoring.application.port.out.CatalogRepository;
import com.riverlifeline.monitoring.application.port.out.CommunityRepository;
import com.riverlifeline.monitoring.application.port.out.EarlyWarningRepository;
import com.riverlifeline.monitoring.application.port.out.LayerImageRepository;
import com.riverlifeline.monitoring.application.port.out.PassStatusRepository;
import com.riverlifeline.monitoring.application.port.out.RiverLevelRepository;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.DataDirectory;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.FileSystemCatalogRepository;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.FileSystemCommunityRepository;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.FileSystemEarlyWarningRepository;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.FileSystemLayerImageRepository;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.FileSystemPassStatusRepository;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.FileSystemRiverLevelRepository;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** {@code river.source=files} (the default): the files processing writes under {@code RIVER_DATA_DIR}. */
@Configuration
@ConditionalOnProperty(prefix = "river", name = "source", havingValue = "files", matchIfMissing = true)
public class FileSourceConfiguration {

    @Bean
    DataDirectory dataDirectory(RiverProperties properties) {
        return new DataDirectory(properties.dataPath());
    }

    @Bean
    CatalogRepository catalogRepository(DataDirectory directory, Clock clock, RiverProperties properties) {
        return new FileSystemCatalogRepository(directory, clock, properties.catalogCheckInterval());
    }

    @Bean
    CommunityRepository communityRepository(DataDirectory directory, Clock clock, RiverProperties properties) {
        return new FileSystemCommunityRepository(directory, clock, properties.catalogCheckInterval());
    }

    @Bean
    PassStatusRepository passStatusRepository(DataDirectory directory) {
        return new FileSystemPassStatusRepository(directory);
    }

    @Bean
    RiverLevelRepository riverLevelRepository(DataDirectory directory, Clock clock, RiverProperties properties) {
        return new FileSystemRiverLevelRepository(directory, clock, properties.catalogCheckInterval());
    }

    @Bean
    EarlyWarningRepository earlyWarningRepository(DataDirectory directory, Clock clock, RiverProperties properties) {
        return new FileSystemEarlyWarningRepository(directory, clock, properties.catalogCheckInterval());
    }

    @Bean
    LayerImageRepository layerImageRepository(DataDirectory directory) {
        return new FileSystemLayerImageRepository(directory);
    }
}
