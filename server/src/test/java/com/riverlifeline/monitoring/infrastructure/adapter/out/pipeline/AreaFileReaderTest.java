package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.riverlifeline.shared.domain.GeoPoint;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AreaFileReaderTest {

    @Test
    void readsTheAreaItsMethodAndItsCommunities() {
        AreaFile area = TestArea.area();

        assertThat(area.name()).isEqualTo("Lake Tefé");
        assertThat(area.hub().waterAccess()).isEqualTo(new GeoPoint(-64.71, -3.35));
        assertThat(area.gauge().stationCode()).isEqualTo("12900001");
        assertThat(area.method().widthsM()).containsExactly(40, 60, 100, 200, 400);
        assertThat(area.method().safeWidthM()).isEqualTo(60);
        assertThat(area.method().waterDbWeak()).isEqualTo(-14.0);
        assertThat(area.method().lowWaterGaugeMaxCm()).isEqualTo(1200.0);
        assertThat(area.communities()).extracting(AreaFile.CommunityPlace::id).containsExactly("a", "b", "c", "d");
        assertThat(area.communities().getFirst().access()).isEqualTo(new GeoPoint(-64.80, -3.22));
    }

    @Test
    void theGridOfACircleIsTheSquareAroundItAsThePipelineDerivesIt() {
        AreaFile area = TestArea.area();

        // the same bbox the pipeline computes for Tefé (Bbox.around)
        assertThat(area.bbox().west()).isEqualTo(-65.24494);
        assertThat(area.bbox().south()).isEqualTo(-3.89742);
        assertThat(area.bbox().east()).isEqualTo(-64.25506);
        assertThat(area.bbox().north()).isEqualTo(-2.90258);
        assertThat(area.center()).isEqualTo(new GeoPoint(-64.75, -3.40));
        assertThat(area.radiusKm()).isEqualTo(55.0);
    }

    @Test
    void onlyThePixelsInsideTheCircleBelongToTheArea() {
        AreaFile area = TestArea.area();

        assertThat(area.contains(-64.75, -3.40)).isTrue();
        assertThat(area.contains(-65.24, -2.91)).isFalse(); // a corner of the square
    }

    @Test
    void aBoxAreaUsesItsBbox() {
        String box = """
                {"id": "box", "name": "Box", "bbox": [-65.0, -4.0, -64.0, -3.0],
                 "hub": {"name": "Hub", "lon": -64.5, "lat": -3.5, "radius_m": 500},
                 "access_radius_m": 500, "min_coverage": 0.8, "water_db": -15.0,
                 "widths_m": [40], "safe_width_m": 60}
                """;
        AreaFileReader reader =
                new AreaFileReader(TestArea.JSON, List.of("box.json"), name -> box.getBytes(StandardCharsets.UTF_8));

        AreaFile area = reader.find("box").orElseThrow();

        assertThat(area.circle()).isNull();
        assertThat(area.center()).isEqualTo(new GeoPoint(-64.5, -3.5));
        assertThat(area.radiusKm()).isCloseTo(55.6, within(0.1));
        assertThat(area.contains(-64.1, -3.1)).isTrue();
        assertThat(area.communities()).isEmpty();
    }

    @Test
    void aBrokenAreaFileIsLeftOutAndTheOthersLoad() {
        Map<String, String> files = Map.of(
                "bad.json", "{\"id\": \"bad\"}",
                "tefe.json", TestArea.AREA,
                "tefe_communities.geojson", TestArea.COMMUNITIES);
        AreaFileReader reader = new AreaFileReader(
                TestArea.JSON,
                List.of("tefe.json", "bad.json"),
                name -> files.get(name).getBytes(StandardCharsets.UTF_8));

        assertThat(reader.ids()).containsExactly("tefe");
    }

    @Test
    void readsTheAreaFilesOfTheClasspath() throws Exception {
        AreaFileReader reader = AreaFileReader.fromClasspath(TestArea.JSON);

        assertThat(reader.ids()).contains("tefe");
        assertThat(reader.find("tefe").orElseThrow().communities()).isNotEmpty();
    }
}
