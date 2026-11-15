package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.monitoring.application.port.out.BinaryContent;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.pass.LayerId;
import com.riverlifeline.monitoring.domain.model.pass.LayerImage;
import com.riverlifeline.shared.domain.BoundingBox;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RasterLayerImageRepositoryTest {
    static final AoiId TEFE = AoiId.of("tefe");
    static final LayerImage WATER = new LayerImage(
            LayerId.WATER, "rasters/w1abc/water.png", new BoundingBox(-64.8, -3.42, -64.76, -3.41), 4, 1);

    @TempDir
    Path root;

    void store(String rasterId, byte[] body) throws IOException {
        Path file = root.resolve(rasterId.substring(0, 2)).resolve(rasterId + ".bin.gz");
        Files.createDirectories(file.getParent());
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(body);
        }
    }

    static BufferedImage read(BinaryContent content) throws IOException {
        try (InputStream in = content.open()) {
            return ImageIO.read(in);
        }
    }

    @Test
    void paintsALayerFromItsStoredRasterAndKeepsTheImage() throws IOException {
        store("w1abc", new byte[] {0, 1, 2, 0});
        RasterLayerImageRepository images = new RasterLayerImageRepository(root, TestArea.reader());

        BinaryContent content = images.open(TEFE, WATER).orElseThrow();

        BufferedImage image = read(content);
        assertThat(image.getWidth()).isEqualTo(4);
        assertThat(image.getRGB(1, 0) & 0xFFFFFF).isEqualTo(0x3D8BFF);
        assertThat(root.resolve("images").resolve("w1abc-water.png")).isRegularFile();
        assertThat(content.sizeBytes())
                .isEqualTo(Files.size(root.resolve("images").resolve("w1abc-water.png")));
    }

    @Test
    void aPaintedImageIsServedAgainWithoutReadingTheRaster() throws IOException {
        store("w1abc", new byte[] {0, 1, 2, 0});
        RasterLayerImageRepository images = new RasterLayerImageRepository(root, TestArea.reader());
        images.open(TEFE, WATER).orElseThrow();
        Files.delete(root.resolve("w1").resolve("w1abc.bin.gz")); // the raster is gone, its image stays

        assertThat(images.open(TEFE, WATER)).isPresent();
    }

    @Test
    void aLayerWhoseRasterIsMissingIsNotThere() {
        RasterLayerImageRepository images = new RasterLayerImageRepository(root, TestArea.reader());

        assertThat(images.open(TEFE, WATER)).isEmpty();
    }

    @Test
    void onlyPathsOfStoredRastersAreServed() throws IOException {
        store("w1abc", new byte[] {0, 1, 2, 0});
        RasterLayerImageRepository images = new RasterLayerImageRepository(root, TestArea.reader());

        LayerImage fileCatalogPath =
                new LayerImage(LayerId.WATER, "passes/p1/water.png", WATER.bounds(), WATER.widthPx(), WATER.heightPx());
        LayerImage otherLayer =
                new LayerImage(LayerId.CHANGE, WATER.path(), WATER.bounds(), WATER.widthPx(), WATER.heightPx());

        assertThat(images.open(TEFE, fileCatalogPath)).isEmpty();
        assertThat(images.open(TEFE, otherLayer)).isEmpty();
    }

    @Test
    void theRasterLayoutComesFromTheImageBoundsAndSize() {
        var box = RasterLayerImageRepository.box("w1abc", WATER);

        assertThat(box.width()).isEqualTo(4);
        assertThat(box.stepX()).isCloseTo(0.01, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(box.stepY()).isCloseTo(-0.01, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(box.originX()).isCloseTo(-64.795, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(box.originY()).isCloseTo(-3.415, org.assertj.core.data.Offset.offset(1e-12));
    }
}
