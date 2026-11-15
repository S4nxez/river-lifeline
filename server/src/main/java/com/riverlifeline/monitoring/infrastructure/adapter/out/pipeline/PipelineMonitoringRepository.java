package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.monitoring.application.port.out.CatalogRepository;
import com.riverlifeline.monitoring.application.port.out.CommunityRepository;
import com.riverlifeline.monitoring.application.port.out.PassStatusRepository;
import com.riverlifeline.monitoring.application.port.out.RiverLevelRepository;
import com.riverlifeline.monitoring.domain.exception.AoiNotFoundException;
import com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.catalog.Catalog;
import com.riverlifeline.monitoring.domain.model.community.Community;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.riverlevel.RiverLevelSeries;
import com.riverlifeline.monitoring.domain.model.status.PassStatuses;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineMapper.Snapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The public API's data from the worker's results: the areas come from the area files, the passes, statuses and
 * river levels from the pipeline's tables. An area's snapshot is read again at most every {@code refresh}, so the
 * page sees a new pass within that time and the database is not queried on every request.
 */
public class PipelineMonitoringRepository
        implements CatalogRepository, CommunityRepository, PassStatusRepository, RiverLevelRepository {
    private static final Logger log = LoggerFactory.getLogger(PipelineMonitoringRepository.class);

    private final AreaFileReader areas;
    private final PipelineReader reader;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration refresh;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Snapshot snapshot, Instant readAt) {}

    public PipelineMonitoringRepository(
            AreaFileReader areas, PipelineReader reader, ObjectMapper json, Clock clock, Duration refresh) {
        this.areas = areas;
        this.reader = reader;
        this.json = json;
        this.clock = clock;
        this.refresh = refresh;
    }

    @Override
    public List<AoiId> findAoiIds() {
        return areas.ids().stream().map(AoiId::of).toList();
    }

    @Override
    public Catalog load(AoiId aoiId) {
        return snapshot(aoiId).catalog();
    }

    @Override
    public List<Community> findAll(AoiId aoiId) {
        return snapshot(aoiId).communities();
    }

    @Override
    public PassStatuses find(AoiId aoiId, Pass pass) {
        PassStatuses statuses = snapshot(aoiId).statuses().get(pass.id());
        return statuses != null ? statuses : new PassStatuses(pass.id(), pass.acquiredAt(), false, List.of());
    }

    @Override
    public Optional<RiverLevelSeries> find(AoiId aoiId) {
        return snapshot(aoiId).riverLevel();
    }

    Snapshot snapshot(AoiId aoiId) {
        AreaFile area = areas.find(aoiId.value()).orElseThrow(() -> new AoiNotFoundException(aoiId.value()));
        Instant now = clock.instant();
        Cached cached = cache.get(area.id());
        if (cached != null && now.isBefore(cached.readAt().plus(refresh))) {
            return cached.snapshot();
        }
        try {
            Snapshot fresh = PipelineMapper.snapshot(
                    area,
                    reader.passes(area.id()),
                    reader.assessments(area.id()),
                    reader.communityClasses(area.id()),
                    area.gauge() == null ? List.of() : reader.gauge(area.gauge().stationCode()),
                    json);
            cache.put(area.id(), new Cached(fresh, now));
            return fresh;
        } catch (RuntimeException e) {
            if (cached != null) {
                log.warn("Results of {} not refreshed, serving the previous ones: {}", area.id(), e.getMessage());
                return cached.snapshot();
            }
            throw new CatalogUnavailableException(aoiId, "the worker's results can't be read", e);
        }
    }
}
