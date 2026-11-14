package com.riverlifeline.pipeline.infrastructure;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * Writes a small single-band float32 tiled GeoTIFF like Planetary Computer's Sentinel-1 RTC COGs: classic
 * little-endian TIFF, Deflate with the floating point predictor, a full-resolution image and one overview at half
 * size, PixelIsArea, and a GDAL_NODATA tag. Tiles whose values are all nodata are left empty (byte count 0).
 */
public final class TestTiff {
    private TestTiff() {}

    /** {@code image[row][col]}; the tie point is the top-left corner of the image in {@code epsg}. */
    public static byte[] write(
            float[][] image, int tile, double cornerX, double cornerY, double pixel, int epsg, float nodata) {
        float[][] overview = halve(image, nodata);
        List<Ifd> ifds = List.of(
                new Ifd(image, tile, false, cornerX, cornerY, pixel, epsg, nodata),
                new Ifd(overview, tile, true, cornerX, cornerY, pixel, epsg, nodata));
        // Layout: header, then each directory followed by its out-of-line values, then every tile
        int offset = 8;
        for (Ifd ifd : ifds) {
            ifd.at = offset;
            offset += ifd.size();
        }
        for (Ifd ifd : ifds) {
            for (int t = 0; t < ifd.tiles.size(); t++) {
                byte[] data = ifd.tiles.get(t);
                ifd.offsets[t] = data == null ? 0 : offset;
                offset += data == null ? 0 : data.length;
            }
        }
        ByteBuffer out = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifds.get(0).at);
        for (int i = 0; i < ifds.size(); i++) {
            ifds.get(i).writeTo(out, i + 1 < ifds.size() ? ifds.get(i + 1).at : 0);
        }
        for (Ifd ifd : ifds) {
            for (byte[] data : ifd.tiles) {
                if (data != null) {
                    out.put(data);
                }
            }
        }
        return out.array();
    }

    private static float[][] halve(float[][] image, float nodata) {
        int h = (image.length + 1) / 2;
        int w = (image[0].length + 1) / 2;
        float[][] out = new float[h][w];
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                out[r][c] = image[2 * r][2 * c];
            }
        }
        return out;
    }

    private record Entry(int tag, int type, long count, byte[] value) {}

    private static final class Ifd {
        final float[][] image;
        final int tile;
        final List<byte[]> tiles = new ArrayList<>();
        final long[] offsets;
        final List<Entry> entries = new ArrayList<>();
        int at;

        Ifd(
                float[][] image,
                int tile,
                boolean overview,
                double cornerX,
                double cornerY,
                double pixel,
                int epsg,
                float nodata) {
            this.image = image;
            this.tile = tile;
            int height = image.length;
            int width = image[0].length;
            int across = (width + tile - 1) / tile;
            int down = (height + tile - 1) / tile;
            for (int tr = 0; tr < down; tr++) {
                for (int tc = 0; tc < across; tc++) {
                    tiles.add(encode(image, tr, tc, tile, nodata));
                }
            }
            offsets = new long[tiles.size()];
            if (overview) {
                entries.add(shorts(254, 1));
            }
            entries.add(shorts(256, width));
            entries.add(shorts(257, height));
            entries.add(shorts(258, 32));
            entries.add(shorts(259, 8));
            entries.add(shorts(262, 1));
            entries.add(shorts(277, 1));
            entries.add(shorts(284, 1));
            entries.add(shorts(317, 3));
            entries.add(shorts(322, tile));
            entries.add(shorts(323, tile));
            entries.add(new Entry(324, 4, tiles.size(), null));
            entries.add(new Entry(325, 4, tiles.size(), null));
            entries.add(shorts(339, 3));
            if (!overview) {
                entries.add(doubles(33550, pixel, pixel, 0));
                entries.add(doubles(33922, 0, 0, 0, cornerX, cornerY, 0));
                entries.add(shorts(34735, 1, 1, 0, 3, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, epsg));
                byte[] text = (formatNodata(nodata) + "\0").getBytes(StandardCharsets.US_ASCII);
                entries.add(new Entry(42113, 2, text.length, text));
            }
        }

        static String formatNodata(float nodata) {
            return nodata == Math.rint(nodata) ? Long.toString((long) nodata) : Float.toString(nodata);
        }

        int size() {
            int size = 2 + entries.size() * 12 + 4;
            for (Entry e : entries) {
                int bytes = bytes(e);
                if (bytes > 4) {
                    size += bytes;
                }
            }
            return size;
        }

        private static int bytes(Entry e) {
            int unit = e.type == 3 ? 2 : e.type == 4 ? 4 : e.type == 12 ? 8 : 1;
            return (int) (unit * e.count);
        }

        void writeTo(ByteBuffer out, int next) {
            out.position(at);
            out.putShort((short) entries.size());
            int extra = at + 2 + entries.size() * 12 + 4;
            List<byte[]> values = new ArrayList<>();
            for (Entry e : entries) {
                byte[] value = e.value;
                if (e.tag == 324 || e.tag == 325) {
                    ByteBuffer b = ByteBuffer.allocate(tiles.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
                    for (int t = 0; t < tiles.size(); t++) {
                        b.putInt(e.tag == 324 ? (int) offsets[t] : tiles.get(t) == null ? 0 : tiles.get(t).length);
                    }
                    value = b.array();
                }
                out.putShort((short) e.tag).putShort((short) e.type).putInt((int) e.count);
                if (value.length <= 4) {
                    byte[] padded = new byte[4];
                    System.arraycopy(value, 0, padded, 0, value.length);
                    out.put(padded);
                } else {
                    out.putInt(extra);
                    extra += value.length;
                    values.add(value);
                }
            }
            out.putInt(next);
            values.forEach(out::put);
        }

        private static Entry shorts(int tag, int... values) {
            ByteBuffer b = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : values) {
                b.putShort((short) v);
            }
            return new Entry(tag, 3, values.length, b.array());
        }

        private static Entry doubles(int tag, double... values) {
            ByteBuffer b = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (double v : values) {
                b.putDouble(v);
            }
            return new Entry(tag, 12, values.length, b.array());
        }

        /** Floating point predictor (byte planes, most significant first, differenced) then Deflate. */
        private static byte[] encode(float[][] image, int tr, int tc, int tile, float nodata) {
            int rowBytes = tile * 4;
            byte[] raw = new byte[rowBytes * tile];
            boolean data = false;
            for (int y = 0; y < tile; y++) {
                byte[] row = new byte[rowBytes];
                for (int x = 0; x < tile; x++) {
                    int r = tr * tile + y;
                    int c = tc * tile + x;
                    float v = r < image.length && c < image[0].length ? image[r][c] : nodata;
                    if (v != nodata) {
                        data = true;
                    }
                    int bits = Float.floatToIntBits(v);
                    row[x] = (byte) (bits >>> 24);
                    row[tile + x] = (byte) (bits >>> 16);
                    row[2 * tile + x] = (byte) (bits >>> 8);
                    row[3 * tile + x] = (byte) bits;
                }
                for (int k = rowBytes - 1; k > 0; k--) {
                    row[k] -= row[k - 1];
                }
                System.arraycopy(row, 0, raw, y * rowBytes, rowBytes);
            }
            if (!data) {
                return null;
            }
            Deflater deflater = new Deflater();
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            deflater.end();
            return out.toByteArray();
        }
    }
}
