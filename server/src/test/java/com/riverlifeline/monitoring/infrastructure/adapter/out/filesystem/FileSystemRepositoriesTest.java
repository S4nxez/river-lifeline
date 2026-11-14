package com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riverlifeline.monitoring.domain.exception.AoiNotFoundException;
import com.riverlifeline.monitoring.domain.exception.CatalogSchemaUnsupportedException;
import com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.catalog.Catalog;
import com.riverlifeline.monitoring.domain.model.community.BoardingPointSource;
import com.riverlifeline.monitoring.domain.model.community.Community;
import com.riverlifeline.monitoring.domain.model.community.CommunityId;
import com.riverlifeline.monitoring.domain.model.pass.LayerId;
import com.riverlifeline.monitoring.domain.model.pass.LayerImage;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.pass.PassSelector;
import com.riverlifeline.monitoring.domain.model.status.PassStatuses;
import com.riverlifeline.monitoring.domain.model.status.Reason;
import com.riverlifeline.monitoring.domain.model.status.Status;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file adapters on a copy of the repository's fixtures (CONTRACTS.md section 2), so they can be altered. */
class FileSystemRepositoriesTest {
    static final Path FIXTURES = Path.of("..", "fixtures", "data");
    static final AoiId TEFE = AoiId.of("tefe");

    @TempDir
    Path root;

    private DataDirectory directory;
    private Path catalogFile;

