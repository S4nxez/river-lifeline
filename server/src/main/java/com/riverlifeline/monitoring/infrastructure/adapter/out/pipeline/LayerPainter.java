package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import com.riverlifeline.monitoring.domain.model.pass.LayerId;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.RasterBox;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.imageio.ImageIO;

/**
 * Paints a layer PNG from the raw body of a result raster (api/worker-api.md, class codes): an indexed image whose
 * index 0 is transparent, so the map shows only what the layer is about. Pure: no file system, no database.
 */
public final class LayerPainter {
    /** Radar backscatter shown from black to white over this range. */
    static final double RADAR_MIN_DB = -25.0;

    static final double RADAR_MAX_DB = 0.0;

    // Water classes and change codes fixed by the contract
    static final int WATER_WIDE = 1;
    static final int WATER_NARROW = 2;
    static final int WATER_VEGETATED = 3;
    static final int NO_DATA = 255;
    static final int CHANGE_LOST = 1;
    static final int CHANGE_GAINED = 2;

    private LayerPainter() {}

    /**
     * Paints {@code layer} from {@code body}, the raster's raw little-endian values laid out as {@code box} says.
     * {@code area} limits the no-data layer to the area's own pixels (the corners of a circular area are not gaps).
     */
    public static void paint(LayerId layer, RasterBox box, byte[] body, AreaFile area, OutputStream out)
            throws IOException {
        BufferedImage image = switch (layer) {
            case RADAR_HH -> radar(box, body);
            case WATER ->
                classes(box, body, palette(new int[][] {{}, {61, 139, 255}, {255, 176, 32}}), code -> switch (code) {
                    case WATER_WIDE -> 1;
                    case WATER_NARROW -> 2;
                    default -> 0;
                });
            case FLOODED_VEGETATION ->
                classes(box, body, palette(new int[][] {{}, {48, 164, 108}}), code -> code == WATER_VEGETATED ? 1 : 0);
            case CHANGE ->
                classes(box, body, palette(new int[][] {{}, {228, 119, 89}, {104, 214, 230}}), code -> switch (code) {
                    case CHANGE_LOST -> 1;
                    case CHANGE_GAINED -> 2;
                    default -> 0;
                });
            case NODATA -> noData(box, body, area);
        };
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("No PNG writer");
        }
    }

    /** Filtered HH (float32 dB, NaN no data) in greys; no data transparent. */
    static BufferedImage radar(RasterBox box, byte[] body) {
        requireLength(box, body, 4);
        int[][] greys = new int[256][];
        greys[0] = new int[0];
        for (int i = 1; i < 256; i++) {
            int g = (int) Math.round((i - 1) * 255.0 / 254.0);
            greys[i] = new int[] {g, g, g};
        }
        BufferedImage image = indexed(box, palette(greys));
        byte[] pixels = pixels(image);
        FloatBuffer values =
                ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        for (int i = 0; i < pixels.length; i++) {
            float db = values.get(i);
            if (Float.isNaN(db)) {
                continue;
            }
            double t = Math.clamp((db - RADAR_MIN_DB) / (RADAR_MAX_DB - RADAR_MIN_DB), 0.0, 1.0);
            pixels[i] = (byte) (1 + (int) Math.round(t * 254));
        }
        return image;
    }

    /** A uint8 class raster, each code mapped to a palette index (0: not painted). */
    static BufferedImage classes(RasterBox box, byte[] body, IndexColorModel colors, CodeMap map) {
        requireLength(box, body, 1);
        BufferedImage image = indexed(box, colors);
        byte[] pixels = pixels(image);
        byte[] lookup = new byte[256];
        for (int code = 0; code < 256; code++) {
            lookup[code] = (byte) map.index(code);
        }
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = lookup[body[i] & 0xFF];
        }
        return image;
    }

    /** The pixels of the area a pass has no data on (code 255), in grey. */
    static BufferedImage noData(RasterBox box, byte[] body, AreaFile area) {
        requireLength(box, body, 1);
        BufferedImage image = indexed(box, palette(new int[][] {{}, {139, 141, 152}}));
        byte[] pixels = pixels(image);
        for (int r = 0; r < box.height(); r++) {
            double lat = box.originY() + r * box.stepY();
            for (int c = 0; c < box.width(); c++) {
                int i = r * box.width() + c;
                if ((body[i] & 0xFF) == NO_DATA
                        && (area == null || area.contains(box.originX() + c * box.stepX(), lat))) {
                    pixels[i] = 1;
                }
            }
        }
        return image;
    }

    @FunctionalInterface
    interface CodeMap {
        int index(int code);
    }

    /** An indexed palette: entry 0 transparent, the others opaque with the given RGB. */
    static IndexColorModel palette(int[][] rgb) {
        int n = rgb.length;
        byte[] r = new byte[n];
        byte[] g = new byte[n];
        byte[] b = new byte[n];
        byte[] a = new byte[n];
        for (int i = 1; i < n; i++) {
            r[i] = (byte) rgb[i][0];
            g[i] = (byte) rgb[i][1];
            b[i] = (byte) rgb[i][2];
            a[i] = (byte) 255;
        }
        return new IndexColorModel(8, n, r, g, b, a);
    }

    private static BufferedImage indexed(RasterBox box, IndexColorModel colors) {
        return new BufferedImage(box.width(), box.height(), BufferedImage.TYPE_BYTE_INDEXED, colors);
    }

    private static byte[] pixels(BufferedImage image) {
        return ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    }

    private static void requireLength(RasterBox box, byte[] body, int bytesPerValue) {
        long expected = (long) box.width() * box.height() * bytesPerValue;
        if (body.length != expected) {
            throw new IllegalArgumentException(
                    "raster " + box.id() + " has " + body.length + " bytes, its layout needs " + expected);
        }
    }
}
