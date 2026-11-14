package com.riverlifeline.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import com.riverlifeline.pipeline.domain.model.raster.XY;
import org.junit.jupiter.api.Test;

class RasterMetaTest {
    private static RasterMeta meta(DataType type, NoData nodata, double originX) {
        return new RasterMeta(
                5511,
                5496,
                type,
                nodata,
                RasterUnit.LINEAR_POWER,
                "EPSG:32720",
                new XY(originX, 9654321.0),
                new XY(20, -20));
    }

    @Test
    void theBodyLengthIsWidthTimesHeightTimesTheValueSize() {
        assertThat(meta(DataType.FLOAT32, NoData.NAN, 612345).byteLength()).isEqualTo(5511L * 5496 * 4);
        assertThat(meta(DataType.INT16, NoData.value(-1), 612345).byteLength()).isEqualTo(5511L * 5496 * 2);
    }

    @Test
    void noDataIsANumberNanOrNothing() {
        assertThat(NoData.parse("NaN")).isEqualTo(NoData.NAN);
        assertThat(NoData.parse(null)).isEqualTo(NoData.NONE);
        assertThat(NoData.parse("-32768")).isEqualTo(NoData.value(-32768));
        assertThat(NoData.value(255).text()).isEqualTo("255");
        assertThat(NoData.value(0.5).text()).isEqualTo("0.5");
        assertThat(NoData.NONE.text()).isNull();
        assertThatThrownBy(() -> meta(DataType.UINT8, NoData.NAN, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void layoutsAreValidated() {
        assertThatThrownBy(() -> new RasterMeta(
                        0, 1, DataType.UINT8, NoData.NONE, RasterUnit.CLASS, "EPSG:4326", new XY(0, 0), new XY(1, -1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RasterMeta(
                        1, 1, DataType.UINT8, NoData.NONE, RasterUnit.CLASS, "4326", new XY(0, 0), new XY(1, -1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(meta(DataType.FLOAT32, NoData.NAN, 612345).sameGridAs(meta(DataType.UINT8, NoData.NONE, 612345)))
                .isTrue();
        assertThat(meta(DataType.FLOAT32, NoData.NAN, 612345).sameGridAs(meta(DataType.FLOAT32, NoData.NAN, 612365)))
                .isFalse();
    }

    @Test
    void aReferenceIsDownloadedFromTheBackendPath() {
        RasterRef ref = new RasterRef(new RasterId("7f3c"), meta(DataType.FLOAT32, NoData.NAN, 0));

        assertThat(ref.href()).isEqualTo("/internal/worker/rasters/7f3c");
        assertThat(RasterId.random().value()).matches("[A-Za-z0-9_-]{22}");
        assertThatThrownBy(() -> new RasterId("../etc")).isInstanceOf(IllegalArgumentException.class);
    }
}
