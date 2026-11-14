package com.riverlifeline.pipeline.infrastructure.adapter.out.asf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.pipeline.application.port.out.PassSearch;
import com.riverlifeline.pipeline.domain.model.area.Bbox;
import com.riverlifeline.pipeline.domain.model.pass.GranuleRef;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.HttpCalls;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** NISAR L2 GCOV granules over a box from the ASF DAAC search API (no account needed). */
public class AsfPassSearch implements PassSearch {
    private static final Logger log = LoggerFactory.getLogger(AsfPassSearch.class);

    private final HttpCalls http;
    private final ObjectMapper json;
    private final String searchUrl;

    public AsfPassSearch(HttpCalls http, ObjectMapper json, String searchUrl) {
        this.http = http;
        this.json = json;
        this.searchUrl = searchUrl;
    }

    @Override
    public List<GranuleRef> granules(Bbox bbox) throws IOException {
        String polygon = String.format(
                java.util.Locale.ROOT,
                "POLYGON((%s %s,%s %s,%s %s,%s %s,%s %s))",
                bbox.lon0(),
                bbox.lat0(),
                bbox.lon1(),
                bbox.lat0(),
                bbox.lon1(),
                bbox.lat1(),
                bbox.lon0(),
                bbox.lat1(),
                bbox.lon0(),
                bbox.lat0());
        URI uri = URI.create(searchUrl
                + "?dataset=NISAR&processingLevel=GCOV&output=geojson&maxResults=5000&intersectsWith="
                + URLEncoder.encode(polygon, StandardCharsets.UTF_8));
        return parse(json.readTree(http.get(uri)));
    }

    List<GranuleRef> parse(JsonNode answer) {
        List<GranuleRef> granules = new ArrayList<>();
        for (JsonNode feature : answer.path("features")) {
            JsonNode p = feature.path("properties");
            String name = p.path("fileName").asText("");
            String url = p.path("url").asText("");
            if (!name.endsWith(".h5") || url.isEmpty()) {
                continue;
            }
            try {
                granules.add(
                        GranuleRef.parse(name, url, p.path("collectionName").asText("")));
            } catch (IllegalArgumentException e) {
                log.debug("Skipped {}: {}", name, e.getMessage());
            }
        }
        return granules;
    }
}
