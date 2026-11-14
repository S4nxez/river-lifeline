package com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem;

import com.riverlifeline.monitoring.application.port.out.BinaryContent;
import com.riverlifeline.monitoring.application.port.out.LayerImageRepository;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.pass.LayerImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Streams the layer PNGs of a pass from disk. */
public class FileSystemLayerImageRepository implements LayerImageRepository {
    private final DataDirectory directory;

    public FileSystemLayerImageRepository(DataDirectory directory) {
        this.directory = directory;
    }

    @Override
    public Optional<BinaryContent> open(AoiId aoiId, LayerImage image) {
        Path file = directory.resolve(aoiId, image.path());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BinaryContent(Files.size(file), () -> Files.newInputStream(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read " + file, e);
        }
    }
}
