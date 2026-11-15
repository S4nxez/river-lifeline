package com.riverlifeline.monitoring.infrastructure.config;

import com.riverlifeline.monitoring.application.port.in.AoiQueries;
import com.riverlifeline.monitoring.application.port.in.CommunityQueries;
import com.riverlifeline.monitoring.application.port.in.EarlyWarningQueries;
import com.riverlifeline.monitoring.application.port.in.PassQueries;
import com.riverlifeline.monitoring.application.port.in.SystemQueries;
import com.riverlifeline.monitoring.application.port.out.CatalogRepository;
import com.riverlifeline.monitoring.application.port.out.CommunityRepository;
import com.riverlifeline.monitoring.application.port.out.EarlyWarningRepository;
import com.riverlifeline.monitoring.application.port.out.LayerImageRepository;
import com.riverlifeline.monitoring.application.port.out.PassStatusRepository;
import com.riverlifeline.monitoring.application.port.out.RiverLevelRepository;
import com.riverlifeline.monitoring.application.service.AoiQueryService;
import com.riverlifeline.monitoring.application.service.CommunityQueryService;
import com.riverlifeline.monitoring.application.service.EarlyWarningQueryService;
import com.riverlifeline.monitoring.application.service.PassQueryService;
import com.riverlifeline.monitoring.application.service.SystemQueryService;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the hexagon: the application services (plain Java) get their ports from the adapters of the configured
 * source, {@code river.source}: the processing files ({@link FileSourceConfiguration}) or the worker's results
 * ({@link PipelineSourceConfiguration}).
 */
@Configuration
public class BeanConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EarlyWarningQueries earlyWarningQueries(EarlyWarningRepository earlyWarnings) {
        return new EarlyWarningQueryService(earlyWarnings);
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
