package com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem;

import com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException;
import com.riverlifeline.monitoring.domain.model.Identifier;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The layout of {@code $RIVER_DATA_DIR} (CONTRACTS.md 2.1). Paths inside the JSON files are relative to the AOI folder
 * and are never allowed to leave it.
 */
public class DataDirectory {
    public static final String CATALOG = "catalog.json";
    public static final String COMMUNITIES = "communities.geojson";
    public static final String RIVER_LEVEL = "river_level.json";

    private final Path root;

    public DataDirectory(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path aoiFolder(AoiId aoiId) {
        return root.resolve("aois").resolve(aoiId.value());
    }

    public Path aoiFile(AoiId aoiId, String name) {
        return aoiFolder(aoiId).resolve(name);
    }

    /** Every AOI folder that holds a catalog, sorted by id. */
    public List<AoiId> aoiIds() {
        Path aois = root.resolve("aois");
        if (!Files.isDirectory(aois)) {
            return List.of();
        }
        try (Stream<Path> folders = Files.list(aois)) {
            return folders.filter(folder -> Files.isRegularFile(folder.resolve(CATALOG)))
                    .map(folder -> folder.getFileName().toString())
                    .filter(Identifier::isValid)
                    .sorted()
                    .map(AoiId::of)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Can't list " + aois, e);
        }
    }

    /** A path written in a JSON file of the AOI, resolved against its folder. */
    public Path resolve(AoiId aoiId, String relativePath) {
        Path folder = aoiFolder(aoiId);
        try {
            Path resolved = folder.resolve(relativePath).normalize();
            if (relativePath.isBlank() || Path.of(relativePath).isAbsolute() || !resolved.startsWith(folder)) {
                throw new CatalogUnavailableException(aoiId, "path '" + relativePath + "' leaves the AOI folder");
            }
            return resolved;
        } catch (InvalidPathException e) {
            throw new CatalogUnavailableException(aoiId, "invalid path '" + relativePath + "'", e);
        }
    }
}
