package com.riverlifeline.pipeline.infrastructure.adapter.out.planetarycomputer;

import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.pipeline.PipelineTestData;
import com.riverlifeline.pipeline.application.port.out.Sentinel1Scenes.Scene;
import com.riverlifeline.pipeline.domain.model.area.AreaGrid;
import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import com.riverlifeline.pipeline.infrastructure.CapturingSink;
import com.riverlifeline.pipeline.infrastructure.LocalServer;
import com.riverlifeline.pipeline.infrastructure.TestTiff;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.HttpCalls;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.HttpRangeChannel;
import com.riverlifeline.pipeline.infrastructure.json.ContractJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

class PlanetaryComputerSentinel1Test {
    private LocalServer server;
    private PlanetaryComputerSentinel1 sentinel1;
    private final float[][] image = new float[600][600];

    @BeforeEach
    void serve() throws Exception {
        server = new LocalServer();
        server.json("/token", "{\"msft:expiry\": \"2026-11-14T13:00:00Z\", \"token\": \"st=1&sig=abc\"}");
        server.json("/search", """
                {"features": [
                  {"id": "s1b", "properties": {"datetime": "2026-10-25T10:03:40Z"}, "assets": {"vv": {"href": "%s"}}},
                  {"id": "novv", "properties": {"datetime": "2026-10-25T10:03:40Z"}, "assets": {"vh": {"href": "x"}}}],
                 "links": [{"rel": "next", "method": "POST", "href": "%s", "body": {"token": "page2"}}]}""".formatted(server.url("/tile.tif"), server.url("/search2")));
        server.json("/search2", """
                {"features": [
                  {"id": "s1a", "properties": {"datetime": "2026-10-20T09:55:45Z"}, "assets": {"vv": {"href": "%s"}}}],
                 "links": []}""".formatted(server.url("/tile.tif")));
        // A 6 km tile of 10 m pixels around the test area's centre, in UTM 20S
        var transform = new CoordinateTransformFactory()
                .createTransform(
                        new CRSFactory().createFromName("EPSG:4326"), new CRSFactory().createFromName("EPSG:32720"));
        ProjCoordinate centre = transform.transform(new ProjCoordinate(-64.75, -3.40), new ProjCoordinate());
        double cornerX = Math.floor(centre.x / 10) * 10 - 3000;
        double cornerY = Math.floor(centre.y / 10) * 10 + 3000;
        for (int r = 0; r < 600; r++) {
            for (int c = 0; c < 600; c++) {
                image[r][c] = 0.001f * (r + 1) + c;
            }
        }
        server.file("/tile.tif", TestTiff.write(image, 64, cornerX, cornerY, 10, 32720, -32768f));
        HttpCalls http = new HttpCalls(HttpClient.newHttpClient(), "test", Duration.ofSeconds(10));
        HttpClient ranges = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        sentinel1 = new PlanetaryComputerSentinel1(
                http,
                ContractJson.mapper(),
                URI.create(server.url("/search")),
                URI.create(server.url("/token")),
                href -> new HttpRangeChannel(ranges, href, null, "test", 4096, 16),
                Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void theSearchFollowsEveryPageAndKeepsTheScenesWithVv() throws Exception {
        List<Scene> scenes = sentinel1.search(
                PipelineTestData.GRID.bbox(), LocalDate.parse("2026-10-19"), LocalDate.parse("2026-10-27"));

        assertThat(scenes).extracting(Scene::id).containsExactly("s1a", "s1b");
        assertThat(scenes.get(0).date()).isEqualTo(LocalDate.parse("2026-10-20"));
        var search = server.requestsTo("/search").get(0).body();
        assertThat(search)
                .contains("\"collections\":[\"sentinel-1-rtc\"]", "2026-10-19T00:00:00Z/2026-10-27T23:59:59Z");
        assertThat(server.requestsTo("/search2").get(0).body()).isEqualTo("{\"token\":\"page2\"}");
    }

    @Test
    void theTileIsSignedOnceAndCroppedToTheAreaAtItsPixelSize() throws Exception {
        AreaGrid grid = PipelineTestData.GRID;
        CapturingSink sink = new CapturingSink();
        Scene scene = new Scene("s1a", LocalDate.parse("2026-10-20"), server.url("/tile.tif"));

        Optional<RasterRef> ref = sentinel1.read(scene, grid, sink);
        sentinel1.read(scene, grid, new CapturingSink());

        assertThat(ref).isPresent();
        var meta = ref.get().meta();
        assertThat(meta.dtype()).isEqualTo(DataType.FLOAT32);
        assertThat(meta.unit()).isEqualTo(RasterUnit.LINEAR_POWER);
        assertThat(meta.nodata()).isEqualTo(NoData.value(-32768));
        assertThat(meta.crs()).isEqualTo("EPSG:32720");
        assertThat(meta.step().x()).isEqualTo(20);
        assertThat(meta.width()).isBetween(100, 130);
        assertThat(server.requestsTo("/token")).hasSize(1);
        assertThat(server.requestsTo("/tile.tif")).allMatch(r -> "st=1&sig=abc".equals(r.query()));
        float[][] values = sink.floats(ref.get());
        // Overview pixels take every other pixel of the image: neighbours differ by 2 columns
        assertThat(values[0][0]).isGreaterThan(0f);
        assertThat(values[3][5] - values[3][4]).isCloseTo(2f, org.assertj.core.api.Assertions.within(1e-3f));
        assertThat(values[4][5] - values[3][5]).isCloseTo(0.002f, org.assertj.core.api.Assertions.within(1e-4f));
    }

    @Test
    void aSceneThatMissesTheAreaGivesNothing() throws Exception {
        AreaGrid far = AreaGrid.of(null, new com.riverlifeline.pipeline.domain.model.area.Circle(-60, -3.4, 1), 0.0009);

        assertThat(sentinel1.read(
                        new Scene("x", LocalDate.parse("2026-10-20"), server.url("/tile.tif")),
                        far,
                        new CapturingSink()))
                .isEmpty();
    }
}
