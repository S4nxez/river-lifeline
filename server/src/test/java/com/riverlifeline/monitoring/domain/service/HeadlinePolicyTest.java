package com.riverlifeline.monitoring.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.monitoring.domain.model.pass.StatusCounts;
import org.junit.jupiter.api.Test;

/** The rules of CONTRACTS.md 3.3 #6, in their order. */
class HeadlinePolicyTest {

    private static Headline headline(int monitored, int atRisk, int isolated) {
        return HeadlinePolicy.headlineFor(
                "Tefé", new StatusCounts(monitored, monitored - atRisk - isolated, atRisk, isolated, 0, 0));
    }

    @Test
    void noEvaluatedPass() {
        assertThat(HeadlinePolicy.headlineFor("Tefé", null))
                .isEqualTo(new Headline("No evaluated radar pass yet.", HeadlineLevel.INFO));
    }

    @Test
    void isolatedCommunitiesComeFirst() {
        assertThat(headline(132, 19, 1))
                .isEqualTo(new Headline("1 community has lost the water route to Tefé.", HeadlineLevel.CRITICAL));
        assertThat(headline(132, 19, 9))
                .isEqualTo(new Headline("9 communities have lost the water route to Tefé.", HeadlineLevel.CRITICAL));
    }

    @Test
    void thenCommunitiesAtRisk() {
        assertThat(headline(132, 1, 0))
                .isEqualTo(new Headline(
                        "1 community is at risk of losing the water route to Tefé.", HeadlineLevel.WARNING));
        assertThat(headline(132, 19, 0))
                .isEqualTo(new Headline(
                        "19 communities are at risk of losing the water route to Tefé.", HeadlineLevel.WARNING));
    }

    @Test
    void otherwiseEveryoneIsConnected() {
        assertThat(headline(132, 0, 0))
                .isEqualTo(new Headline("All 132 monitored communities can reach Tefé by water.", HeadlineLevel.OK));
    }

    @Test
    void theTownIsTheAreasNotAFixedOne() {
        assertThat(HeadlinePolicy.headlineFor("Coari", new StatusCounts(3, 2, 0, 1, 0, 0))
                        .text())
                .isEqualTo("1 community has lost the water route to Coari.");
    }
}
