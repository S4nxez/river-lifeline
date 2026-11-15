package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riverlifeline.monitoring.domain.exception.AoiNotFoundException;
import com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.community.CommunityId;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.pass.PassId;
import com.riverlifeline.monitoring.domain.model.status.Status;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.AssessmentRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.GaugeRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.PassRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PipelineMonitoringRepositoryTest {
    static final AoiId TEFE = AoiId.of("tefe");

    /** The pipeline's tables in memory; `broken` makes every read fail like a database that is down. */
    static class Tables implements PipelineReader {
        final List<PassRow> passes = new ArrayList<>();
        final List<AssessmentRow> assessments = new ArrayList<>();
        int reads;
        boolean broken;

        @Override
        public List<PassRow> passes(String areaId) {
            reads++;
            if (broken) {
                throw new IllegalStateException("database down");
            }
            return List.copyOf(passes);
        }

        @Override
        public List<AssessmentRow> assessments(String areaId) {
            return List.copyOf(assessments);
        }

        @Override
        public Map<String, String> communityClasses(String areaId) {
            return Map.of();
        }

        @Override
        public List<GaugeRow> gauge(String stationCode) {
            return List.of(new GaugeRow(LocalDate.parse("2026-10-23"), 800.0));
        }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-15T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final Tables tables = new Tables();
    final MutableClock clock = new MutableClock();
    final PipelineMonitoringRepository repository =
            new PipelineMonitoringRepository(TestArea.reader(), tables, TestArea.JSON, clock, Duration.ofSeconds(60));

    @Test
    void servesTheAreasOfTheAreaFilesWithTheirPassesStatusesCommunitiesAndRiverLevel() {
        tables.passes.add(TestArea.pass("p1", "2026-10-23", true, null));
        tables.assessments.add(new AssessmentRow("p1", "a", "connected", 100, 0, false));

        assertThat(repository.findAoiIds()).containsExactly(TEFE);
        Pass pass = repository.load(TEFE).passes().getFirst();
        assertThat(pass.id()).isEqualTo(PassId.of("p1"));
        assertThat(repository
                        .find(TEFE, pass)
                        .find(CommunityId.of("a"))
                        .orElseThrow()
                        .status())
                .isEqualTo(Status.CONNECTED);
        assertThat(repository.findAll(TEFE)).hasSize(4);
        assertThat(repository.find(TEFE).orElseThrow().readings()).hasSize(1);
    }

    @Test
    void anAreaWithoutPassesYetHasAnEmptyCatalog() {
        assertThat(repository.load(TEFE).passes()).isEmpty();
    }

    @Test
    void readsTheTablesAgainOnlyAfterTheRefreshInterval() {
        repository.load(TEFE);
        tables.passes.add(TestArea.pass("p1", "2026-10-23", true, null));

        assertThat(repository.load(TEFE).passes()).isEmpty();
        clock.now = clock.now.plusSeconds(61);
        assertThat(repository.load(TEFE).passes()).hasSize(1);
        assertThat(tables.reads).isEqualTo(2);
    }

    @Test
    void keepsServingTheLastResultsWhileTheDatabaseIsDown() {
        tables.passes.add(TestArea.pass("p1", "2026-10-23", true, null));
        repository.load(TEFE);
        tables.broken = true;
        clock.now = clock.now.plusSeconds(61);

        assertThat(repository.load(TEFE).passes()).hasSize(1);
    }

    @Test
    void withoutAnyResultsReadTheCatalogIsUnavailable() {
        tables.broken = true;

        assertThatThrownBy(() -> repository.load(TEFE)).isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void anAreaWithoutAnAreaFileIsNotFound() {
        assertThatThrownBy(() -> repository.load(AoiId.of("nowhere"))).isInstanceOf(AoiNotFoundException.class);
    }

    @Test
    void aPassTheResultsDoNotKnowHasNoStatuses() {
        Pass unknown = PipelineMapper.snapshot(
                        TestArea.area(),
                        List.of(TestArea.pass("p9", "2026-10-23", true, null)),
                        List.of(),
                        Map.of(),
                        List.of(),
                        TestArea.JSON)
                .catalog()
                .passes()
                .getFirst();

        assertThat(repository.find(TEFE, unknown).evaluated()).isFalse();
        assertThat(repository.find(TEFE, unknown).size()).isZero();
    }
}
