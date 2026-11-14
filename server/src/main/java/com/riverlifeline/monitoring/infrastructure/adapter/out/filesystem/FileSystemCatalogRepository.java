package com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.riverlifeline.monitoring.application.port.out.CatalogRepository;
import com.riverlifeline.monitoring.domain.exception.AoiNotFoundException;
import com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException;
import com.riverlifeline.monitoring.domain.exception.MonitoringException;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.catalog.Catalog;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.CatalogDocument;
import com.riverlifeline.shared.domain.SchemaVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads {@code aois/<id>/catalog.json}. It checks the file's mtime at most once per interval and parses it again only
 * when it changed; processing replaces it atomically (write {@code .tmp}, then rename), so a read never sees half a
 * file. The schema major is checked before anything else is parsed.
 */
public class FileSystemCatalogRepository implements CatalogRepository {
    private static final Logger log = LoggerFactory.getLogger(FileSystemCatalogRepository.class);

    private final DataDirectory directory;
    private final FileWatchCache<AoiId, Catalog> cache;

    public FileSystemCatalogRepository(DataDirectory directory, Clock clock, Duration checkInterval) {
        this.directory = directory;
        this.cache = new FileWatchCache<>(clock, checkInterval);
    }

    @Override
    public List<AoiId> findAoiIds() {
        return directory.aoiIds();
    }

    @Override
    public Catalog load(AoiId aoiId) {
        if (!Files.isDirectory(directory.aoiFolder(aoiId))) {
            throw new AoiNotFoundException(aoiId.value());
        }
        return cache.get(aoiId, directory.aoiFile(aoiId, DataDirectory.CATALOG), file -> read(aoiId, file));
    }

    private Catalog read(AoiId aoiId, Path file) {
        if (!Files.isRegularFile(file)) {
            throw new CatalogUnavailableException(aoiId, DataDirectory.CATALOG + " is missing");
        }
        try {
            JsonNode tree = ProcessingJson.readTree(file);
            SchemaVersion version = schemaVersionOf(aoiId, tree);
            Catalog.requireSupported(aoiId, version);
            CatalogDocument document = ProcessingJson.convert(tree, CatalogDocument.class);
            Catalog catalog = ProcessingMapper.toCatalog(aoiId, version, document);
            log.info(
                    "Loaded the catalog of {}: schema {}, generated at {}, {} passes",
                    aoiId,
                    version,
                    catalog.generatedAt(),
                    catalog.passes().size());
            return catalog;
        } catch (MonitoringException e) {
            log.warn("{}", e.getMessage());
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("Can't read the catalog of {}: {}", aoiId, e.getMessage());
            throw new CatalogUnavailableException(aoiId, "invalid " + DataDirectory.CATALOG + ": " + e.getMessage(), e);
        }
    }

    private static SchemaVersion schemaVersionOf(AoiId aoiId, JsonNode tree) {
        JsonNode value = tree.get("schema_version");
        if (value == null || !value.isTextual()) {
            throw new CatalogUnavailableException(aoiId, DataDirectory.CATALOG + " has no schema_version");
        }
        try {
            return SchemaVersion.parse(value.asText());
        } catch (IllegalArgumentException e) {
            throw new CatalogUnavailableException(aoiId, "invalid schema_version '" + value.asText() + "'", e);
        }
    }
}
