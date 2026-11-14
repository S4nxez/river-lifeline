package com.riverlifeline.pipeline.infrastructure.adapter.out.earthdata;

import com.riverlifeline.pipeline.application.port.out.GranuleWindows;
import com.riverlifeline.pipeline.application.port.out.RasterSink;
import com.riverlifeline.pipeline.domain.model.area.AreaGrid;
import com.riverlifeline.pipeline.domain.model.job.JobInputs.GranuleWindow;
import com.riverlifeline.pipeline.domain.model.pass.GranuleRef;
import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import com.riverlifeline.pipeline.domain.model.raster.XY;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.ProductWindow;
import io.jhdf.HdfFile;
import io.jhdf.api.Dataset;
import io.jhdf.api.Node;
import io.jhdf.exceptions.HdfInvalidPathException;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.util.Optional;

/**
 * The area's window of a NISAR L2 GCOV granule, frequency A, read over HTTP range requests with jHDF. The covariance
 * terms are copied as they are (float32 linear power, NaN outside the swath), in strips of the dataset's chunk height,
 * so a strip is never more than a few MB in memory.
 */
public class GcovGranuleWindows implements GranuleWindows {
    static final String GRID = "/science/LSAR/GCOV/grids/frequencyA/";
    static final int MIN_WINDOW_PIXELS = 10;
    static final int PAD_PIXELS = 8;
    static final int STRIP_ROWS = 512;

    /** Opens a granule: its URL and the Earthdata token (sent only to the URL's own host). */
    @FunctionalInterface
    public interface Opener {
        SeekableByteChannel open(URI uri, String token) throws IOException;
    }

    private final String token;
    private final Opener opener;

    public GcovGranuleWindows(String token, Opener opener) {
        this.token = token == null ? "" : token.strip();
        this.opener = opener;
    }

    @Override
    public boolean available() {
        return !token.isEmpty();
    }

    @Override
    public Optional<GranuleWindow> read(GranuleRef granule, AreaGrid grid, RasterSink sink) throws IOException {
        URI uri = URI.create(granule.url());
        try (SeekableByteChannel channel = opener.open(uri, token);
                HdfFile file = new HdfFile(channel, uri)) {
            Dataset hh = dataset(file, "HHHH");
            if (hh == null) {
                return Optional.empty();
            }
            int epsg = scalarInt(file.getDatasetByPath(GRID + "projection").getData());
            double[] xs = doubles(file.getDatasetByPath(GRID + "xCoordinates").getData());
            double[] ys = doubles(file.getDatasetByPath(GRID + "yCoordinates").getData());
            if (xs.length < 2 || ys.length < 2) {
                throw new IOException(granule.name() + " has no grid coordinates");
            }
            double dx = xs[1] - xs[0];
            double dy = ys[1] - ys[0];
            ProductWindow w =
                    ProductWindow.of(grid.bbox(), epsg, xs[0], ys[0], dx, dy, xs.length, ys.length, PAD_PIXELS);
            if (w.width() < MIN_WINDOW_PIXELS || w.height() < MIN_WINDOW_PIXELS) {
                return Optional.empty();
            }
            RasterMeta meta = new RasterMeta(
                    w.width(),
                    w.height(),
                    DataType.FLOAT32,
                    NoData.NAN,
                    RasterUnit.LINEAR_POWER,
                    "EPSG:" + epsg,
                    new XY(xs[w.c0()], ys[w.r0()]),
                    new XY(dx, dy));
            RasterRef hhRef = sink.write(meta, out -> strips(hh, w, out));
            Dataset hv = dataset(file, "HVHV");
            RasterRef hvRef = hv == null ? null : sink.write(meta, out -> strips(hv, w, out));
            return Optional.of(new GranuleWindow(granule.name(), granule.frame(), hhRef, hvRef));
        } catch (RuntimeException e) {
            throw new IOException("Could not read " + granule.name() + ": " + e.getMessage(), e);
        }
    }

    private static Dataset dataset(HdfFile file, String band) {
        try {
            Node node = file.getByPath(GRID + band);
            return node instanceof Dataset d ? d : null;
        } catch (HdfInvalidPathException e) {
            return null;
        }
    }

    static void strips(Dataset dataset, ProductWindow w, OutputStream out) throws IOException {
        if (dataset.getJavaType() != float.class) {
            throw new IOException(dataset.getName() + " is " + dataset.getJavaType() + ", not float32");
        }
        ByteBuffer row = ByteBuffer.allocate(w.width() * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int r = 0; r < w.height(); r += STRIP_ROWS) {
            int rows = Math.min(STRIP_ROWS, w.height() - r);
            float[][] strip = (float[][]) dataset.getData(new long[] {w.r0() + r, w.c0()}, new int[] {rows, w.width()});
            for (float[] values : strip) {
                row.clear();
                row.asFloatBuffer().put(values);
                out.write(row.array());
            }
        }
    }

    private static int scalarInt(Object data) {
        if (data instanceof Number n) {
            return n.intValue();
        }
        if (data != null && data.getClass().isArray() && Array.getLength(data) > 0) {
            return ((Number) Array.get(data, 0)).intValue();
        }
        throw new IllegalArgumentException("projection is not an EPSG code");
    }

    private static double[] doubles(Object data) {
        if (data instanceof double[] d) {
            return d;
        }
        if (data instanceof float[] f) {
            double[] d = new double[f.length];
            for (int i = 0; i < f.length; i++) {
                d[i] = f[i];
            }
            return d;
        }
        throw new IllegalArgumentException("coordinates are not a 1-D float array");
    }
}
