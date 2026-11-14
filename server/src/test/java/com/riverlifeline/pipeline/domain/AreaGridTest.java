package com.riverlifeline.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.riverlifeline.pipeline.domain.model.area.AreaGrid;
import com.riverlifeline.pipeline.domain.model.area.Bbox;
import com.riverlifeline.pipeline.domain.model.area.Circle;
import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import org.junit.jupiter.api.Test;

class AreaGridTest {
    /** Tefé: a 55 km circle at 0.00018 degrees, whose occurrence PNG is 5499 x 5527. */
    @Test
    void theGridOfACircleIsTheSquareAroundIt() {
        AreaGrid grid = AreaGrid.of(null, new Circle(-64.75, -3.40, 55), 0.00018);

        assertThat(grid.bbox()).isEqualTo(new Bbox(-65.24494, -3.89742, -64.25506, -2.90258));
        assertThat(grid.width()).isEqualTo(5499);
        assertThat(grid.height()).isEqualTo(5527);
        assertThat(grid.origin().x()).isCloseTo(-65.24494 + 0.00009, within(1e-12));
        assertThat(grid.origin().y()).isCloseTo(-2.90258 - 0.00009, within(1e-12));
        assertThat(grid.step().x()).isEqualTo(0.00018);
        assertThat(grid.step().y()).isEqualTo(-0.00018);
        assertThat(grid.pixelM()).isCloseTo(20.02, within(0.005));
        assertThat(grid.circle()).isNotNull();
    }

    @Test
    void aBoxAreaKeepsItsBox() {
        AreaGrid grid = AreaGrid.of(new Bbox(10, 20, 10.1, 20.05), null, 0.01);

        assertThat(grid.width()).isEqualTo(10);
        assertThat(grid.height()).isEqualTo(5);
        assertThat(grid.circle()).isNull();
        assertThatThrownBy(() -> AreaGrid.of(null, null, 0.01)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AreaGrid.of(new Bbox(0, 0, 1, 1), null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rastersOnTheGridShareItsLayout() {
        AreaGrid grid = AreaGrid.of(new Bbox(10, 20, 10.1, 20.05), null, 0.01);
        RasterMeta classes = grid.meta(DataType.UINT8, NoData.value(255), RasterUnit.CLASS);

        assertThat(classes.crs()).isEqualTo("EPSG:4326");
        assertThat(classes.byteLength()).isEqualTo(50);
        assertThat(grid.maxRasterBytes()).isEqualTo(200);
        assertThat(classes.sameGridAs(grid.meta(DataType.FLOAT32, NoData.NAN, RasterUnit.DB)))
                .isTrue();
    }
}
