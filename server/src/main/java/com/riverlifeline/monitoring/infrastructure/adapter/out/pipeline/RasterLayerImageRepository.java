package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import com.riverlifeline.monitoring.application.port.out.BinaryContent;
import com.riverlifeline.monitoring.application.port.out.LayerImageRepository;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.pass.LayerId;
import com.riverlifeline.monitoring.domain.model.pass.LayerImage;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.RasterBox;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The layer PNGs of the passes the worker judged, painted from their stored rasters the first time they are asked
 * for and kept under {@code <raster dir>/images/}: a raster never changes once stored, so neither does its image.
 *
 * <p>Raster bodies are the pipeline's gzip files, {@code <raster dir>/<first two characters of the id>/<id>.bin.gz};
 * the monitoring context reads them on its own, without the pipeline's classes.
 */
public class RasterLayerImageRepository implements LayerImageRepository {
    private static final Logger log = LoggerFactory.getLogger(RasterLayerImageRepository.class);

    private final Path rasterDir;
    private final Path imageDir;
    private final AreaFileReader areas;
    private final ConcurrentHashMap<String, Object> painting = new ConcurrentHashMap<>();

    public RasterLayerImageRepository(Path rasterDir, AreaFileReader areas) {
        this.rasterDir = rasterDir.toAbsolutePath();
        this.imageDir = this.rasterDir.resolve("images");
        this.areas = areas;
    }

    @Override
    public Optional<BinaryContent> open(AoiId aoiId, LayerImage image) {
        Optional<LayerPath> path = LayerPath.parse(image.path());
        if (path.isEmpty() || path.get().layer() != image.id() || image.widthPx() == null || image.heightPx() == null) {
            return Optional.empty();
        }
        Path png = imageDir.resolve(path.get().rasterId() + "-" + image.id().code() + ".png");
        if (!Files.isRegularFile(png)) {
            Path body = body(path.get().rasterId());
            if (!Files.isRegularFile(body)) {
                return Optional.empty();
            }
            synchronized (painting.computeIfAbsent(png.toString(), k -> new Object())) {
                if (!Files.isRegularFile(png)) {
                    paint(image, box(path.get().rasterId(), image), body, aoiId, png);
                }
            }
            painting.remove(png.toString());
        }
        try {
            return Optional.of(new BinaryContent(Files.size(png), () -> Files.newInputStream(png)));
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read " + png, e);
        }
    }

    /** Where the pipeline keeps a raster's gzip body. */
    Path body(String rasterId) {
        return rasterDir
                .resolve(rasterId.substring(0, Math.min(2, rasterId.length())))
                .resolve(rasterId + ".bin.gz");
    }

    /** The raster's layout, from the image's bounds and size (the image covers the raster pixel for pixel). */
    static RasterBox box(String rasterId, LayerImage image) {
        double stepX = (image.bounds().east() - image.bounds().west()) / image.widthPx();
        double stepY = -(image.bounds().north() - image.bounds().south()) / image.heightPx();
        return new RasterBox(
                rasterId,
                image.widthPx(),
                image.heightPx(),
                image.id() == LayerId.RADAR_HH ? "float32" : "uint8",
                image.bounds().west() + stepX / 2,
                image.bounds().north() + stepY / 2,
                stepX,
                stepY);
    }

    private void paint(LayerImage image, RasterBox box, Path body, AoiId aoiId, Path png) {
        long started = System.nanoTime();
        try {
            byte[] values;
            try (InputStream in = new GZIPInputStream(Files.newInputStream(body), 64 * 1024)) {
                values = in.readAllBytes();
            }
            Files.createDirectories(imageDir);
            Path temporary = imageDir.resolve(png.getFileName() + ".tmp-" + UUID.randomUUID());
            try (OutputStream out = Files.newOutputStream(temporary)) {
                LayerPainter.paint(
                        image.id(), box, values, areas.find(aoiId.value()).orElse(null), out);
            }
            Files.move(temporary, png, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.info(
                    "Painted {} ({} x {}) in {} ms",
                    png.getFileName(),
                    box.width(),
                    box.height(),
                    (System.nanoTime() - started) / 1_000_000);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't paint " + png.getFileName(), e);
        }
    }
}
