package com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem;

import com.riverlifeline.monitoring.application.port.out.RiverLevelRepository;
import com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.riverlevel.RiverLevelSeries;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.RiverLevelDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/** Reads {@code aois/<id>/river_level.json}; an AOI without one simply has no river curve. */
public class FileSystemRiverLevelRepository implements RiverLevelRepository {
    private final DataDirectory directory;
    private final FileWatchCache<AoiId, Optional<RiverLevelSeries>> cache;

    public FileSystemRiverLevelRepository(DataDirectory directory, Clock clock, Duration checkInterval) {
        this.directory = directory;
        this.cache = new FileWatchCache<>(clock, checkInterval);
    }

    @Override
    public Optional<RiverLevelSeries> find(AoiId aoiId) {
        return cache.get(aoiId, directory.aoiFile(aoiId, DataDirectory.RIVER_LEVEL), file -> read(aoiId, file));
    }

    private static Optional<RiverLevelSeries> read(AoiId aoiId, Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(ProcessingMapper.toRiverLevel(ProcessingJson.read(file, RiverLevelDocument.class)));
        } catch (IOException | RuntimeException e) {
            throw new CatalogUnavailableException(
                    aoiId, "invalid " + DataDirectory.RIVER_LEVEL + ": " + e.getMessage(), e);
        }
    }
}
