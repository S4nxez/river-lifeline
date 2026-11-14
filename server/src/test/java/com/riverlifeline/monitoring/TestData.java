package com.riverlifeline.monitoring;

import com.riverlifeline.monitoring.domain.model.aoi.Aoi;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.aoi.AoiParameters;
import com.riverlifeline.monitoring.domain.model.aoi.Destination;
import com.riverlifeline.monitoring.domain.model.aoi.RiverGauge;
import com.riverlifeline.monitoring.domain.model.catalog.Catalog;
import com.riverlifeline.monitoring.domain.model.catalog.MethodInfo;
import com.riverlifeline.monitoring.domain.model.community.BoardingPoint;
import com.riverlifeline.monitoring.domain.model.community.BoardingPointSource;
import com.riverlifeline.monitoring.domain.model.community.Community;
import com.riverlifeline.monitoring.domain.model.community.CommunityId;
import com.riverlifeline.monitoring.domain.model.community.CommunityKind;
import com.riverlifeline.monitoring.domain.model.community.ExclusionReason;
import com.riverlifeline.monitoring.domain.model.pass.LayerId;
import com.riverlifeline.monitoring.domain.model.pass.LayerImage;
import com.riverlifeline.monitoring.domain.model.pass.LowWaterRule;
import com.riverlifeline.monitoring.domain.model.pass.OrbitDirection;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.pass.PassId;
import com.riverlifeline.monitoring.domain.model.pass.StatusCounts;
import com.riverlifeline.monitoring.domain.model.status.CommunityAssessment;
import com.riverlifeline.monitoring.domain.model.status.Reason;
import com.riverlifeline.monitoring.domain.model.status.Status;
import com.riverlifeline.shared.domain.BoundingBox;
import com.riverlifeline.shared.domain.GeoPoint;
import com.riverlifeline.shared.domain.SchemaVersion;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Small builders of domain objects for the unit tests. Nothing here touches the disk. */
public final class TestData {
    public static final AoiId AOI = AoiId.of("tefe");
    public static final BoundingBox BBOX = new BoundingBox(-65.24636, -3.89640, -64.25364, -2.90360);
    public static final AoiParameters PARAMETERS =
            new AoiParameters(List.of(40, 60, 100, 200, 400), 60, 40, 0.8, 12.0, -16.0, -14.0, -40.0, 1000);

    private TestData() {}

    public static Aoi aoi(AoiId id, String name, AoiParameters parameters) {
        return new Aoi(
                id,
                name,
                "BR",
                "Amazonas",
                new GeoPoint(-64.75, -3.40),
                55,
                BBOX,
                "America/Manaus",
                new Destination("Port of " + name, new GeoPoint(-64.7188, -3.3542), null),
                new RiverGauge("ANA", "12900001", "Tefé-Missões"),
                parameters);
    }

    public static Aoi aoi() {
        return aoi(AOI, "Tefé", PARAMETERS);
    }

    public static Catalog catalog(Pass... passes) {
        return catalog(aoi(), passes);
    }

    public static Catalog catalog(Aoi aoi, Pass... passes) {
        return new Catalog(
                SchemaVersion.parse("1.0.0"),
                Instant.parse("2026-10-24T03:12:08Z"),
                aoi,
                List.of(),
                new MethodInfo("pr26", "test method"),
                Arrays.asList(passes));
    }

    public static Pass pass(String id, String acquiredAt, boolean evaluated) {
        StatusCounts counts = evaluated ? new StatusCounts(2, 1, 1, 0, 0, 0) : StatusCounts.notEvaluated(2);
        return pass(id, acquiredAt, evaluated, counts);
    }

    public static Pass pass(String id, String acquiredAt, boolean evaluated, StatusCounts counts) {
        PassId passId = PassId.of(id);
        return new Pass(
                passId,
                Instant.parse(acquiredAt),
                "NISAR",
                "L",
                24.0,
                "L2_GCOV",
                "granule-" + id,
                81,
                12,
                6123,
                OrbitDirection.DESCENDING,
                List.of("HH", "HV"),
                20.0,
                evaluated ? 0.97 : 0.5,
                evaluated,
                8.0,
                new LowWaterRule(true, List.of("S1A_TEST"), 4.2),
                counts,
                Instant.parse(acquiredAt).plusSeconds(3600),
                "pr26",
                "passes/" + id + "/statuses.json",
                Map.of(
                        LayerId.WATER,
                        new LayerImage(LayerId.WATER, "passes/" + id + "/water.png", BBOX, 256, 256),
                        LayerId.RADAR_HH,
                        new LayerImage(LayerId.RADAR_HH, "passes/" + id + "/radar_hh.png", BBOX, 256, 256)));
    }

    public static Community community(String id, String name, boolean monitored) {
        GeoPoint village = new GeoPoint(-64.6, -3.3);
        return new Community(
                CommunityId.of(id),
                name,
                CommunityKind.VILLAGE,
                "Tefé",
                null,
                null,
                monitored,
                monitored ? null : ExclusionReason.TERRA_FIRME,
                village,
                new BoardingPoint(new GeoPoint(-64.59, -3.31), BoardingPointSource.OSM_PIER, null, 300),
                monitored ? 200 : null);
    }

    public static CommunityAssessment assessment(String communityId, Status status, Reason reason, Integer width) {
        return new CommunityAssessment(
                CommunityId.of(communityId), status, reason, width, true, null, null, false, null, null);
    }
}
