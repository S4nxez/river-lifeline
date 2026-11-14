package com.riverlifeline.pipeline.infrastructure.adapter.out.earthdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.riverlifeline.pipeline.PipelineTestData;
import com.riverlifeline.pipeline.domain.model.area.AreaGrid;
import com.riverlifeline.pipeline.domain.model.area.Circle;
import com.riverlifeline.pipeline.domain.model.job.JobInputs.GranuleWindow;
import com.riverlifeline.pipeline.domain.model.pass.GranuleRef;
import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import com.riverlifeline.pipeline.infrastructure.CapturingSink;
import io.jhdf.HdfFile;
import io.jhdf.WritableHdfFile;
import io.jhdf.api.DatasetCreationOptions;
import io.jhdf.api.WritableGroup;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A small GCOV-like file written with jHDF: chunked, shuffled and deflated like the NISAR products. */
class GcovGranuleWindowsTest {
    static final double X0 = -64.80;
    static final double Y0 = -3.35;
    static final double STEP = 0.0005;

    @TempDir
    Path folder;

    private final List<String> tokens = new ArrayList<>();

    static float value(int r, int c) {
        return r * 1000 + c + 0.5f;
    }

    private Path write(boolean dualPol) {
        Path file = folder.resolve(dualPol ? "dual.h5" : "single.h5");
        int rows = 200;
        int cols = 220;
        float[][] hh = new float[rows][cols];
        float[][] hv = new float[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                hh[r][c] = value(r, c);
                hv[r][c] = -value(r, c);
            }
        }
        double[] xs = new double[cols];
        double[] ys = new double[rows];
        for (int c = 0; c < cols; c++) {
            xs[c] = X0 + c * STEP;
        }
        for (int r = 0; r < rows; r++) {
            ys[r] = Y0 - r * STEP;
        }
        DatasetCreationOptions chunked = DatasetCreationOptions.builder()
                .chunkDimensions(32, 32)
                .shuffle()
                .deflate(1)
                .build();
        try (WritableHdfFile out = HdfFile.write(file)) {
            WritableGroup grid = out.putGroup("science")
                    .putGroup("LSAR")
                    .putGroup("GCOV")
                    .putGroup("grids")
                    .putGroup("frequencyA");
            grid.putDataset("projection", new int[] {4326});
            grid.putDataset("xCoordinates", xs);
            grid.putDataset("yCoordinates", ys);
            grid.putDataset("HHHH", hh, chunked);
            if (dualPol) {
                grid.putDataset("HVHV", hv, chunked);
            }
        }
        return file;
    }

    private GcovGranuleWindows reader(Path file) {
        return new GcovGranuleWindows(" token ", (uri, token) -> {
            tokens.add(token);
            return FileChannel.open(file, StandardOpenOption.READ);
        });
    }

    private static GranuleRef granule() {
        return PipelineTestData.granule("2026-10-23", 161, 176, "NISAR_L2_GCOV_V1");
    }

    @Test
    void theAreaWindowIsCopiedValueForValue() throws Exception {
        AreaGrid grid = AreaGrid.of(null, new Circle(-64.75, -3.40, 2), 0.0009);
        CapturingSink sink = new CapturingSink();

        GranuleWindow window = reader(write(true)).read(granule(), grid, sink).orElseThrow();

        RasterMeta meta = window.hh().meta();
        assertThat(meta.dtype()).isEqualTo(DataType.FLOAT32);
        assertThat(meta.nodata()).isEqualTo(NoData.NAN);
        assertThat(meta.unit()).isEqualTo(RasterUnit.LINEAR_POWER);
        assertThat(meta.crs()).isEqualTo("EPSG:4326");
        assertThat(meta.step().x()).isCloseTo(STEP, within(1e-12));
        assertThat(meta.step().y()).isCloseTo(-STEP, within(1e-12));
        int c0 = (int) Math.round((meta.origin().x() - X0) / STEP);
        int r0 = (int) Math.round((Y0 - meta.origin().y()) / STEP);
        // The window holds the area's bbox plus 8 pixels on every side
        assertThat(meta.origin().x()).isLessThanOrEqualTo(grid.bbox().lon0() - 7 * STEP);
        assertThat(meta.origin().y()).isGreaterThanOrEqualTo(grid.bbox().lat1() + 7 * STEP);
        float[][] hh = sink.floats(window.hh());
        float[][] hv = sink.floats(window.hv());
        for (int r = 0; r < meta.height(); r += 7) {
            for (int c = 0; c < meta.width(); c += 5) {
                assertThat(hh[r][c]).isEqualTo(value(r0 + r, c0 + c));
                assertThat(hv[r][c]).isEqualTo(-value(r0 + r, c0 + c));
            }
        }
        assertThat(window.id()).isEqualTo(granule().name());
        assertThat(window.frame()).isEqualTo(176);
        assertThat(tokens).containsExactly("token");
    }

    @Test
    void aSinglePolarisationFrameHasNoHvAndAFrameAwayFromTheAreaGivesNothing() throws Exception {
        Path single = write(false);
        AreaGrid near = AreaGrid.of(null, new Circle(-64.75, -3.40, 2), 0.0009);
        AreaGrid far = AreaGrid.of(null, new Circle(-62, -3.40, 2), 0.0009);

        Optional<GranuleWindow> window = reader(single).read(granule(), near, new CapturingSink());

        assertThat(window.orElseThrow().hv()).isNull();
        assertThat(reader(single).read(granule(), far, new CapturingSink())).isEmpty();
        assertThat(new GcovGranuleWindows("", (u, t) -> null).available()).isFalse();
    }
}
