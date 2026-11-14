package com.nisarsar.domain.services;

import static com.nisarsar.dao.model.CommunityKind.HUB;
import static com.nisarsar.dao.model.CommunityKind.TOWN;
import static com.nisarsar.dao.model.CommunityKind.VILLAGE;
import static com.nisarsar.dao.model.ConnectivityStatus.AT_RISK;
import static com.nisarsar.dao.model.ConnectivityStatus.CONNECTED;
import static com.nisarsar.dao.model.ConnectivityStatus.ISOLATED;
import static com.nisarsar.dao.model.ConnectivityStatus.NO_DATA;
import static com.nisarsar.dao.model.entities.EntityFixtures.assessment;
import static com.nisarsar.dao.model.entities.EntityFixtures.community;
import static com.nisarsar.dao.model.entities.EntityFixtures.crossChecked;
import static com.nisarsar.dao.model.entities.EntityFixtures.pass;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.nisarsar.dao.model.Connectivity.Assessment;
import com.nisarsar.dao.model.Connectivity.CommunityStatus;
import com.nisarsar.dao.model.Connectivity.CrossCheck;
import com.nisarsar.dao.model.Connectivity.Overview;
import com.nisarsar.dao.model.Connectivity.PassSummary;
import com.nisarsar.dao.model.Connectivity.StatusCounts;
import com.nisarsar.dao.model.entities.CommunityEntity;
import com.nisarsar.dao.model.entities.ConnectivityAssessmentEntity;
import com.nisarsar.dao.model.entities.RadarPassEntity;
import com.nisarsar.dao.repositories.CommunityRepository;
import com.nisarsar.dao.repositories.ConnectivityAssessmentRepository;
import com.nisarsar.dao.repositories.RadarPassRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConnectivityServiceTest {
    @Mock
    private CommunityRepository communities;

    @Mock
    private RadarPassRepository passes;

    @Mock
    private ConnectivityAssessmentRepository assessments;

    @InjectMocks
    private ConnectivityService service;

    private static final CommunityEntity TEFE = community("tefe", "Tefé", HUB);
    private static final CommunityEntity UARINI = community("uarini", "Uarini", TOWN);
    private static final CommunityEntity NOGUEIRA = community("nogueira", "Nogueira", VILLAGE);

    // Stored out of order on purpose: the service sorts by acquisition date
    private static final List<RadarPassEntity> PASSES =
            List.of(pass("p2", "2026-08-16", 1420), pass("p1", "2026-08-04", 1650), pass("p3", "2026-08-28", null));

    private void given(List<CommunityEntity> stored, List<ConnectivityAssessmentEntity> assessed) {
        lenient().when(communities.findAll()).thenReturn(stored);
        lenient().when(passes.findAll()).thenReturn(PASSES);
        lenient().when(assessments.findAll()).thenReturn(assessed);
    }

    @Test
    void theOverviewSeparatesTheHubFromTheMonitoredCommunities() {
        given(List.of(UARINI, TEFE, NOGUEIRA), List.of());

        Overview overview = service.overview();

        assertThat(overview.hub().name()).isEqualTo("Tefé");
        assertThat(overview.communities()).extracting(CommunityStatus::name).containsExactly("Nogueira", "Uarini");
    }

    @Test
    void eachCommunityShowsItsLatestAssessment() {
        given(
                List.of(TEFE, UARINI),
                List.of(
                        assessment("p3", "uarini", ISOLATED, null),
                        assessment("p1", "uarini", CONNECTED, 200),
                        assessment("p2", "uarini", AT_RISK, 40)));

        Assessment latest = service.overview().communities().getFirst().latest();

        assertThat(latest.passId()).isEqualTo("p3");
        assertThat(latest.acquiredOn()).isEqualTo("2026-08-28");
        assertThat(latest.status()).isEqualTo(ISOLATED);
    }

    @Test
    void aPassWithoutDataDoesNotHideTheLastKnownStatus() {
        given(
                List.of(UARINI),
                List.of(assessment("p2", "uarini", AT_RISK, 40), assessment("p3", "uarini", NO_DATA, null)));

        Assessment latest = service.overview().communities().getFirst().latest();

        assertThat(latest.status()).isEqualTo(AT_RISK);
        assertThat(latest.passId()).isEqualTo("p2");
    }

    @Test
    void aCommunityNoPassEverSawIsNoData() {
        given(List.of(UARINI), List.of(assessment("p1", "uarini", NO_DATA, null)));

        assertThat(service.overview().communities().getFirst().latest().status())
                .isEqualTo(NO_DATA);
    }

    @Test
    void aCommunityNeverAssessedHasNoLatestAssessment() {
        given(List.of(UARINI), List.of());

        assertThat(service.overview().communities().getFirst().latest()).isNull();
    }

    @Test
    void anAssessmentCarriesItsSentinel1CrossCheckWhenThereIsOne() {
        given(
                List.of(UARINI, NOGUEIRA),
                List.of(
                        crossChecked(assessment("p2", "uarini", AT_RISK, 40), "2026-08-15", false),
                        assessment("p2", "nogueira", CONNECTED, 100)));

        List<CommunityStatus> statuses = service.overview().communities();

        assertThat(statuses.get(1).latest().sentinel1()).isEqualTo(new CrossCheck("2026-08-15", false));
        assertThat(statuses.get(0).latest().sentinel1()).isNull();
    }

    @Test
    void theOverviewIncludesTheLatestPassWithItsCounts() {
        given(
                List.of(UARINI, NOGUEIRA),
                List.of(assessment("p3", "uarini", ISOLATED, null), assessment("p3", "nogueira", ISOLATED, null)));

        PassSummary latestPass = service.overview().latestPass();

        assertThat(latestPass.id()).isEqualTo("p3");
        assertThat(latestPass.counts()).isEqualTo(new StatusCounts(0, 0, 2, 0));
    }

    @Test
    void passesAreListedOldestFirstWithTheirRiverLevelAndCounts() {
        given(
                List.of(UARINI, NOGUEIRA),
                List.of(
                        assessment("p1", "uarini", CONNECTED, 200),
                        assessment("p1", "nogueira", AT_RISK, 40),
                        assessment("p2", "uarini", NO_DATA, null)));

        List<PassSummary> summaries = service.passes();

        assertThat(summaries).extracting(PassSummary::id).containsExactly("p1", "p2", "p3");
        assertThat(summaries.getFirst().riverLevelCm()).isEqualTo(1650);
        assertThat(summaries.getFirst().mission()).isEqualTo("NISAR");
        assertThat(summaries.get(0).counts()).isEqualTo(new StatusCounts(1, 1, 0, 0));
        assertThat(summaries.get(1).counts()).isEqualTo(new StatusCounts(0, 0, 0, 1));
        assertThat(summaries.get(2).counts()).isEqualTo(new StatusCounts(0, 0, 0, 0));
    }

    @Test
    void aCommunityHistoryListsItsAssessmentsOldestFirst() {
        given(
                List.of(UARINI),
                List.of(assessment("p3", "uarini", ISOLATED, null), assessment("p1", "uarini", CONNECTED, 200)));
        lenient().when(communities.findById("uarini")).thenReturn(Optional.of(UARINI));

        List<Assessment> history = service.history("uarini").orElseThrow().assessments();

        assertThat(history).extracting(Assessment::passId).containsExactly("p1", "p3");
        assertThat(history.getFirst().routeWidthM()).isEqualTo(200);
    }

    @Test
    void theHubAndUnknownCommunitiesHaveNoHistory() {
        lenient().when(communities.findById("tefe")).thenReturn(Optional.of(TEFE));
        lenient().when(communities.findById("atlantis")).thenReturn(Optional.empty());

        assertThat(service.history("tefe")).isEmpty();
        assertThat(service.history("atlantis")).isEmpty();
    }

    @Test
    void anEmptyMonitorHasNoHubNoPassAndNoCommunities() {
        lenient().when(communities.findAll()).thenReturn(List.of());
        lenient().when(passes.findAll()).thenReturn(List.of());
        lenient().when(assessments.findAll()).thenReturn(List.of());

        assertThat(service.overview()).isEqualTo(new Overview(null, null, List.of()));
    }
}
