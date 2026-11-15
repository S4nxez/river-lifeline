package com.riverlifeline.monitoring.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineEarlyWarningTest {
    static final AoiId TEFE = AoiId.of("tefe");

    @TempDir
    Path root;

    @Test
    void withoutAFolderThePipelineHasNoEarlyWarning() {
        assertThat(PipelineSourceConfiguration.earlyWarnings("", Clock.systemUTC(), Duration.ZERO)
                        .find(TEFE))
                .isEmpty();
        assertThat(PipelineSourceConfiguration.earlyWarnings(null, Clock.systemUTC(), Duration.ZERO)
                        .find(TEFE))
                .isEmpty();
    }

    @Test
    void theEarlyWarningPublishedInTheFolderIsServed() throws IOException {
        Path folder = Files.createDirectories(root.resolve("aois").resolve("tefe"));
        Files.writeString(folder.resolve("early_warning.json"), """
                {"schema_version": "1.0.0", "aoi_id": "tefe", "experimental": true, "method": {},
                 "issued_at": "2026-09-01", "communities": [], "hindcast": {}}
                """);

        var repository = PipelineSourceConfiguration.earlyWarnings(root.toString(), Clock.systemUTC(), Duration.ZERO);

        assertThat(repository.find(TEFE)).isPresent();
        assertThat(repository.find(AoiId.of("other"))).isEmpty();
    }
}
