package com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem;

import com.riverlifeline.monitoring.application.port.out.PassStatusRepository;
import com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.pass.PassId;
import com.riverlifeline.monitoring.domain.model.status.PassStatuses;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.StatusesDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads {@code passes/<pass_id>/statuses.json} lazily and keeps it: a pass folder never changes once it is in the
 * catalog. A reprocessed pass comes with a new method version or processing time, so it gets a new cache entry.
 */
public class FileSystemPassStatusRepository implements PassStatusRepository {
    private final DataDirectory directory;
    private final Map<Key, PassStatuses> cache = new ConcurrentHashMap<>();

    public FileSystemPassStatusRepository(DataDirectory directory) {
        this.directory = directory;
    }

    @Override
    public PassStatuses find(AoiId aoiId, Pass pass) {
        Key key = new Key(aoiId, pass.id(), pass.methodVersion(), pass.processedAt());
        return cache.computeIfAbsent(key, ignored -> read(aoiId, pass));
    }

    private PassStatuses read(AoiId aoiId, Pass pass) {
        Path file = directory.resolve(aoiId, pass.statusesPath());
        if (!Files.isRegularFile(file)) {
            throw new CatalogUnavailableException(aoiId, "the statuses of pass " + pass.id() + " are missing");
        }
        try {
            return ProcessingMapper.toPassStatuses(aoiId, pass, ProcessingJson.read(file, StatusesDocument.class));
        } catch (IOException | RuntimeException e) {
            throw new CatalogUnavailableException(
                    aoiId, "invalid statuses of pass " + pass.id() + ": " + e.getMessage(), e);
        }
    }

    private record Key(AoiId aoiId, PassId passId, String methodVersion, Instant processedAt) {}
}
