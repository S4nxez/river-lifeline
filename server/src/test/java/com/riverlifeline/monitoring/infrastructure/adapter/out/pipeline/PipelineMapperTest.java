package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.monitoring.domain.model.aoi.Aoi;
import com.riverlifeline.monitoring.domain.model.community.Community;
import com.riverlifeline.monitoring.domain.model.community.CommunityId;
import com.riverlifeline.monitoring.domain.model.community.CommunityKind;
import com.riverlifeline.monitoring.domain.model.community.ExclusionReason;
import com.riverlifeline.monitoring.domain.model.pass.LayerId;
import com.riverlifeline.monitoring.domain.model.pass.LayerImage;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.pass.PassId;
import com.riverlifeline.monitoring.domain.model.pass.StatusCounts;
import com.riverlifeline.monitoring.domain.model.status.CommunityAssessment;
import com.riverlifeline.monitoring.domain.model.status.PassStatuses;
import com.riverlifeline.monitoring.domain.model.status.Reason;
import com.riverlifeline.monitoring.domain.model.status.Status;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineMapper.Snapshot;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.AssessmentRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.ChangeRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.CountsRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.GaugeRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.PassRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.RasterBox;
import com.riverlifeline.shared.domain.GeoPoint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PipelineMapperTest {
    static final Map<String, String> CLASSES =
            Map.of("a", "monitored", "b", "monitored", "c", "unresolved", "d", "inland");

    static AssessmentRow route(String pass, String community, String status, Integer bottleneck, Integer distance) {
        return new AssessmentRow(pass, community, status, bottleneck, distance, false);
    }

    static Snapshot snapshot(List<PassRow> passes, List<AssessmentRow> routes, Map<String, String> classes) {
        return PipelineMapper.snapshot(TestArea.area(), passes, routes, classes, List.of(), TestArea.JSON);
    }

    static CommunityAssessment status(Snapshot snapshot, String pass, String community) {
        return snapshot.statuses()
                .get(PassId.of(pass))
                .find(CommunityId.of(community))
                .orElseThrow();
    }

    @Test
    void eachStatusComesWithTheReasonThePublicApiShows() {
        List<AssessmentRow> routes = List.of(
                route("p1", "a", "connected", 100, 0),
                route("p1", "b", "at_risk", 40, 0),
                new AssessmentRow("p1", "c", "at_risk", 100, 0, true),
                route("p1", "d", "isolated", null, 2000));
        Snapshot snapshot =
                snapshot(List.of(TestArea.pass("p1", "2026-10-11", true, null)), routes, Map.of("d", "monitored"));

        assertThat(status(snapshot, "p1", "a"))
                .extracting(
                        CommunityAssessment::status, CommunityAssessment::reason, CommunityAssessment::minWidthClassM)
                .containsExactly(Status.CONNECTED, Reason.ROUTE_GE_60M, 100);
        assertThat(status(snapshot, "p1", "b").reason()).isEqualTo(Reason.NARROW_ROUTE);
        assertThat(status(snapshot, "p1", "c"))
                .extracting(CommunityAssessment::reason, CommunityAssessment::routeVerified)
                .containsExactly(Reason.ROUTE_UNVERIFIED, false);
        assertThat(status(snapshot, "p1", "d").reason()).isEqualTo(Reason.NO_ROUTE_CONFIRMED);
    }

    @Test
    void noDataSaysWhetherThePlaceWasNotCoveredOrItsRouteCrossesAGap() {
        List<AssessmentRow> routes =
                List.of(route("p1", "a", "no_data", null, null), route("p1", "b", "no_data", null, 300));

        Snapshot snapshot = snapshot(List.of(TestArea.pass("p1", "2026-10-11", true, null)), routes, CLASSES);

        assertThat(status(snapshot, "p1", "a").reason()).isEqualTo(Reason.OUTSIDE_COVERAGE);
        assertThat(status(snapshot, "p1", "b").reason()).isEqualTo(Reason.ROUTE_THROUGH_NODATA);
    }

    @Test
    void anUnconfirmedIsolationIsAtRiskUntilTheNextPassConfirmsIt() {
        List<PassRow> passes = List.of(
                TestArea.pass("p1", "2026-10-11", true, new CountsRow(1, 0, 0, 1, List.of())),
                TestArea.pass("p2", "2026-10-23", true, new CountsRow(0, 1, 0, 1, List.of("a"))),
                TestArea.pass("p3", "2026-11-04", true, new CountsRow(0, 0, 1, 1, List.of())));
        List<AssessmentRow> routes = List.of(
                route("p1", "a", "connected", 100, 0),
                route("p2", "a", "isolated", null, 200),
                route("p3", "a", "isolated", null, 200));

        Snapshot snapshot = snapshot(passes, routes, CLASSES);

        CommunityAssessment p2 = status(snapshot, "p2", "a");
        assertThat(p2)
                .extracting(
                        CommunityAssessment::status,
                        CommunityAssessment::reason,
                        CommunityAssessment::previousStatus,
                        CommunityAssessment::previousPassId,
                        CommunityAssessment::changed)
                .containsExactly(Status.AT_RISK, Reason.NO_ROUTE_UNCONFIRMED, Status.CONNECTED, PassId.of("p1"), true);
        CommunityAssessment p3 = status(snapshot, "p3", "a");
        assertThat(p3)
                .extracting(CommunityAssessment::status, CommunityAssessment::reason, CommunityAssessment::changed)
                .containsExactly(Status.ISOLATED, Reason.NO_ROUTE_CONFIRMED, true);
    }

    @Test
    void thePassesCarryTheCountsOfTheClassificationOverMonitoredCommunities() {
        List<PassRow> passes = List.of(
                TestArea.pass("p1", "2026-10-11", true, new CountsRow(2, 0, 0, 0, List.of())),
                TestArea.pass("p2", "2026-10-23", true, new CountsRow(1, 1, 0, 0, List.of())));
        List<AssessmentRow> routes = List.of(
                route("p1", "a", "connected", 100, 0),
                route("p1", "b", "connected", 100, 0),
                route("p2", "a", "connected", 100, 0),
                route("p2", "b", "at_risk", 40, 0));

        Snapshot snapshot = snapshot(passes, routes, CLASSES);

        Pass p2 = snapshot.catalog().passes().get(1);
        assertThat(p2.counts()).isEqualTo(new StatusCounts(2, 1, 1, 0, 0, 1));
    }

    @Test
    void beforeTheFirstClassificationEveryCommunityIsMonitoredAndCountedFromItsStatus() {
        List<AssessmentRow> routes =
                List.of(route("p1", "a", "connected", 100, 0), route("p1", "b", "isolated", null, 100));

        Snapshot snapshot = snapshot(List.of(TestArea.pass("p1", "2026-10-11", true, null)), routes, Map.of());

        // c and d have no route on the pass: no data
        assertThat(snapshot.catalog().passes().getFirst().counts()).isEqualTo(new StatusCounts(4, 1, 0, 1, 2, 0));
        assertThat(snapshot.communities()).allMatch(Community::monitored);
    }

    @Test
    void aPassThatWasNotEvaluatedClaimsNoStatus() {
        Snapshot snapshot = snapshot(
                List.of(TestArea.pass("p1", "2026-10-11", false, null)),
                List.of(route("p1", "a", "connected", 100, 0)),
                CLASSES);

        PassStatuses statuses = snapshot.statuses().get(PassId.of("p1"));
        assertThat(statuses.evaluated()).isFalse();
        assertThat(statuses.size()).isZero();
        assertThat(snapshot.catalog().passes().getFirst().counts()).isEqualTo(StatusCounts.notEvaluated(2));
    }

    @Test
    void aPassDescribesItsAcquisitionAndProcessing() {
        PassRow row = TestArea.withChange(
                TestArea.pass("p1", "2026-10-23", true, null),
                new ChangeRow("p0", 1.5, 0.5, new RasterBox("c1", 4, 3, "uint8", -65.0, -3.0, 0.01, -0.01)));

        Pass pass =
                snapshot(List.of(row), List.of(), CLASSES).catalog().passes().getFirst();

        assertThat(pass.acquiredAt()).isEqualTo(Instant.parse("2026-10-23T10:02:00Z"));
        assertThat(pass.platform()).isEqualTo("NISAR");
        assertThat(pass.granuleId()).isEqualTo("NISAR_L2_GCOV_p1");
        assertThat(pass.polarizations()).containsExactly("HH", "HV");
        assertThat(pass.riverLevelM()).isEqualTo(8.12);
        assertThat(pass.lowWaterRule().active()).isTrue();
        assertThat(pass.lowWaterRule().s1SceneIds()).containsExactly("2026-10-25");
        assertThat(pass.methodVersion()).isEqualTo("0123456789ab");
        assertThat(pass.processedAt()).isEqualTo(Instant.parse("2026-10-23T12:00:00Z"));
    }

    @Test
    void theLayersArePaintedFromThePassRastersAndCoverThemExactly() {
        PassRow row = TestArea.withChange(
                TestArea.pass("p1", "2026-10-23", true, null),
                new ChangeRow("p0", 1.5, 0.5, new RasterBox("c1", 4, 3, "uint8", -65.0, -3.0, 0.01, -0.01)));

        Map<LayerId, LayerImage> layers = PipelineMapper.layers(row);

        assertThat(layers.keySet())
                .containsExactlyInAnyOrder(
                        LayerId.RADAR_HH,
                        LayerId.SENTINEL1_VV,
                        LayerId.WATER,
                        LayerId.FLOODED_VEGETATION,
                        LayerId.NODATA,
                        LayerId.CHANGE);
        LayerImage water = layers.get(LayerId.WATER);
        assertThat(water.path()).isEqualTo("rasters/w1/water.png");
        assertThat(List.of(
                        water.bounds().west(),
                        water.bounds().south(),
                        water.bounds().east(),
                        water.bounds().north()))
                .usingElementComparator((a, b) -> Math.abs(a - b) < 1e-9 ? 0 : Double.compare(a, b))
                .containsExactly(-65.005, -3.025, -64.965, -2.995);
        assertThat(water.widthPx()).isEqualTo(4);
        assertThat(layers.get(LayerId.CHANGE).path()).isEqualTo("rasters/c1/change.png");
        assertThat(layers.get(LayerId.RADAR_HH).path()).isEqualTo("rasters/r1/radar_hh.png");
        assertThat(layers.get(LayerId.SENTINEL1_VV).path()).isEqualTo("rasters/v1/sentinel1_vv.png");
    }

    @Test
    void aSinglePolarisationPassHasNoVegetationLayer() {
        Map<LayerId, LayerImage> layers = PipelineMapper.layers(TestArea.pass("p1", "2026-10-23", true, null));

        assertThat(layers.keySet()).containsExactlyInAnyOrder(LayerId.WATER, LayerId.NODATA);
    }

    @Test
    void theAreaComesFromItsFileWithTheParametersThePageShows() {
        Aoi aoi = PipelineMapper.aoi(TestArea.area());

        assertThat(aoi.id().value()).isEqualTo("tefe");
        assertThat(aoi.region()).isEqualTo("Brazil (Amazonas)");
        assertThat(aoi.destination().name()).isEqualTo("Tefé");
        assertThat(aoi.destination().location()).isEqualTo(new GeoPoint(-64.71, -3.35));
        assertThat(aoi.riverGauge().stationCode()).isEqualTo("12900001");
        assertThat(aoi.parameters().testedWidthsM()).containsExactly(40, 60, 100, 200, 400);
        assertThat(aoi.parameters().connectedMinWidthM()).isEqualTo(60);
        assertThat(aoi.parameters().monitorMinWidthM()).isEqualTo(40);
        assertThat(aoi.parameters().minCoverageFraction()).isEqualTo(0.8);
        assertThat(aoi.parameters().lowWaterThresholdM()).isEqualTo(12.0);
        assertThat(aoi.parameters().waterHysteresisDb()).isEqualTo(-14.0);
        assertThat(aoi.parameters().swathEdgeBufferM()).isEqualTo(1000);
    }

    @Test
    void communitiesTakeTheirWholeRecordClass() {
        List<Community> communities = PipelineMapper.communities(TestArea.area(), CLASSES);

        assertThat(communities)
                .extracting(Community::monitored, Community::exclusionReason, Community::kind)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(true, null, CommunityKind.TOWN),
                        org.assertj.core.groups.Tuple.tuple(true, null, CommunityKind.VILLAGE),
                        org.assertj.core.groups.Tuple.tuple(
                                false, ExclusionReason.NEVER_NAVIGABLE, CommunityKind.HAMLET),
                        org.assertj.core.groups.Tuple.tuple(false, ExclusionReason.TERRA_FIRME, CommunityKind.OTHER));
        assertThat(communities.getFirst().measurementPoint()).isEqualTo(new GeoPoint(-64.80, -3.22));
    }

    @Test
    void theRiverLevelIsTheGaugeSeriesInMetres() {
        Snapshot snapshot = PipelineMapper.snapshot(
                TestArea.area(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(new GaugeRow(LocalDate.parse("2026-10-23"), 812.5)),
                TestArea.JSON);

        var series = snapshot.riverLevel().orElseThrow();
        assertThat(series.stationCode()).isEqualTo("12900001");
        assertThat(series.readings().getFirst().levelM()).isEqualTo(8.125);
        assertThat(snapshot.catalog().passes()).isEmpty();
    }

    @Test
    void pipelinePassIdsBecomeUrlSafePublicIds() {
        Snapshot snapshot = snapshot(
                List.of(TestArea.pass("2026-10-23_t161A", "2026-10-23", true, null)),
                List.of(route("2026-10-23_t161A", "a", "connected", 100, 0)),
                CLASSES);

        PassId id = snapshot.catalog().passes().getFirst().id();
        assertThat(id).isEqualTo(PassId.of("2026-10-23-t161a"));
        assertThat(snapshot.statuses().get(id).find(CommunityId.of("a"))).isPresent();
    }
}
