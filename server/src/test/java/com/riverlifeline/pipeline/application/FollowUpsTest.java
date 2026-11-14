package com.riverlifeline.pipeline.application;

import static com.riverlifeline.pipeline.PipelineTestData.AREA_ID;
import static com.riverlifeline.pipeline.PipelineTestData.area;
import static com.riverlifeline.pipeline.PipelineTestData.assessment;
import static com.riverlifeline.pipeline.PipelineTestData.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.pipeline.PipelineFakes.PassRow;
import com.riverlifeline.pipeline.PipelineTestData;
import com.riverlifeline.pipeline.domain.model.job.JobInputs;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.pass.NisarPass;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FollowUpsTest {
    private final PipelineHarness h = new PipelineHarness();

    private NisarPass assessed(String day, int track, boolean shown) {
        NisarPass pass = PipelineTestData.pass(day, track);
        h.passes.saveDiscovered(AREA_ID, pass, h.clock.instant());
        PassRow row = h.passes.row(AREA_ID, pass.info().id());
        var a = assessment(pass.info().id(), Map.of(ResultRasterName.WATER, classes()));
        row.assessment = shown
                ? a
                : new com.riverlifeline.pipeline.domain.model.result.PassAssessment(
                        a.passId(),
                        0.01,
                        false,
                        false,
                        0,
                        0,
                        null,
                        a.threshold(),
                        null,
                        null,
                        null,
                        java.util.List.of(),
                        java.util.List.of(),
                        null,
                        null,
                        a.rasters());
        row.rasters.put(ResultRasterName.WATER, RasterId.random());
        row.digest = "digest-1";
        return pass;
    }

    @Test
    void aPassArrivingOutOfOrderGetsBothChangesAndTheAreaIsClassifiedAgain() {
        NisarPass first = assessed("2026-09-29", 161, true);
        NisarPass last = assessed("2026-10-23", 161, true);
        assessed("2026-10-23", 97, true);
        NisarPass middle = assessed("2026-10-11", 161, true);

        h.followUps.afterPass(area(), middle.info().id());

        assertThat(h.encoder.of(JobInputs.DetectChange.class))
                .extracting(c -> c.sincePassId() + " -> " + c.passId())
                .containsExactly(
                        first.info().id() + " -> " + middle.info().id(),
                        middle.info().id() + " -> " + last.info().id());
        assertThat(h.jobs.ofType(JobType.CLASSIFY_COMMUNITIES)).hasSize(1);
        JobInputs.ClassifyCommunities classify =
                h.encoder.of(JobInputs.ClassifyCommunities.class).get(0);
        assertThat(classify.passes()).hasSize(4);
        assertThat(classify.communityIds()).containsExactly("a", "b");
        assertThat(classify.accessRadiusM()).isEqualTo(1000);
    }

    @Test
    void aPassThatIsNotShownHasNoChangeAndASecondClassificationWaitsAsOne() {
        assessed("2026-09-29", 161, true);
        NisarPass hidden = assessed("2026-10-11", 161, false);

        h.followUps.afterPass(area(), hidden.info().id());
        h.followUps.afterPass(area(), hidden.info().id());

        assertThat(h.jobs.ofType(JobType.DETECT_CHANGE)).isEmpty();
        assertThat(h.jobs.ofType(JobType.CLASSIFY_COMMUNITIES)).hasSize(1);
    }
}
