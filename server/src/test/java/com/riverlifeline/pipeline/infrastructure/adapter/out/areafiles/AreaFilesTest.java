package com.riverlifeline.pipeline.infrastructure.adapter.out.areafiles;

import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import com.riverlifeline.pipeline.infrastructure.CapturingSink;
import com.riverlifeline.pipeline.infrastructure.json.ContractJson;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The area files shipped in server/src/main/resources/areas/. */
class AreaFilesTest {
    @Test
    void tefeLoadsWithItsGridCommunitiesMethodAndGauge() throws Exception {
        AreaFiles files = AreaFiles.fromClasspath(ContractJson.mapper());

        Area tefe = files.find(new AreaId("tefe")).orElseThrow();

        assertThat(tefe.grid().width()).isEqualTo(5499);
        assertThat(tefe.grid().height()).isEqualTo(5527);
        assertThat(tefe.communities()).hasSize(204);
        assertThat(tefe.communities()).allMatch(c -> c.id().startsWith("osm"));
        assertThat(tefe.hub().name()).isEqualTo("Tefé");
        assertThat(tefe.gauge().code()).isEqualTo("12900001");
        assertThat(tefe.method().safeWidthM()).isEqualTo(60);
        assertThat(tefe.method().speckle().filter()).isEqualTo("refined_lee");
        assertThat(tefe.method().speckle().windowM()).isEqualTo(60);
        assertThat(tefe.method().waterDbAdaptive()).isNull();
        assertThat(tefe.method().sentinel1().maxDays()).isEqualTo(4);
        assertThat(tefe.method().lowWater().seasonalPctHigh()).isEqualTo(90);
        assertThat(tefe.digest()).matches("[0-9a-f]{64}");
        assertThat(files.occurrencePng(tefe.id())).isPresent();
    }

    @Test
    void theOccurrencePngBecomesAPercentRasterOnTheAreaGrid() throws Exception {
        AreaFiles files = AreaFiles.fromClasspath(ContractJson.mapper());
        Area tefe = files.find(new AreaId("tefe")).orElseThrow();
        CapturingSink sink = new CapturingSink();

        RasterRef ref = new PngOccurrenceSource(files)
                .write(tefe, new RasterId("occ-tefe"), sink)
                .orElseThrow();

        assertThat(ref.meta()).isEqualTo(tefe.grid().meta(DataType.UINT8, NoData.value(255), RasterUnit.PERCENT));
        byte[] body = sink.bodies.get(ref.id());
        assertThat(body).hasSize(5499 * 5527);
        boolean percentsOrNoData = true;
        for (byte b : body) {
            int v = b & 0xff;
            percentsOrNoData &= v <= 100 || v == 255;
        }
        assertThat(percentsOrNoData).isTrue();
    }

    @Test
    void aBrokenFileIsLeftOutAndTheOthersLoad() {
        Map<String, String> folder = Map.of(
                "good.json", """
                        {"id": "good", "name": "Good", "bbox": [10, 20, 10.1, 20.1], "step_deg": 0.01,
                         "hub": {"name": "H", "lon": 10.05, "lat": 20.05, "radius_m": 500, "access": [10.06, 20.06]},
                         "river": {"name": "R", "lon": 10.02, "lat": 20.02, "radius_m": 500},
                         "access_radius_m": 1000, "min_coverage": 0.8, "min_coverage_shown": 0.05, "water_db": -16,
                         "widths_m": [40], "safe_width_m": 60}""",
                "bad.json", "{\"id\": \"bad\"}",
                "renamed.json", "{\"id\": \"other\"}");
        AreaFiles files = new AreaFiles(
                ContractJson.mapper(),
                List.of("bad.json", "good.json", "renamed.json"),
                name -> folder.get(name).getBytes(StandardCharsets.UTF_8));

        assertThat(files.all()).extracting(a -> a.id().value()).containsExactly("good");
        Area good = files.all().get(0);
        assertThat(good.gauge()).isNull();
        assertThat(good.communities()).isEmpty();
        assertThat(good.hub().access().lon()).isEqualTo(10.06);
        assertThat(good.method().validFloorDb()).isEqualTo(-40);
        assertThat(good.method().sentinel1()).isNull();
        assertThat(good.method().gaugeDays()).isEqualTo(1);
        assertThat(files.occurrencePng(good.id())).isEmpty();
    }
}
