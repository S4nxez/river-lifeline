package com.riverlifeline.pipeline.infrastructure.adapter.out.planetarycomputer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riverlifeline.pipeline.application.port.out.RasterSink;
import com.riverlifeline.pipeline.application.port.out.Sentinel1Scenes;
import com.riverlifeline.pipeline.domain.model.area.AreaGrid;
import com.riverlifeline.pipeline.domain.model.area.Bbox;
import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import com.riverlifeline.pipeline.domain.model.raster.XY;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.HttpCalls;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.ProductWindow;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.RangeReader;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Sentinel-1 RTC from Microsoft Planetary Computer: STAC search of {@code sentinel-1-rtc}, the anonymous SAS token that
 * signs the asset URLs (cached until shortly before it expires), and the VV COG window over an area.
 */
public class PlanetaryComputerSentinel1 implements Sentinel1Scenes {
    static final String COLLECTION = "sentinel-1-rtc";
    static final int PAD_PIXELS = 8;

    /** Opens a signed asset URL for range reads. */
    @FunctionalInterface
    public interface Opener {
        RangeReader open(URI signedHref) throws IOException;
    }

    private final HttpCalls http;
    private final ObjectMapper json;
    private final URI searchUrl;
    private final URI tokenUrl;
    private final Opener opener;
    private final Clock clock;
    private String token;
    private Instant tokenExpiry = Instant.MIN;

    public PlanetaryComputerSentinel1(
            HttpCalls http, ObjectMapper json, URI searchUrl, URI tokenUrl, Opener opener, Clock clock) {
        this.http = http;
        this.json = json;
        this.searchUrl = searchUrl;
        this.tokenUrl = tokenUrl;
        this.opener = opener;
        this.clock = clock;
    }

    @Override
    public List<Scene> search(Bbox bbox, LocalDate from, LocalDate to) throws IOException {
        ObjectNode body = json.createObjectNode();
        body.putArray("collections").add(COLLECTION);
        body.putArray("bbox").add(bbox.lon0()).add(bbox.lat0()).add(bbox.lon1()).add(bbox.lat1());
        body.put("datetime", from + "T00:00:00Z/" + to + "T23:59:59Z");
        body.put("limit", 100);
        List<Scene> scenes = new ArrayList<>();
        JsonNode page = json.readTree(http.postJson(searchUrl, json.writeValueAsString(body)));
        for (int pages = 0; page != null && pages < 20; pages++) {
            for (JsonNode item : page.path("features")) {
                JsonNode vv = item.path("assets").path("vv").path("href");
                String datetime = item.path("properties").path("datetime").asText("");
                if (vv.isTextual() && datetime.length() >= 10) {
                    scenes.add(new Scene(
                            item.path("id").asText(), LocalDate.parse(datetime.substring(0, 10)), vv.asText()));
                }
            }
            page = nextPage(page);
        }
        scenes.sort(Comparator.comparing(Scene::date).thenComparing(Scene::id));
        return scenes;
    }

    private JsonNode nextPage(JsonNode page) throws IOException {
        for (JsonNode link : page.path("links")) {
            if ("next".equals(link.path("rel").asText())) {
                URI href = URI.create(link.path("href").asText());
                if ("POST".equalsIgnoreCase(link.path("method").asText("GET")) && link.has("body")) {
                    return json.readTree(http.postJson(href, json.writeValueAsString(link.get("body"))));
                }
                return json.readTree(http.get(href));
            }
        }
        return null;
    }

    @Override
    public Optional<RasterRef> read(Scene scene, AreaGrid grid, RasterSink sink) throws IOException {
        RangeReader file = opener.open(URI.create(sign(scene.vvHref())));
        Cog cog = Cog.open(file);
        int level = cog.levelFor(grid.pixelM());
        double dx = cog.stepX(level);
        double dy = -cog.stepY(level);
        ProductWindow w = ProductWindow.of(
                grid.bbox(),
                cog.epsg(),
                cog.originX(level),
                cog.originY(level),
                dx,
                dy,
                cog.width(level),
                cog.height(level),
                PAD_PIXELS);
        if (w.width() < 1 || w.height() < 1 || !cog.hasData(level, w)) {
            return Optional.empty();
        }
        NoData nodata = cog.nodata() == null ? NoData.NONE : NoData.parse(cog.nodata());
        float fill = nodata instanceof NoData.Value v ? (float) v.value() : Float.NaN;
        RasterMeta meta = new RasterMeta(
                w.width(),
                w.height(),
                DataType.FLOAT32,
                nodata,
                RasterUnit.LINEAR_POWER,
                "EPSG:" + cog.epsg(),
                new XY(cog.originX(level) + w.c0() * dx, cog.originY(level) + w.r0() * dy),
                new XY(dx, dy));
        return Optional.of(sink.write(meta, out -> cog.writeWindow(level, w, fill, out)));
    }

    /** The asset URL with the collection's SAS token. */
    synchronized String sign(String href) throws IOException {
        if (token == null || !clock.instant().isBefore(tokenExpiry)) {
            JsonNode answer = json.readTree(http.get(tokenUrl));
            token = answer.path("token").asText(null);
            if (token == null || token.isBlank()) {
                throw new IOException("Planetary Computer gave no access token");
            }
            String expiry = answer.path("msft:expiry").asText(null);
            Instant expires = expiry == null ? clock.instant().plusSeconds(1800) : Instant.parse(expiry);
            tokenExpiry = expires.minusSeconds(300);
        }
        return href + (href.contains("?") ? "&" : "?") + token;
    }
}
