package com.riverlifeline.monitoring.domain.service;

import static com.riverlifeline.monitoring.TestData.assessment;
import static com.riverlifeline.monitoring.TestData.pass;
import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.monitoring.domain.model.community.CommunityId;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.status.CommunityAssessment;
import com.riverlifeline.monitoring.domain.model.status.PassStatuses;
import com.riverlifeline.monitoring.domain.model.status.Reason;
import com.riverlifeline.monitoring.domain.model.status.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommunityStatusResolverTest {
    private final Pass evaluated = pass("p-1", "2026-10-23T09:45:12Z", true);
    private final Pass partial = pass("p-2", "2026-10-19T09:44:55Z", false);
    private final CommunityAssessment connected = assessment("a", Status.CONNECTED, Reason.ROUTE_GE_60M, 200);

    @Test
    void anEvaluatedPassShowsWhatProcessingPublished() {
        PassStatuses statuses = new PassStatuses(evaluated.id(), null, true, List.of(connected));

        assertThat(CommunityStatusResolver.resolve(evaluated, statuses, CommunityId.of("a"), 60))
                .isEqualTo(connected);
    }

    @Test
    void aCommunityThePassDoesNotListHasNoData() {
        PassStatuses statuses = new PassStatuses(evaluated.id(), null, true, List.of(connected));

        CommunityAssessment missing = CommunityStatusResolver.resolve(evaluated, statuses, CommunityId.of("b"), 60);

        assertThat(missing.status()).isEqualTo(Status.NO_DATA);
        assertThat(missing.reason()).isEqualTo(Reason.OUTSIDE_COVERAGE);
    }

    @Test
    void aPassBelowMinimumCoverageClaimsNothingEvenIfTheFileDoes() {
        PassStatuses statuses = new PassStatuses(partial.id(), null, true, List.of(connected));

        CommunityAssessment shown = CommunityStatusResolver.resolve(partial, statuses, CommunityId.of("a"), 60);

        assertThat(shown.status()).isEqualTo(Status.NO_DATA);
        assertThat(shown.reason()).isEqualTo(Reason.PASS_NOT_EVALUATED);
    }

    @Test
    void aStatusesFileThatSaysItWasNotEvaluatedWinsOverTheCatalog() {
        PassStatuses statuses = new PassStatuses(evaluated.id(), null, false, List.of(connected));

        assertThat(CommunityStatusResolver.resolve(evaluated, statuses, CommunityId.of("a"), 60)
                        .status())
                .isEqualTo(Status.NO_DATA);
    }

    @Test
    void anUnsupportedConnectedIsDegraded() {
        PassStatuses statuses = new PassStatuses(
                evaluated.id(), null, true, List.of(assessment("a", Status.CONNECTED, Reason.ROUTE_GE_60M, 40)));

        assertThat(CommunityStatusResolver.resolve(evaluated, statuses, CommunityId.of("a"), 60)
                        .status())
                .isEqualTo(Status.AT_RISK);
    }
}
