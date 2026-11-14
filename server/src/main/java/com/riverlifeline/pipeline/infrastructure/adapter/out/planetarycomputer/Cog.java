package com.riverlifeline.pipeline.infrastructure.adapter.out.planetarycomputer;

import com.riverlifeline.pipeline.infrastructure.adapter.out.http.ProductWindow;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.RangeReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A minimal Cloud Optimized GeoTIFF reader for single-band tiled float32 images, the layout of Planetary Computer's
 * Sentinel-1 RTC: classic or BigTIFF, Deflate, predictor none, horizontal or floating point. It reads the header and
 * the image directories once, then only the tiles a window needs, merging neighbouring tiles into one request.
 */
public final class Cog {
    private static final int MERGE_GAP = 64 * 1024;

    /** One image of the file: the full resolution (level 0) or an overview. */
    record Level(
            int width,
            int height,
            int tileWidth,
            int tileHeight,
            int compression,
            int predictor,
            int bits,
            int sampleFormat,
            long[] offsets,
            long[] counts) {
        int tilesAcross() {
            return (width + tileWidth - 1) / tileWidth;
        }
    }

    private final RangeReader file;
    private final ByteOrder order;
    private final boolean big;
    private final List<Level> levels = new ArrayList<>();
    private double[] pixelScale;
    private double[] tiepoint;
    private int epsg;
    private boolean pixelIsArea = true;
    private String nodata;

    private Cog(RangeReader file, ByteOrder order, boolean big) {
        this.file = file;
        this.order = order;
        this.big = big;
    }

    public static Cog open(RangeReader file) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(file.read(0, 16));
        ByteOrder order;
        if (header.get(0) == 'I' && header.get(1) == 'I') {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (header.get(0) == 'M' && header.get(1) == 'M') {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            throw new IOException("Not a TIFF file");
        }
        header.order(order);
        int version = header.getShort(2) & 0xffff;
        if (version != 42 && version != 43) {
            throw new IOException("Not a TIFF file (version " + version + ")");
        }
        Cog cog = new Cog(file, order, version == 43);
        long next = cog.big ? header.getLong(8) : header.getInt(4) & 0xffffffffL;
        while (next != 0) {
            next = cog.readDirectory(next);
        }
        if (cog.levels.isEmpty() || cog.pixelScale == null || cog.tiepoint == null || cog.epsg == 0) {
            throw new IOException("Not a georeferenced tiled TIFF");
        }
        return cog;
    }

    private long readDirectory(long at) throws IOException {
        int countSize = big ? 8 : 2;
        int entrySize = big ? 20 : 12;
        ByteBuffer c = ByteBuffer.wrap(file.read(at, countSize)).order(order);
        long n = big ? c.getLong() : c.getShort() & 0xffff;
        ByteBuffer e = ByteBuffer.wrap(file.read(at + countSize, (int) n * entrySize + (big ? 8 : 4)))
                .order(order);
        Map<Integer, long[]> tags = new HashMap<>();
        boolean first = levels.isEmpty();
        for (int i = 0; i < n; i++) {
            int base = i * entrySize;
            int tag = e.getShort(base) & 0xffff;
            int type = e.getShort(base + 2) & 0xffff;
            long count = big ? e.getLong(base + 4) : e.getInt(base + 4) & 0xffffffffL;
            int unit = switch (type) {
                case 1, 2, 6, 7 -> 1;
                case 3, 8 -> 2;
                case 4, 9, 11, 13 -> 4;
                default -> 8;
            };
            long bytes = unit * count;
            ByteBuffer v;
            if (bytes <= (big ? 8 : 4)) {
                byte[] raw = new byte[(int) bytes];
                e.get(base + (big ? 12 : 8), raw);
                v = ByteBuffer.wrap(raw).order(order);
            } else {
                long offset = big ? e.getLong(base + 12) : e.getInt(base + 8) & 0xffffffffL;
                v = ByteBuffer.wrap(file.read(offset, (int) bytes)).order(order);
            }
            if (type == 2) {
                if (first && tag == 42113) {
                    nodata = new String(v.array(), java.nio.charset.StandardCharsets.US_ASCII)
                            .replace("\0", "")
                            .strip();
                }
                continue;
            }
            if (type == 12) {
                double[] d = new double[(int) count];
                for (int k = 0; k < count; k++) {
                    d[k] = v.getDouble(k * 8);
                }
                if (first && tag == 33550) {
                    pixelScale = d;
                } else if (first && tag == 33922) {
                    tiepoint = d;
                }
                continue;
            }
            long[] values = new long[(int) count];
            for (int k = 0; k < count; k++) {
                values[k] = switch (unit) {
                    case 1 -> v.get(k) & 0xff;
                    case 2 -> v.getShort(k * 2) & 0xffff;
                    case 4 -> v.getInt(k * 4) & 0xffffffffL;
                    default -> v.getLong(k * 8);
                };
            }
            tags.put(tag, values);
        }
        if (first && tags.containsKey(34735)) {
            long[] keys = tags.get(34735);
            for (int i = 4; i + 3 < keys.length; i += 4) {
                if (keys[i] == 3072 || keys[i] == 2048) {
                    epsg = (int) keys[i + 3];
                } else if (keys[i] == 1025) {
                    pixelIsArea = keys[i + 3] == 1;
                }
            }
        }
        if (!tags.containsKey(322) || !tags.containsKey(324)) {
            throw new IOException("Not a tiled TIFF");
        }
        levels.add(new Level(
                (int) tags.get(256)[0],
                (int) tags.get(257)[0],
                (int) tags.get(322)[0],
                (int) tags.get(323)[0],
                (int) tags.getOrDefault(259, new long[] {1})[0],
                (int) tags.getOrDefault(317, new long[] {1})[0],
                (int) tags.getOrDefault(258, new long[] {8})[0],
                (int) tags.getOrDefault(339, new long[] {1})[0],
                tags.get(324),
                tags.get(325)));
        int tail = (int) n * entrySize;
        return big ? e.getLong(tail) : e.getInt(tail) & 0xffffffffL;
    }

