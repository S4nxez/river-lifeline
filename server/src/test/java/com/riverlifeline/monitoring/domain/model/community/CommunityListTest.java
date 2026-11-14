package com.riverlifeline.monitoring.domain.model.community;

import static com.riverlifeline.monitoring.TestData.community;
import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.monitoring.domain.model.status.CommunityAssessment;
import com.riverlifeline.monitoring.domain.model.status.Status;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Filtering and sorting the community list (#9). */
class CommunityListTest {
    private final CommunitySnapshot alvaraes = snapshot("osm-node-1", "Alvarães", Status.CONNECTED, 200, false);
    private final CommunitySnapshot caiambe = snapshot("manual-caiambe", "Caiambé", Status.ISOLATED, null, true);
    private final CommunitySnapshot nogueira = snapshot("osm-node-2", "Nogueira", Status.AT_RISK, 40, true);
    private final CommunitySnapshot bomJesus = snapshot("manual-bom-jesus", "Bom Jesus", Status.NO_DATA, null, false);
    private final List<CommunitySnapshot> all = List.of(alvaraes, caiambe, nogueira, bomJesus);

    private static CommunitySnapshot snapshot(String id, String name, Status status, Integer width, boolean changed) {
        CommunityAssessment assessment =
                new CommunityAssessment(CommunityId.of(id), status, null, width, true, null, null, changed, null, null);
        return new CommunitySnapshot(community(id, name, true), assessment);
    }

    private List<CommunitySnapshot> filter(CommunityFilter filter) {
        return all.stream().filter(filter::matches).toList();
    }

    @Test
    void filtersByStatusChangeAndName() {
        assertThat(filter(new CommunityFilter(Set.of(Status.ISOLATED, Status.AT_RISK), false, null)))
                .containsExactly(caiambe, nogueira);
        assertThat(filter(new CommunityFilter(Set.of(), true, null))).containsExactly(caiambe, nogueira);
        assertThat(filter(CommunityFilter.ALL)).hasSize(4);
    }

    @Test
    void nameSearchIgnoresCaseAndAccents() {
        assertThat(filter(new CommunityFilter(Set.of(), false, "alva"))).containsExactly(alvaraes);
        assertThat(filter(new CommunityFilter(Set.of(), false, "CAIAMBE"))).containsExactly(caiambe);
        assertThat(filter(new CommunityFilter(Set.of(), false, "  "))).hasSize(4);
    }

    @Test
    void theDefaultSortIsWorstStatusFirstThenName() {
        assertThat(all.stream().sorted(CommunitySort.STATUS.comparator(Status::defaultOrder)))
                .containsExactly(caiambe, nogueira, alvaraes, bomJesus);
    }

    @Test
    void sortsByNameOrByNarrowestRoute() {
        assertThat(all.stream().sorted(CommunitySort.NAME.comparator(Status::defaultOrder)))
                .containsExactly(alvaraes, bomJesus, caiambe, nogueira);
        assertThat(all.stream().sorted(CommunitySort.WIDTH.comparator(Status::defaultOrder)))
                .containsExactly(bomJesus, caiambe, nogueira, alvaraes);
    }

    @Test
    void sortCodesAreTheContracts() {
        assertThat(CommunitySort.fromCode("width")).contains(CommunitySort.WIDTH);
        assertThat(CommunitySort.fromCode("size")).isEmpty();
    }
}
