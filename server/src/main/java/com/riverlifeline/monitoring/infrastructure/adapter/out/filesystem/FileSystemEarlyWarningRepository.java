package com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.monitoring.application.port.out.EarlyWarningRepository;
import com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.earlywarning.EarlyWarningReport;
import com.riverlifeline.shared.domain.SchemaVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Reads {@code aois/<id>/early_warning.json}: an AOI without one has no early warning. The document is checked for
 * the contract's required fields and served as published.
 */
public class FileSystemEarlyWarningRepository implements EarlyWarningRepository {
    public static final String FILE = "early_warning.json";
    private static final List<String> REQUIRED =
            List.of("schema_version", "aoi_id", "experimental", "method", "issued_at", "communities", "hindcast");

    private final DataDirectory directory;
    private final FileWatchCache<AoiId, Optional<EarlyWarningReport>> cache;
    private final ObjectMapper json = new ObjectMapper();

    public FileSystemEarlyWarningRepository(DataDirectory directory, Clock clock, Duration checkInterval) {
        this.directory = directory;
        this.cache = new FileWatchCache<>(clock, checkInterval);
    }

    @Override
    public Optional<EarlyWarningReport> find(AoiId aoiId) {
        return cache.get(aoiId, directory.aoiFile(aoiId, FILE), file -> read(aoiId, file));
    }

    private Optional<EarlyWarningReport> read(AoiId aoiId, Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode root = json.readTree(text);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("not a JSON object");
            }
            for (String field : REQUIRED) {
                if (!root.hasNonNull(field)) {
                    throw new IllegalArgumentException(field + " is missing");
                }
            }
            if (!root.get("aoi_id").asText().equals(aoiId.value())) {
                throw new IllegalArgumentException("aoi_id is not " + aoiId);
            }
            if (!root.get("experimental").asBoolean(false)) {
                throw new IllegalArgumentException("an early warning must say it is experimental");
            }
            if (!root.get("communities").isArray()) {
                throw new IllegalArgumentException("communities must be an array");
            }
            return Optional.of(new EarlyWarningReport(
                    aoiId, SchemaVersion.parse(root.get("schema_version").asText()), text));
        } catch (IOException | RuntimeException e) {
            throw new CatalogUnavailableException(aoiId, "invalid " + FILE + ": " + e.getMessage(), e);
        }
    }
}
