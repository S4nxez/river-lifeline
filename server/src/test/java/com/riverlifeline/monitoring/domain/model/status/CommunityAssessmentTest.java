package com.riverlifeline.monitoring.domain.model.status;

import static com.riverlifeline.monitoring.TestData.assessment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riverlifeline.monitoring.TestData;
import com.riverlifeline.monitoring.domain.model.aoi.AoiParameters;
import com.riverlifeline.monitoring.domain.model.community.CommunityId;
import com.riverlifeline.monitoring.domain.model.pass.PassId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommunityAssessmentTest {

    @Test
    void aConnectedRouteWithItsEvidenceIsKept() {
        CommunityAssessment connected = assessment("a", Status.CONNECTED, Reason.ROUTE_GE_60M, 100);

        assertThat(connected.degradedIfUnsupported(60)).isSameAs(connected);
    }

    @Test
    void aConnectedRouteNarrowerThanTheSafeWidthDropsToAtRisk() {
        CommunityAssessment degraded =
                assessment("a", Status.CONNECTED, Reason.ROUTE_GE_60M, 40).degradedIfUnsupported(60);

        assertThat(degraded.status()).isEqualTo(Status.AT_RISK);
        assertThat(degraded.reason()).isEqualTo(Reason.NARROW_ROUTE);
    }

    @Test
    void theSafeWidthIsTheAreasNotAFixedSixtyMetres() {
        CommunityAssessment route100 = assessment("a", Status.CONNECTED, Reason.ROUTE_GE_60M, 100);

        assertThat(route100.degradedIfUnsupported(100).status()).isEqualTo(Status.CONNECTED);
        assertThat(route100.degradedIfUnsupported(200).status()).isEqualTo(Status.AT_RISK);
    }

    @Test
    void anUnverifiedConnectedRouteDropsToAtRiskUnverified() {
        CommunityAssessment unverified = new CommunityAssessment(
                CommunityId.of("a"),
                Status.CONNECTED,
                Reason.ROUTE_GE_60M,
                200,
                false,
                Status.CONNECTED,
                PassId.of("p-1"),
                false,
                null,
                null);

        CommunityAssessment degraded = unverified.degradedIfUnsupported(60);

        assertThat(degraded.status()).isEqualTo(Status.AT_RISK);
        assertThat(degraded.reason()).isEqualTo(Reason.ROUTE_UNVERIFIED);
        assertThat(degraded.changed()).as("it changed from connected").isTrue();
    }

    @Test
    void connectedWithoutAnyRouteOrWithAnotherReasonIsNeverShown() {
        assertThat(assessment("a", Status.CONNECTED, null, null)
                        .degradedIfUnsupported(60)
                        .reason())
                .isEqualTo(Reason.NO_ROUTE_UNCONFIRMED);
        assertThat(assessment("a", Status.CONNECTED, Reason.NARROW_ROUTE, 100)
                        .degradedIfUnsupported(60)
                        .status())
                .isEqualTo(Status.AT_RISK);
    }

    @Test
    void worseStatusesAreNeverTouched() {
        CommunityAssessment isolated = assessment("a", Status.ISOLATED, Reason.NO_ROUTE_CONFIRMED, null);

        assertThat(isolated.degradedIfUnsupported(60)).isSameAs(isolated);
    }

    @Test
    void onAPassThatWasNotEvaluatedNothingIsClaimed() {
        CommunityAssessment notEvaluated =
                assessment("a", Status.CONNECTED, Reason.ROUTE_GE_60M, 200).asNotEvaluated();

        assertThat(notEvaluated.status()).isEqualTo(Status.NO_DATA);
        assertThat(notEvaluated.reason()).isEqualTo(Reason.PASS_NOT_EVALUATED);
        assertThat(notEvaluated.minWidthClassM()).isNull();
        assertThat(notEvaluated.route()).isNull();
    }

    @Test
    void noDataNeedsANoDataReason() {
        assertThat(CommunityAssessment.noData(CommunityId.of("a"), Reason.OUTSIDE_COVERAGE)
                        .status())
                .isEqualTo(Status.NO_DATA);
        assertThatThrownBy(() -> CommunityAssessment.noData(CommunityId.of("a"), Reason.ROUTE_GE_60M))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reasonLabelsNameTheAreasThresholds() {
        AoiParameters strict = new AoiParameters(List.of(100, 200), 100, 100, 0.9, 10, -16, -14, -40, 500);

        assertThat(Reason.ROUTE_GE_60M.labelFor(TestData.PARAMETERS)).isEqualTo("Route ≥ 60 m");
        assertThat(Reason.ROUTE_GE_60M.labelFor(strict)).isEqualTo("Route ≥ 100 m");
        assertThat(Reason.NARROW_ROUTE.labelFor(strict)).isEqualTo("Only channels narrower than 100 m");
        assertThat(Reason.PASS_NOT_EVALUATED.labelFor(strict)).isEqualTo("Pass covers < 90 % of the area");
        assertThat(Reason.NO_ROUTE_CONFIRMED.labelFor(strict)).isEqualTo("No water route (confirmed)");
    }

    @Test
    void everyReasonImpliesItsContractStatus() {
        assertThat(Reason.fromCode("route_unverified")).contains(Reason.ROUTE_UNVERIFIED);
        assertThat(Reason.ROUTE_UNVERIFIED.status()).isEqualTo(Status.AT_RISK);
        assertThat(Reason.NO_ROUTE_CONFIRMED.status()).isEqualTo(Status.ISOLATED);
        assertThat(Reason.fromCode("made_up")).isEmpty();
    }
}