    public int levels() {
        return levels.size();
    }

    public int epsg() {
        return epsg;
    }

    /** The GDAL_NODATA tag, or null. */
    public String nodata() {
        return nodata;
    }

    public int width(int level) {
        return levels.get(level).width();
    }

    public int height(int level) {
        return levels.get(level).height();
    }

    /** Pixel width of a level: overviews scale the full resolution by the ratio of the widths, as GDAL does. */
    public double stepX(int level) {
        return pixelScale[0]
                * levels.get(0).width()
                / (double) levels.get(level).width();
    }

    public double stepY(int level) {
        return pixelScale[1]
                * levels.get(0).height()
                / (double) levels.get(level).height();
    }

    /** Centre of pixel (0, 0) of a level (the tie point is a corner when the raster is PixelIsArea). */
    public double originX(int level) {
        double corner = tiepoint[3] - tiepoint[0] * pixelScale[0];
        return corner + (pixelIsArea ? stepX(level) / 2 : 0);
    }

    public double originY(int level) {
        double corner = tiepoint[4] + tiepoint[1] * pixelScale[1];
        return corner - (pixelIsArea ? stepY(level) / 2 : 0);
    }

    /** The coarsest level whose pixel is no larger than {@code pixel} (level 0 when every level is larger). */
    public int levelFor(double pixel) {
        int chosen = 0;
        for (int i = 0; i < levels.size(); i++) {
            if (stepX(i) <= pixel * 1.0001) {
                chosen = i;
            }
        }
        return chosen;
    }

