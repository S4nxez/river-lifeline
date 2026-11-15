package com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.earlywarning.EarlyWarningReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileSystemEarlyWarningRepositoryTest {
    static final AoiId TEFE = AoiId.of("tefe");
    static final String VALID = """
            {"schema_version": "1.0.0", "aoi_id": "tefe", "experimental": true, "method": {"summary": "s"},
             "issued_at": "2026-09-01", "communities": [], "hindcast": {"issues": []}}
            """;

    @TempDir
    Path root;

    FileSystemEarlyWarningRepository repository() {
        return new FileSystemEarlyWarningRepository(new DataDirectory(root), Clock.systemUTC(), Duration.ZERO);
    }

    void publish(String text) throws IOException {
        Path folder = root.resolve("aois").resolve("tefe");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(FileSystemEarlyWarningRepository.FILE), text);
    }

    @Test
    void servesThePublishedDocumentAsItIs() throws IOException {
        publish(VALID);

        EarlyWarningReport report = repository().find(TEFE).orElseThrow();

        assertThat(report.aoiId()).isEqualTo(TEFE);
        assertThat(report.schemaVersion().major()).isEqualTo(1);
        assertThat(report.json()).isEqualTo(VALID);
    }

    @Test
    void anAoiWithoutTheFileHasNoEarlyWarning() {
        assertThat(repository().find(TEFE)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not json",
                "[]",
                "{\"schema_version\": \"1.0.0\", \"aoi_id\": \"tefe\"}",
                "{\"schema_version\": \"1.0.0\", \"aoi_id\": \"other\", \"experimental\": true, \"method\": {},"
                        + " \"issued_at\": \"2026-09-01\", \"communities\": [], \"hindcast\": {}}",
                "{\"schema_version\": \"1.0.0\", \"aoi_id\": \"tefe\", \"experimental\": false, \"method\": {},"
                        + " \"issued_at\": \"2026-09-01\", \"communities\": [], \"hindcast\": {}}",
                "{\"schema_version\": \"2.0.0\", \"aoi_id\": \"tefe\", \"experimental\": true, \"method\": {},"
                        + " \"issued_at\": \"2026-09-01\", \"communities\": [], \"hindcast\": {}}",
                "{\"schema_version\": \"1.0.0\", \"aoi_id\": \"tefe\", \"experimental\": true, \"method\": {},"
                        + " \"issued_at\": \"2026-09-01\", \"communities\": {}, \"hindcast\": {}}"
            })
    void aMalformedFileMakesTheEarlyWarningUnavailable(String text) throws IOException {
        publish(text);

        assertThatThrownBy(() -> repository().find(TEFE)).isInstanceOf(CatalogUnavailableException.class);
    }
}
