package com.riverlifeline.monitoring.infrastructure.config;

import com.riverlifeline.monitoring.application.port.in.AoiQueries;
import com.riverlifeline.monitoring.application.port.in.CommunityQueries;
import com.riverlifeline.monitoring.application.port.in.PassQueries;
import com.riverlifeline.monitoring.application.port.in.SystemQueries;
import com.riverlifeline.monitoring.application.port.out.CatalogRepository;
import com.riverlifeline.monitoring.application.port.out.CommunityRepository;
import com.riverlifeline.monitoring.application.port.out.LayerImageRepository;
import com.riverlifeline.monitoring.application.port.out.PassStatusRepository;
import com.riverlifeline.monitoring.application.port.out.RiverLevelRepository;
import com.riverlifeline.monitoring.application.service.AoiQueryService;
import com.riverlifeline.monitoring.application.service.CommunityQueryService;
import com.riverlifeline.monitoring.application.service.PassQueryService;
import com.riverlifeline.monitoring.application.service.SystemQueryService;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.DataDirectory;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.FileSystemCatalogRepository;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.FileSystemCommunityRepository;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.FileSystemLayerImageRepository;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.FileSystemPassStatusRepository;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.FileSystemRiverLevelRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the hexagon: the application services (plain Java) get the file system adapters as their ports. */
@Configuration
public class BeanConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

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
    LayerImageRepository layerImageRepository(DataDirectory directory) {
        return new FileSystemLayerImageRepository(directory);
    }

    @Bean
    SystemQueries systemQueries(CatalogRepository catalogs, RiverProperties properties) {
        return new SystemQueryService(catalogs, properties.defaultAoi().map(AoiId::of));
    }

    @Bean
    AoiQueries aoiQueries(CatalogRepository catalogs, RiverLevelRepository riverLevels) {
        return new AoiQueryService(catalogs, riverLevels);
    }

    @Bean
    PassQueries passQueries(CatalogRepository catalogs, LayerImageRepository layerImages) {
        return new PassQueryService(catalogs, layerImages);
    }

    @Bean
    CommunityQueries communityQueries(
            CatalogRepository catalogs, CommunityRepository communities, PassStatusRepository statuses) {
        return new CommunityQueryService(catalogs, communities, statuses);
    }
}
