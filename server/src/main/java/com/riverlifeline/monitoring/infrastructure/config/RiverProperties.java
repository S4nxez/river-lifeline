package com.riverlifeline.monitoring.infrastructure.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code river.*} in application.properties. No area is configured here: every area, with its coordinates and
 * thresholds, comes from the files processing writes under {@code dataDir/aois/<id>/}.
 *
 * @param dataDir {@code RIVER_DATA_DIR}: the root the processing pipeline writes to
 * @param defaultAoi {@code DEFAULT_AOI}: the AOI that /health and /meta describe; blank means the first one found
 * @param catalogCheckInterval how often, at most, a catalog file's mtime is checked
 * @param corsOrigins {@code CORS_ORIGINS}: front origins allowed to call the API from another origin
 */
@ConfigurationProperties("river")
public record RiverProperties(
        String dataDir,
        String defaultAoiId,
        @DefaultValue("60s") Duration catalogCheckInterval,
        @DefaultValue List<String> corsOrigins) {

    public RiverProperties {
        if (dataDir == null || dataDir.isBlank()) {
            throw new IllegalArgumentException("river.data-dir (RIVER_DATA_DIR) is not set");
        }
        defaultAoiId = defaultAoiId == null || defaultAoiId.isBlank() ? null : defaultAoiId.strip();
        corsOrigins = corsOrigins == null
                ? List.of()
                : corsOrigins.stream()
                        .map(String::strip)
                        .filter(origin -> !origin.isEmpty())
                        .toList();
    }

    /** Relative paths resolve against the working directory. */
    public Path dataPath() {
        return Path.of(dataDir);
    }

    public Optional<String> defaultAoi() {
        return Optional.ofNullable(defaultAoiId);
    }
}
