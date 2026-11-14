package com.riverlifeline.monitoring.application.service;

import com.riverlifeline.monitoring.application.port.out.BinaryContent;
import com.riverlifeline.monitoring.application.port.out.CatalogRepository;
import com.riverlifeline.monitoring.application.port.out.CommunityRepository;
import com.riverlifeline.monitoring.application.port.out.LayerImageRepository;
import com.riverlifeline.monitoring.application.port.out.PassStatusRepository;
import com.riverlifeline.monitoring.application.port.out.RiverLevelRepository;
import com.riverlifeline.monitoring.domain.exception.AoiNotFoundException;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.catalog.Catalog;
import com.riverlifeline.monitoring.domain.model.community.Community;
import com.riverlifeline.monitoring.domain.model.pass.LayerImage;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.pass.PassId;
import com.riverlifeline.monitoring.domain.model.riverlevel.RiverLevelSeries;
import com.riverlifeline.monitoring.domain.model.status.CommunityAssessment;
import com.riverlifeline.monitoring.domain.model.status.PassStatuses;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** In-memory fakes of every outbound port, for the application tests. */
final class InMemoryRepositories
        implements CatalogRepository,
                CommunityRepository,
                PassStatusRepository,
                RiverLevelRepository,
                LayerImageRepository {
    private final Map<AoiId, Catalog> catalogs =
            new TreeMap<>((a, b) -> a.value().compareTo(b.value()));
    private final Map<AoiId, RuntimeException> failures = new HashMap<>();
    private final Map<AoiId, List<Community>> communities = new HashMap<>();
    private final Map<PassId, List<CommunityAssessment>> statuses = new HashMap<>();
    private final Map<AoiId, RiverLevelSeries> riverLevels = new HashMap<>();
    private final Set<String> layerFiles = new HashSet<>();

    InMemoryRepositories withCatalog(Catalog catalog) {
        catalogs.put(catalog.aoiId(), catalog);
        return this;
    }

    InMemoryRepositories failing(AoiId aoiId, RuntimeException failure) {
        failures.put(aoiId, failure);
        return this;
    }

    InMemoryRepositories withCommunities(AoiId aoiId, Community... list) {
        communities.put(aoiId, List.of(list));
        return this;
    }

    InMemoryRepositories withStatuses(Pass pass, CommunityAssessment... list) {
        statuses.put(pass.id(), List.of(list));
        return this;
    }

    InMemoryRepositories withRiverLevel(AoiId aoiId, RiverLevelSeries series) {
        riverLevels.put(aoiId, series);
        return this;
    }

    InMemoryRepositories withLayerFile(String path) {
        layerFiles.add(path);
        return this;
    }

    @Override
    public List<AoiId> findAoiIds() {
        List<AoiId> ids = new ArrayList<>(catalogs.keySet());
        ids.addAll(failures.keySet());
        return ids.stream()
                .distinct()
                .sorted((a, b) -> a.value().compareTo(b.value()))
                .toList();
    }

    @Override
    public Catalog load(AoiId aoiId) {
        if (failures.containsKey(aoiId)) {
            throw failures.get(aoiId);
        }
        Catalog catalog = catalogs.get(aoiId);
        if (catalog == null) {
            throw new AoiNotFoundException(aoiId.value());
        }
        return catalog;
    }

    @Override
    public List<Community> findAll(AoiId aoiId) {
        return communities.getOrDefault(aoiId, List.of());
    }

    @Override
    public PassStatuses find(AoiId aoiId, Pass pass) {
        return new PassStatuses(
                pass.id(), pass.acquiredAt(), pass.evaluated(), statuses.getOrDefault(pass.id(), List.of()));
    }

    @Override
    public Optional<RiverLevelSeries> find(AoiId aoiId) {
        return Optional.ofNullable(riverLevels.get(aoiId));
    }

    @Override
    public Optional<BinaryContent> open(AoiId aoiId, LayerImage image) {
        if (!layerFiles.contains(image.path())) {
            return Optional.empty();
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        return Optional.of(new BinaryContent(png.length, () -> new ByteArrayInputStream(png)));
    }
}
