package com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem;

import com.riverlifeline.monitoring.application.port.out.CommunityRepository;
import com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.community.Community;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.CommunitiesDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Reads {@code aois/<id>/communities.geojson}, again whenever processing rewrites it (an OSM refresh). */
public class FileSystemCommunityRepository implements CommunityRepository {
    private final DataDirectory directory;
    private final FileWatchCache<AoiId, List<Community>> cache;

    public FileSystemCommunityRepository(DataDirectory directory, Clock clock, Duration checkInterval) {
        this.directory = directory;
        this.cache = new FileWatchCache<>(clock, checkInterval);
    }

    @Override
    public List<Community> findAll(AoiId aoiId) {
        return cache.get(aoiId, directory.aoiFile(aoiId, DataDirectory.COMMUNITIES), file -> read(aoiId, file));
    }

    private static List<Community> read(AoiId aoiId, Path file) {
        if (!Files.isRegularFile(file)) {
            throw new CatalogUnavailableException(aoiId, DataDirectory.COMMUNITIES + " is missing");
        }
        try {
            return ProcessingMapper.toCommunities(aoiId, ProcessingJson.read(file, CommunitiesDocument.class));
        } catch (IOException | RuntimeException e) {
            throw new CatalogUnavailableException(
                    aoiId, "invalid " + DataDirectory.COMMUNITIES + ": " + e.getMessage(), e);
        }
    }
}
