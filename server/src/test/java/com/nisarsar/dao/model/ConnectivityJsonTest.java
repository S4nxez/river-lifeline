package com.nisarsar.dao.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nisarsar.dao.model.Connectivity.Assessment;
import com.nisarsar.dao.model.Connectivity.CrossCheck;
import org.junit.jupiter.api.Test;

/** The JSON the frontend reads: lowercase codes for statuses and kinds, ISO dates. */
class ConnectivityJsonTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void statusesAndKindsAreLowercaseCodes() throws Exception {
        assertThat(json.writeValueAsString(ConnectivityStatus.AT_RISK)).isEqualTo("\"at_risk\"");
        assertThat(json.writeValueAsString(ConnectivityStatus.NO_DATA)).isEqualTo("\"no_data\"");
        assertThat(json.writeValueAsString(CommunityKind.VILLAGE)).isEqualTo("\"village\"");
    }

    @Test
    void anAssessmentSerializesWithItsCrossCheck() throws Exception {
        Assessment assessment =
                new Assessment("p2", "2026-08-16", ConnectivityStatus.AT_RISK, 40, new CrossCheck("2026-08-15", true));

        assertThat(json.writeValueAsString(assessment))
                .isEqualTo("{\"passId\":\"p2\",\"acquiredOn\":\"2026-08-16\",\"status\":\"at_risk\",\"routeWidthM\":40,"
                        + "\"sentinel1\":{\"sceneOn\":\"2026-08-15\",\"agrees\":true}}");
    }
}
