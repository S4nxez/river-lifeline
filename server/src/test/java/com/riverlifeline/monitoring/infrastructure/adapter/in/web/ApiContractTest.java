package com.riverlifeline.monitoring.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Every endpoint of contract 2 on the repository's fixtures ({@code RIVER_DATA_DIR=../fixtures/data}). */
@SpringBootTest(
        properties = {
            WithoutDatabase.PROPERTY,
            WithoutDatabase.NO_PIPELINE,
            "river.data-dir=../fixtures/data",
            "river.cors-origins=http://localhost:5500"
        })
@AutoConfigureMockMvc
class ApiContractTest {
    private static final String TEFE = "/api/v1/aois/tefe";
    private static final String LATEST = "nisar-20261023t094512z-t081-f012";
    private static final String PARTIAL = "nisar-20261019t094455z-t081-f013";

    @Autowired
    MockMvc mvc;

    @Test
    void health() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Schema-Version", "1.0.0"))
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.api_version").value("1.0.0"))
                .andExpect(jsonPath("$.catalog.loaded").value(true))
                .andExpect(jsonPath("$.catalog.schema_version").value("1.0.0"))
                .andExpect(jsonPath("$.catalog.generated_at").value("2026-10-24T03:12:08Z"));
    }

    @Test
    void meta() throws Exception {
        mvc.perform(get("/api/v1/meta"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.method_version").value("pr26"))
                .andExpect(jsonPath("$.status_table[*].code")
                        .value(contains("isolated", "at_risk", "connected", "no_data")))
                .andExpect(jsonPath("$.status_table[0].shape").value("square"))
                .andExpect(jsonPath("$.reasons.length()").value(8))
                .andExpect(jsonPath("$.reasons[0].label").value("Route ≥ 60 m"))
                .andExpect(jsonPath("$.reasons[7].label").value("Pass covers < 80 % of the area"))
                .andExpect(jsonPath("$.layers[*].id")
                        .value(contains("radar_hh", "sentinel1_vv", "water", "flooded_vegetation", "change", "nodata")))
                .andExpect(jsonPath("$.layers[1].default_visible").value(false))
                .andExpect(jsonPath("$.layers[4].default_visible").value(false));
    }

    @Test
    void aois() throws Exception {
        mvc.perform(get("/api/v1/aois"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].id").value("tefe"))
                .andExpect(jsonPath("$.items[0].radius_km").value(55))
                .andExpect(jsonPath("$.items[0].center").value(contains(-64.75, -3.4)))
                .andExpect(jsonPath("$.items[0].bbox.length()").value(4));
    }

    @Test
    void aoi() throws Exception {
        mvc.perform(get(TEFE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destination.name").value("Port of Tefé"))
                .andExpect(jsonPath("$.river_gauge.station_code").value("12900001"))
                .andExpect(jsonPath("$.parameters.connected_min_width_m").value(60))
                .andExpect(jsonPath("$.parameters.tested_widths_m").value(contains(40, 60, 100, 200, 400)))
                .andExpect(jsonPath("$.pass_count").value(4))
                .andExpect(jsonPath("$.evaluated_pass_count").value(3))
                .andExpect(jsonPath("$.first_pass_at").value("2026-07-04T21:40:11Z"))
                .andExpect(jsonPath("$.latest_pass_at").value("2026-10-23T09:45:12Z"));
    }

    @Test
    void catalogVersion() throws Exception {
        mvc.perform(get(TEFE + "/catalog-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aoi_id").value("tefe"))
                .andExpect(jsonPath("$.etag").value(startsWith("\"")))
                .andExpect(jsonPath("$.latest_pass_id").value(LATEST))
                .andExpect(jsonPath("$.latest_evaluated_pass_id").value(LATEST));
    }

    @Test
    void summary() throws Exception {
        mvc.perform(get(TEFE + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-23"))
                .andExpect(jsonPath("$.pass_id").value(LATEST))
                .andExpect(jsonPath("$.headline").value("2 communities have lost the water route to Tefé."))
                .andExpect(jsonPath("$.headline_level").value("critical"))
                .andExpect(jsonPath("$.counts.isolated").value(2))
                .andExpect(jsonPath("$.river_level_m").value(8.0))
                .andExpect(jsonPath("$.low_water_rule_active").value(true));
        mvc.perform(get(TEFE + "/summary").param("date", "2026-10-20"))
                .andExpect(jsonPath("$.pass_id").value("nisar-20261015t214033z-t154-f088"))
                .andExpect(jsonPath("$.headline_level").value("warning"));
        mvc.perform(get(TEFE + "/summary").param("date", "2026-01-01"))
                .andExpect(jsonPath("$.headline").value("No evaluated radar pass yet."))
                .andExpect(jsonPath("$", hasKey("counts")))
                .andExpect(jsonPath("$.counts").value(nullValue()));
    }

    @Test
    void passes() throws Exception {
        mvc.perform(get(TEFE + "/passes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.items[0].previous_pass_id").value(nullValue()))
                .andExpect(jsonPath("$.items[2].id").value(PARTIAL))
                .andExpect(jsonPath("$.items[2].evaluated").value(false))
                .andExpect(jsonPath("$.items[2].counts.monitored").value(10))
                .andExpect(jsonPath("$.items[2].counts.connected").value(nullValue()))
                .andExpect(jsonPath("$.items[3].previous_pass_id").value(PARTIAL))
                .andExpect(jsonPath("$.items[3].pixel_size_m").value(20));
        mvc.perform(get(TEFE + "/passes").param("evaluated", "true").param("from", "2026-10-01"))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void passDetail() throws Exception {
        mvc.perform(get(TEFE + "/passes/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(LATEST))
                .andExpect(jsonPath("$.counts.monitored").value(10))
                .andExpect(jsonPath("$.image_card.satellite").value("NISAR (NASA–ISRO)"))
                .andExpect(jsonPath("$.image_card.wavelength_cm").value(24))
                .andExpect(jsonPath("$.image_card.data_source_url").value("https://search.asf.alaska.edu/"))
                .andExpect(jsonPath("$.low_water_rule.unverified_area_km2").value(4.2))
                .andExpect(jsonPath("$.layers.length()").value(5))
                .andExpect(
                        jsonPath("$.layers[1].url").value("/api/v1/aois/tefe/passes/" + LATEST + "/layers/water.png"))
                .andExpect(jsonPath("$.layers[1].bounds.length()").value(4));
        mvc.perform(get(TEFE + "/passes/" + PARTIAL))
                .andExpect(jsonPath("$.layers[*].id").value(not(contains("flooded_vegetation"))))
                .andExpect(jsonPath("$.layers.length()").value(4));
    }

    @Test
    void communitiesOnAPass() throws Exception {
        mvc.perform(get(TEFE + "/passes/latest/communities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FeatureCollection"))
                .andExpect(jsonPath("$.pass_id").value(LATEST))
                .andExpect(jsonPath("$.count").value(10))
                .andExpect(jsonPath("$.features[0].properties.status").value("isolated"))
                .andExpect(jsonPath("$.features[0].geometry.type").value("Point"))
                .andExpect(jsonPath("$.features[0].properties", not(hasKey("route"))));
        // The geometry is the boarding point; the village is a property
        mvc.perform(get(TEFE + "/passes/latest/communities").param("q", "ALVARAES"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.features[0].geometry.coordinates").value(contains(-64.5905, -3.316)))
                .andExpect(jsonPath("$.features[0].properties.village_location").value(contains(-64.6012, -3.3215)))
                .andExpect(jsonPath("$.features[0].properties.boarding_point.source")
                        .value("osm_port"))
                .andExpect(jsonPath("$.features[0].properties.bottleneck.width_class_m")
                        .value(200));
        mvc.perform(get(TEFE + "/passes/latest/communities").param("status", "isolated,at_risk"))
                .andExpect(jsonPath("$.count").value(5))
                .andExpect(jsonPath("$.features[*].properties.status").value(everyItem(not(is("connected")))));
        mvc.perform(get(TEFE + "/passes/latest/communities").param("changed", "true"))
                .andExpect(jsonPath("$.count").value(5));
        mvc.perform(get(TEFE + "/passes/" + PARTIAL + "/communities"))
                .andExpect(jsonPath("$.features[*].properties.status").value(everyItem(is("no_data"))))
                .andExpect(jsonPath("$.features[*].properties.reason").value(everyItem(is("pass_not_evaluated"))));
    }

    @Test
    void community() throws Exception {
        mvc.perform(get(TEFE + "/communities/manual-caiambe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monitored").value(true))
                .andExpect(jsonPath("$.boarding_point.source").value("village_center"))
                .andExpect(jsonPath("$.current.pass_id").value(LATEST))
                .andExpect(jsonPath("$.current.status").value("isolated"))
                .andExpect(jsonPath("$.current.route").value(nullValue()))
                .andExpect(jsonPath("$.history[*].status").value(contains("connected", "at_risk", "isolated")))
                .andExpect(jsonPath("$.history[1].river_level_m").value(8.6));
        mvc.perform(get(TEFE + "/communities/osm-node-2984471123").param("pass_id", "nisar-20260704t214011z-t154-f088"))
                .andExpect(jsonPath("$.current.status").value("connected"))
                .andExpect(jsonPath("$.current.route.type").value("LineString"));
        mvc.perform(get(TEFE + "/communities/osm-node-3011223350"))
                .andExpect(jsonPath("$.monitored").value(false))
                .andExpect(jsonPath("$.exclusion_reason").value("terra_firme"))
                .andExpect(jsonPath("$.current").value(nullValue()));
    }

    @Test
    void riverLevel() throws Exception {
        mvc.perform(get(TEFE + "/river-level").param("from", "2026-10-15").param("to", "2026-10-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("ANA"))
                .andExpect(jsonPath("$.low_water_threshold_m").value(12.0))
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.items[0].date").value("2026-10-15"));
    }

    @Test
    void layerPngs() throws Exception {
        mvc.perform(get(TEFE + "/passes/" + LATEST + "/layers/water.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public, immutable"));
        mvc.perform(get(TEFE + "/passes/latest/layers/water.png"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
        mvc.perform(get(TEFE + "/passes/" + PARTIAL + "/layers/flooded_vegetation.png"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("layer_not_found"));
    }

    @Test
    void exports() throws Exception {
        mvc.perform(get(TEFE + "/passes/latest/export").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string(
                                HttpHeaders.CONTENT_DISPOSITION,
                                containsString("river-lifeline_tefe_2026-10-23_" + LATEST + ".csv")))
                .andExpect(
                        content()
                                .string(
                                        startsWith(
                                                "community_id,name,municipality,status,reason,min_width_class_m,route_verified,changed,"
                                                        + "previous_status,boarding_lon,boarding_lat,pass_id,acquired_at,river_level_m\r\n"
                                                        + "manual-caiambe,Caiambé,Tefé,isolated,no_route_confirmed,,true,true,at_risk,")));
        mvc.perform(get(TEFE + "/passes/latest/export").param("format", "geojson"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FeatureCollection"))
                .andExpect(jsonPath("$.count").value(10))
                .andExpect(jsonPath("$.attribution").value(startsWith("Contains NISAR data")));
        mvc.perform(get(TEFE + "/passes/latest/export").param("format", "xlsx"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_parameter"));
    }

    @Test
    void errorsFollowTheContractFormat() throws Exception {
        mvc.perform(get("/api/v1/aois/coari"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Schema-Version", "1.0.0"))
                .andExpect(jsonPath("$.error.code").value("aoi_not_found"))
                .andExpect(jsonPath("$.error.message").value("AOI 'coari' does not exist."))
                .andExpect(jsonPath("$.error", hasKey("details")));
        mvc.perform(get(TEFE + "/passes/nisar-20990101t000000z-t001-f001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("pass_not_found"));
        mvc.perform(get(TEFE + "/communities/osm-node-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("community_not_found"));
        mvc.perform(get(TEFE + "/summary").param("date", "23/10/2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_parameter"))
                .andExpect(jsonPath("$.error.details.parameter").value("date"));
        mvc.perform(get(TEFE + "/passes/latest/communities").param("status", "drowned"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(TEFE + "/passes").param("evaluated", "maybe")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        mvc.perform(post(TEFE + "/passes")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void anUnchangedAnswerIsA304() throws Exception {
        MvcResult first = mvc.perform(get(TEFE + "/passes")).andReturn();
        String etag = first.getResponse().getHeader(HttpHeaders.ETAG);

        mvc.perform(get(TEFE + "/passes").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
        mvc.perform(get("/api/v1/health")).andExpect(header().doesNotExist(HttpHeaders.ETAG));
    }

    @Test
    void corsAllowsTheConfiguredFrontOriginToRead() throws Exception {
        mvc.perform(get(TEFE + "/summary").header(HttpHeaders.ORIGIN, "http://localhost:5500"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5500"));
        mvc.perform(get(TEFE + "/summary").header(HttpHeaders.ORIGIN, "http://evil.example"))
                .andExpect(status().isForbidden());
    }
}
