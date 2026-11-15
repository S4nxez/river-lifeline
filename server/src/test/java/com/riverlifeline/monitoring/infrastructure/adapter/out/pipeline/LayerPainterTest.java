package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riverlifeline.monitoring.domain.model.pass.LayerId;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.RasterBox;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class LayerPainterTest {
    static final RasterBox CLASSES = new RasterBox("w1", 4, 1, "uint8", -64.75, -3.40, 0.01, -0.01);

    static BufferedImage paint(LayerId layer, RasterBox box, byte[] body, AreaFile area) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        LayerPainter.paint(layer, box, body, area, png);
        return ImageIO.read(new ByteArrayInputStream(png.toByteArray()));
    }

    static int alpha(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) >>> 24;
    }

    static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }

    @Test
    void waterShowsWideAndNarrowChannelsAndNothingElse() throws IOException {
        BufferedImage image = paint(LayerId.WATER, CLASSES, new byte[] {0, 1, 2, (byte) 255}, null);

        assertThat(image.getWidth()).isEqualTo(4);
        assertThat(alpha(image, 0, 0)).isZero();
        assertThat(rgb(image, 1, 0)).isEqualTo(0x3D8BFF);
        assertThat(rgb(image, 2, 0)).isEqualTo(0xFFB020);
        assertThat(alpha(image, 3, 0)).isZero();
    }

    @Test
    void vegetationShowsOnlyWaterUnderVegetation() throws IOException {
        BufferedImage image = paint(LayerId.FLOODED_VEGETATION, CLASSES, new byte[] {0, 1, 3, 3}, null);

        assertThat(alpha(image, 1, 0)).isZero();
        assertThat(alpha(image, 2, 0)).isEqualTo(255);
        assertThat(rgb(image, 3, 0)).isEqualTo(0x30A46C);
    }

    @Test
    void changeShowsWaterLostAndGained() throws IOException {
        BufferedImage image = paint(LayerId.CHANGE, CLASSES, new byte[] {0, 1, 2, (byte) 255}, null);

        assertThat(alpha(image, 0, 0)).isZero();
        assertThat(rgb(image, 1, 0)).isEqualTo(0xE47759);
        assertThat(rgb(image, 2, 0)).isEqualTo(0x68D6E6);
        assertThat(alpha(image, 3, 0)).isZero();
    }

    @Test
    void noDataShowsOnlyTheGapsInsideTheArea() throws IOException {
        // the last pixel is 59 km east of the centre: outside the 55 km circle
        RasterBox box = new RasterBox("w1", 3, 1, "uint8", -64.75, -3.40, 0.265, -0.01);
        BufferedImage image = paint(LayerId.NODATA, box, new byte[] {(byte) 255, 0, (byte) 255}, TestArea.area());

        assertThat(alpha(image, 0, 0)).isEqualTo(255);
        assertThat(alpha(image, 1, 0)).isZero();
        assertThat(alpha(image, 2, 0)).isZero();
    }

    @Test
    void radarIsGreyFromMinus25To0DbAndTransparentWithoutData() throws IOException {
        RasterBox box = new RasterBox("r1", 4, 1, "float32", -64.75, -3.40, 0.02, -0.02);
        ByteBuffer values = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        values.putFloat(-30f).putFloat(-12.5f).putFloat(3f).putFloat(Float.NaN);

        BufferedImage image = paint(LayerId.RADAR_HH, box, values.array(), null);

        assertThat(rgb(image, 0, 0)).isEqualTo(0x000000);
        assertThat(alpha(image, 0, 0)).isEqualTo(255);
        assertThat(image.getRGB(1, 0) & 0xFF).isBetween(126, 129);
        assertThat(rgb(image, 2, 0)).isEqualTo(0xFFFFFF);
        assertThat(alpha(image, 3, 0)).isZero();
    }

    @Test
    void sentinel1IsPaintedInGreysLikeTheRadar() throws IOException {
        RasterBox box = new RasterBox("v1", 2, 1, "float32", -64.75, -3.40, 0.02, -0.02);
        ByteBuffer values = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        values.putFloat(-21f).putFloat(Float.NaN);

        BufferedImage image = paint(LayerId.SENTINEL1_VV, box, values.array(), null);

        assertThat(alpha(image, 0, 0)).isEqualTo(255);
        assertThat(image.getRGB(0, 0) & 0xFF).isBetween(39, 43); // -21 dB: 4/25 of the way to white
        assertThat(alpha(image, 1, 0)).isZero();
    }

    @Test
    void aBodyThatDoesNotFitItsLayoutIsRefused() {
        assertThatThrownBy(() -> paint(LayerId.WATER, CLASSES, new byte[3], null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void layerPathsNameTheirRasterAndLayer() {
        assertThat(LayerPath.of("w1", LayerId.WATER)).isEqualTo("rasters/w1/water.png");
        assertThat(LayerPath.parse("rasters/w1/water.png")).contains(new LayerPath("w1", LayerId.WATER));
        for (LayerId layer : LayerId.values()) { // every layer, sentinel1_vv with its digit too
            assertThat(LayerPath.parse(LayerPath.of("X9AARG4PvI1Pf1OgBFLjnw", layer)))
                    .contains(new LayerPath("X9AARG4PvI1Pf1OgBFLjnw", layer));
        }
        assertThat(LayerPath.parse("passes/p1/water.png")).isEqualTo(Optional.empty());
        assertThat(LayerPath.parse("rasters/../x/water.png")).isEmpty();
        assertThat(LayerPath.parse("rasters/w1/unknown.png")).isEmpty();
    }
}