    @BeforeEach
    void copyFixtures() throws IOException {
        try (Stream<Path> files = Files.walk(FIXTURES)) {
            for (Path source : files.toList()) {
                Path target = root.resolve(FIXTURES.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        directory = new DataDirectory(root);
        catalogFile = directory.aoiFile(TEFE, DataDirectory.CATALOG);
    }

    private FileSystemCatalogRepository catalogs(Duration interval) {
        return new FileSystemCatalogRepository(directory, Clock.systemUTC(), interval);
    }

    private void rewriteCatalog(String from, String to) throws IOException {
        String content = Files.readString(catalogFile).replace(from, to);
        Files.writeString(catalogFile, content);
        // Make sure the change is seen even on file systems with a coarse mtime
        Files.setLastModifiedTime(catalogFile, FileTime.from(Instant.now().plusSeconds(5)));
    }

    @Test
    void theFixtureCatalogIsReadAsTheContractDescribesIt() {
        Catalog catalog = catalogs(Duration.ZERO).load(TEFE);

        assertThat(catalog.aoi().name()).isEqualTo("Tefé");
        assertThat(catalog.aoi().parameters().connectedMinWidthM()).isEqualTo(60);
        assertThat(catalog.aoi().parameters().testedWidthsM()).containsExactly(40, 60, 100, 200, 400);
        assertThat(catalog.passes()).hasSize(4);
        assertThat(catalog.evaluatedPasses()).hasSize(3);
        Pass partial = catalog.passes().get(2);
        assertThat(partial.evaluated()).isFalse();
        assertThat(partial.counts().connected()).isNull();
        assertThat(partial.layer(LayerId.FLOODED_VEGETATION))
                .as("an HH-only pass")
                .isEmpty();
        assertThat(catalog.latestEvaluatedPass().orElseThrow().counts().isolated())
                .isEqualTo(2);
    }

    @Test
    void areasAreDiscoveredFromTheFoldersProcessingWrites() throws IOException {
        assertThat(directory.aoiIds()).containsExactly(TEFE);

        Files.createDirectories(root.resolve("aois/coari"));
        Files.writeString(root.resolve("aois/coari/catalog.json"), "{}");
        Files.createDirectories(root.resolve("aois/without-catalog"));

        assertThat(directory.aoiIds()).containsExactly(AoiId.of("coari"), TEFE);
    }

    @Test
    void anAoiWithoutAFolderIsNotFoundAndOneWithoutACatalogIsUnavailable() throws IOException {
        assertThatThrownBy(() -> catalogs(Duration.ZERO).load(AoiId.of("coari")))
                .isInstanceOf(AoiNotFoundException.class);

        Files.delete(catalogFile);
        assertThatThrownBy(() -> catalogs(Duration.ZERO).load(TEFE))
                .isInstanceOf(CatalogUnavailableException.class)
                .hasMessageContaining("catalog.json is missing");
    }

    @Test
    void anotherSchemaMajorIsRefusedBeforeAnythingElseIsParsed() throws IOException {
        Files.writeString(catalogFile, "{\"schema_version\": \"2.0.0\", \"aoi\": \"a shape from the future\"}");

        assertThatThrownBy(() -> catalogs(Duration.ZERO).load(TEFE))
                .isInstanceOf(CatalogSchemaUnsupportedException.class);
    }

    @Test
    void aBrokenCatalogIsUnavailable() throws IOException {
        Files.writeString(catalogFile, "{\"schema_version\": \"1.0.0\", \"aoi\": ");

        assertThatThrownBy(() -> catalogs(Duration.ZERO).load(TEFE)).isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void aCatalogWhoseAoiIdIsNotItsFolderIsUnavailable() throws IOException {
        rewriteCatalog("\"id\": \"tefe\"", "\"id\": \"coari\"");

        assertThatThrownBy(() -> catalogs(Duration.ZERO).load(TEFE))
                .isInstanceOf(CatalogUnavailableException.class)
                .hasMessageContaining("doesn't match its folder");
    }

    @Test
    void theCatalogIsReloadedWhenItsFileChanges() throws IOException {
        FileSystemCatalogRepository repository = catalogs(Duration.ZERO);
        Catalog before = repository.load(TEFE);
        assertThat(repository.load(TEFE)).isSameAs(before);

        rewriteCatalog("\"name\": \"Tefé\"", "\"name\": \"Tefé (reprocessed)\"");

        assertThat(repository.load(TEFE).aoi().name()).isEqualTo("Tefé (reprocessed)");
    }

    @Test
    void theFileIsCheckedAtMostOncePerInterval() throws IOException {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
        var repository = new FileSystemCatalogRepository(directory, clock, Duration.ofSeconds(60));
        repository.load(TEFE);

        rewriteCatalog("\"name\": \"Tefé\"", "\"name\": \"Changed\"");
        clock.advance(Duration.ofSeconds(30));
        assertThat(repository.load(TEFE).aoi().name()).isEqualTo("Tefé");

        clock.advance(Duration.ofSeconds(31));
        assertThat(repository.load(TEFE).aoi().name()).isEqualTo("Changed");
    }

    @Test
    void communitiesKeepTheBoardingPointAndUnmonitoredOnes() {
        List<Community> communities =
                new FileSystemCommunityRepository(directory, Clock.systemUTC(), Duration.ZERO).findAll(TEFE);

        assertThat(communities).hasSize(12);
        assertThat(communities).filteredOn(Community::monitored).hasSize(10);
        Community alvaraes = communities.stream()
                .filter(c -> c.id().equals(CommunityId.of("osm-node-2984471123")))
                .findFirst()
                .orElseThrow();
        assertThat(alvaraes.boardingPoint().source()).isEqualTo(BoardingPointSource.OSM_PORT);
        assertThat(alvaraes.boardingPoint().distanceFromCenterM()).isEqualTo(1260);
        assertThat(alvaraes.measurementPoint())
                .isEqualTo(alvaraes.boardingPoint().location());
    }

    @Test
    void statusesAreReadPerPass() {
        Catalog catalog = catalogs(Duration.ZERO).load(TEFE);
        var repository = new FileSystemPassStatusRepository(directory);
        Pass latest = catalog.require(PassSelector.LATEST);

        PassStatuses statuses = repository.find(TEFE, latest);

        assertThat(statuses.size()).isEqualTo(10);
        assertThat(statuses.find(CommunityId.of("manual-caiambe")).orElseThrow().reason())
                .isEqualTo(Reason.NO_ROUTE_CONFIRMED);
        assertThat(statuses.find(CommunityId.of("osm-node-2984471123"))
                        .orElseThrow()
                        .route()
                        .coordinates())
                .hasSize(3);
        assertThat(repository.find(TEFE, latest))
                .as("cached: a pass never changes")
                .isSameAs(statuses);
    }

    @Test
    void anUnknownStatusIsShownAsNoData() throws IOException {
        Catalog catalog = catalogs(Duration.ZERO).load(TEFE);
        Pass latest = catalog.require(PassSelector.LATEST);
        Path file = directory.resolve(TEFE, latest.statusesPath());
        Files.writeString(
                file, Files.readString(file).replaceFirst("\"status\": \"isolated\"", "\"status\": \"flooded\""));

        PassStatuses statuses = new FileSystemPassStatusRepository(directory).find(TEFE, latest);

        assertThat(statuses.find(CommunityId.of("manual-caiambe")).orElseThrow().status())
                .isEqualTo(Status.NO_DATA);
    }

    @Test
    void missingStatusesMakeTheAoiUnavailable() throws IOException {
        Catalog catalog = catalogs(Duration.ZERO).load(TEFE);
        Pass latest = catalog.require(PassSelector.LATEST);
        Files.delete(directory.resolve(TEFE, latest.statusesPath()));

        assertThatThrownBy(() -> new FileSystemPassStatusRepository(directory).find(TEFE, latest))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void theRiverLevelIsOptional() throws IOException {
        var repository = new FileSystemRiverLevelRepository(directory, Clock.systemUTC(), Duration.ZERO);
        assertThat(repository.find(TEFE).orElseThrow().readings()).isNotEmpty();

        Files.delete(directory.aoiFile(TEFE, DataDirectory.RIVER_LEVEL));
        assertThat(repository.find(TEFE)).isEmpty();
    }

    @Test
    void layersAreStreamedFromInsideTheAoiFolderOnly() throws IOException {
        Catalog catalog = catalogs(Duration.ZERO).load(TEFE);
        LayerImage water =
                catalog.require(PassSelector.LATEST).layer(LayerId.WATER).orElseThrow();
        var repository = new FileSystemLayerImageRepository(directory);

        var content = repository.open(TEFE, water).orElseThrow();
        try (InputStream in = content.open()) {
            byte[] signature = in.readNBytes(4);
            assertThat(signature).containsExactly(0x89, 'P', 'N', 'G');
        }
        assertThat(repository.open(TEFE, new LayerImage(LayerId.WATER, "passes/nope.png", water.bounds(), 1, 1)))
                .isEmpty();
        assertThatThrownBy(() ->
                        repository.open(TEFE, new LayerImage(LayerId.WATER, "../../secret.png", water.bounds(), 1, 1)))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
