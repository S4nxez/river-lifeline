package com.riverlifeline.pipeline.infrastructure.adapter.out.planetarycomputer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.riverlifeline.pipeline.infrastructure.TestTiff;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.ProductWindow;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.RangeReader;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CogTest {
    static final float NODATA = -32768f;

    /** A 40 x 50 image whose value encodes its position; the right edge is nodata, as at a swath edge. */
    static float[][] image() {
        float[][] image = new float[40][50];
        for (int r = 0; r < 40; r++) {
            for (int c = 0; c < 50; c++) {
                image[r][c] = c >= 45 ? NODATA : r * 1000 + c + 0.25f;
            }
        }
        return image;
    }

    static RangeReader reader(byte[] file) {
        return new RangeReader() {
            @Override
            public long size() {
                return file.length;
            }

            @Override
            public byte[] read(long offset, int length) {
                return Arrays.copyOfRange(file, (int) offset, (int) offset + length);
            }
        };
    }

    @Test
    void theHeaderGivesTheGeoreferenceOfEveryLevel() throws Exception {
        Cog cog = Cog.open(reader(TestTiff.write(image(), 16, 300000, 9630000, 10, 32720, NODATA)));

        assertThat(cog.levels()).isEqualTo(2);
        assertThat(cog.epsg()).isEqualTo(32720);
        assertThat(cog.nodata()).isEqualTo("-32768");
        assertThat(cog.width(1)).isEqualTo(25);
        assertThat(cog.stepX(1)).isEqualTo(20);
        assertThat(cog.originX(0)).isEqualTo(300005);
        assertThat(cog.originY(0)).isEqualTo(9629995);
        assertThat(cog.originX(1)).isEqualTo(300010);
        assertThat(cog.levelFor(20.03)).isEqualTo(1);
        assertThat(cog.levelFor(15)).isZero();
    }

    @Test
    void aWindowIsDecodedValueForValue() throws Exception {
        Cog cog = Cog.open(reader(TestTiff.write(image(), 16, 300000, 9630000, 10, 32720, NODATA)));
        ProductWindow w = new ProductWindow(10, 35, 3, 48);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        cog.writeWindow(0, w, NODATA, out);

        ByteBuffer b = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        float[][] expected = image();
        assertThat(out.size()).isEqualTo(25 * 45 * 4);
        for (int r = 10; r < 35; r++) {
            for (int c = 3; c < 48; c++) {
                assertThat(b.getFloat()).as("pixel %d,%d", r, c).isEqualTo(expected[r][c]);
            }
        }
        assertThat(cog.hasData(0, w)).isTrue();
        assertThat(cog.hasData(0, new ProductWindow(0, 16, 48, 50))).isFalse();
    }

    @Test
    void theOverviewIsReadToo() throws Exception {
        Cog cog = Cog.open(reader(TestTiff.write(image(), 16, 300000, 9630000, 10, 32720, NODATA)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        cog.writeWindow(1, new ProductWindow(0, 20, 0, 22), NODATA, out);

        ByteBuffer b = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(b.getFloat(0)).isCloseTo(0.25f, within(0f));
        assertThat(b.getFloat((5 * 22 + 7) * 4)).isEqualTo(10 * 1000 + 14 + 0.25f);
    }
}
