package com.riverlifeline.monitoring.application.view;

import com.riverlifeline.monitoring.application.port.out.BinaryContent;
import com.riverlifeline.monitoring.domain.model.aoi.Aoi;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.community.Community;
import com.riverlifeline.monitoring.domain.model.community.CommunitySnapshot;
import com.riverlifeline.monitoring.domain.model.pass.LayerId;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.pass.PassId;
import com.riverlifeline.monitoring.domain.model.riverlevel.RiverLevelReading;
import com.riverlifeline.monitoring.domain.model.status.CommunityAssessment;
import com.riverlifeline.monitoring.domain.model.status.Reason;
import com.riverlifeline.monitoring.domain.model.status.StatusDefinition;
import com.riverlifeline.monitoring.domain.service.Headline;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The read models the use cases return. The web adapter turns them into the JSON of contract 2. */
public final class Views {
    private Views() {}

    public record Health(boolean ok, String apiVersion, CatalogHealth catalog) {}

    public record CatalogHealth(boolean loaded, String schemaVersion, Instant generatedAt) {}

    public record Meta(
            AoiId aoiId,
            String apiVersion,
            String schemaVersion,
            String methodVersion,
            List<StatusDefinition> statusTable,
            List<ReasonLabel> reasons,
            List<LayerId> layers) {}

    /** A reason with its label in the AOI, whose thresholds it names. */
    public record ReasonLabel(Reason reason, String label) {}

    public record AoiOverview(
            Aoi aoi, int passCount, int evaluatedPassCount, Instant firstPassAt, Instant latestPassAt) {}

    public record CatalogVersion(
            AoiId aoiId,
            String schemaVersion,
            Instant generatedAt,
            String etag,
            PassId latestPassId,
            PassId latestEvaluatedPassId) {}

    /** {@code pass} is null when there is no evaluated pass on or before {@code date}. */
    public record Summary(AoiId aoiId, LocalDate date, Pass pass, Headline headline, Instant catalogGeneratedAt) {}

    /** A pass with its neighbours in the full pass list, evaluated or not (the ◀ ▶ buttons). */
    public record PassEntry(Pass pass, PassId previousPassId, PassId nextPassId) {}

    public record PassDetail(AoiId aoiId, PassEntry entry) {}

    public record PassCommunities(AoiId aoiId, Pass pass, List<CommunitySnapshot> communities) {}

    /** A community's assessment on one pass. */
    public record PassAssessment(Pass pass, CommunityAssessment assessment) {}

    /** {@code current} is null when the community is not monitored or there is no evaluated pass yet. */
    public record CommunityDetail(Community community, PassAssessment current, List<PassAssessment> history) {}

    public record RiverLevel(
            String provider,
            String stationCode,
            String stationName,
            String unit,
            Double lowWaterThresholdM,
            List<RiverLevelReading> readings) {}

    /** {@code immutable} is false when the pass was selected through the {@code latest} alias. */
    public record LayerContent(PassId passId, LayerId layerId, boolean immutable, BinaryContent content) {}
}
