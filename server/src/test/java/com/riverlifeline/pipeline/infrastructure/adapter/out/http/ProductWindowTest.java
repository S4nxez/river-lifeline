package com.riverlifeline.pipeline.infrastructure.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.pipeline.domain.model.area.Bbox;
import org.junit.jupiter.api.Test;

class ProductWindowTest {
    /**
     * The window GDAL and pyproj gave over a real Sentinel-1 RTC tile (S1D_IW_GRDH_1SDV_20261025T100327, overview 1:
     * 14319 x 11058 pixels of about 20 m, EPSG:32720, tie point 51860, 9766420) for the box around Tefé.
     */
    @Test
    void aUtmWindowMatchesTheOneGdalComputed() {
        double stepX = 10.0 * 28639 / 14319;
        double stepY = 10.0 * 22117 / 11058;
        ProductWindow w = ProductWindow.of(
                new Bbox(-65.24494, -3.89407, -64.25506, -2.90593),
                32720,
                51860 + stepX / 2,
                9766420 - stepY / 2,
                stepX,
                -stepY,
                14319,
                11058,
                8);

        assertThat(w).isEqualTo(new ProductWindow(4375, 9867, 9920, 14319));
        assertThat(w.width()).isEqualTo(4399);
        assertThat(w.height()).isEqualTo(5492);
    }

    @Test
    void aGridOutsideTheAreaGivesAnEmptyWindow() {
        ProductWindow w = ProductWindow.of(new Bbox(10, 10, 11, 11), 4326, 20.0005, 30.0, 0.001, -0.001, 100, 100, 8);

        assertThat(w.width() == 0 || w.height() == 0).isTrue();
    }
}