    /** Whether any tile under the window holds data (empty tiles have a byte count of 0). */
    public boolean hasData(int level, ProductWindow w) {
        Level l = levels.get(level);
        for (int tr = w.r0() / l.tileHeight(); tr <= (w.r1() - 1) / l.tileHeight(); tr++) {
            for (int tc = w.c0() / l.tileWidth(); tc <= (w.c1() - 1) / l.tileWidth(); tc++) {
                if (l.counts()[tr * l.tilesAcross() + tc] > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Writes the window's values (float32, little-endian, row-major), one row of tiles in memory at a time. */
    public void writeWindow(int level, ProductWindow w, float fill, OutputStream out) throws IOException {
        Level l = levels.get(level);
        if (l.bits() != 32 || l.sampleFormat() != 3) {
            throw new IOException(
                    "Only float32 images are supported (bits " + l.bits() + ", format " + l.sampleFormat() + ")");
        }
        if (l.compression() != 1 && l.compression() != 8 && l.compression() != 32946) {
            throw new IOException("Unsupported compression " + l.compression());
        }
        int width = w.width();
        ByteBuffer row = ByteBuffer.allocate(width * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int tc0 = w.c0() / l.tileWidth();
        int tc1 = (w.c1() - 1) / l.tileWidth();
        for (int tr = w.r0() / l.tileHeight(); tr <= (w.r1() - 1) / l.tileHeight(); tr++) {
            float[][] tiles = tileRow(l, tr, tc0, tc1);
            int y0 = Math.max(w.r0(), tr * l.tileHeight());
            int y1 = Math.min(w.r1(), (tr + 1) * l.tileHeight());
            for (int y = y0; y < y1; y++) {
                row.clear();
                for (int x = w.c0(); x < w.c1(); x++) {
                    float[] tile = tiles[x / l.tileWidth() - tc0];
                    float value =
                            tile == null ? fill : tile[(y - tr * l.tileHeight()) * l.tileWidth() + (x % l.tileWidth())];
                    row.putFloat(value);
                }
                out.write(row.array());
            }
        }
    }

    private float[][] tileRow(Level l, int tr, int tc0, int tc1) throws IOException {
        float[][] tiles = new float[tc1 - tc0 + 1][];
        int i = tc0;
        while (i <= tc1) {
            int t = tr * l.tilesAcross() + i;
            if (l.counts()[t] == 0) {
                i++;
                continue;
            }
            long start = l.offsets()[t];
            long end = start + l.counts()[t];
            int j = i + 1;
            while (j <= tc1) {
                int u = tr * l.tilesAcross() + j;
                if (l.counts()[u] == 0 || l.offsets()[u] < end || l.offsets()[u] - end > MERGE_GAP) {
                    break;
                }
                end = l.offsets()[u] + l.counts()[u];
                j++;
            }
            byte[] bytes = file.readDirect(start, (int) (end - start));
            for (int k = i; k < j; k++) {
                int u = tr * l.tilesAcross() + k;
                int from = (int) (l.offsets()[u] - start);
                tiles[k - tc0] = decode(l, Arrays.copyOfRange(bytes, from, from + (int) l.counts()[u]));
            }
            i = j;
        }
        return tiles;
    }

    float[] decode(Level l, byte[] stored) throws IOException {
        int rowBytes = l.tileWidth() * 4;
        byte[] raw = l.compression() == 1 ? stored : inflate(stored, rowBytes * l.tileHeight());
        if (raw.length != rowBytes * l.tileHeight()) {
            throw new IOException("Tile of " + raw.length + " bytes, expected " + rowBytes * l.tileHeight());
        }
        float[] out = new float[l.tileWidth() * l.tileHeight()];
        int w = l.tileWidth();
        switch (l.predictor()) {
            case 1 -> ByteBuffer.wrap(raw).order(order).asFloatBuffer().get(out);
            case 2 -> {
                ByteBuffer b = ByteBuffer.wrap(raw).order(order);
                for (int y = 0; y < l.tileHeight(); y++) {
                    int previous = 0;
                    for (int x = 0; x < w; x++) {
                        int bits = b.getInt((y * w + x) * 4) + previous;
                        out[y * w + x] = Float.intBitsToFloat(bits);
                        previous = bits;
                    }
                }
            }
            case 3 -> {
                for (int y = 0; y < l.tileHeight(); y++) {
                    int base = y * rowBytes;
                    for (int k = 1; k < rowBytes; k++) {
                        raw[base + k] += raw[base + k - 1];
                    }
                    for (int x = 0; x < w; x++) {
                        int bits = (raw[base + x] & 0xff) << 24
                                | (raw[base + w + x] & 0xff) << 16
                                | (raw[base + 2 * w + x] & 0xff) << 8
                                | (raw[base + 3 * w + x] & 0xff);
                        out[y * w + x] = Float.intBitsToFloat(bits);
                    }
                }
            }
            default -> throw new IOException("Unsupported predictor " + l.predictor());
        }
        return out;
    }

    private static byte[] inflate(byte[] compressed, int expected) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[expected];
            int n = 0;
            while (n < expected && !inflater.finished()) {
                int k = inflater.inflate(out, n, expected - n);
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += k;
            }
            return n == expected ? out : Arrays.copyOf(out, n);
        } catch (DataFormatException e) {
            throw new IOException("Corrupt tile: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }
}
