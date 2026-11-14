package com.riverlifeline.pipeline.application;

import static com.riverlifeline.pipeline.PipelineTestData.AREA_ID;
import static com.riverlifeline.pipeline.PipelineTestData.STATION;
import static com.riverlifeline.pipeline.PipelineTestData.granule;
import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.pipeline.PipelineFakes.PassRow;
import com.riverlifeline.pipeline.PipelineTestData;
import com.riverlifeline.pipeline.application.port.out.Sentinel1Scenes.Scene;
import com.riverlifeline.pipeline.domain.model.job.JobInputs;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.pass.GranuleRef;
import com.riverlifeline.pipeline.domain.model.pass.NisarPass;
import com.riverlifeline.pipeline.domain.model.raster.RasterRole;
import com.riverlifeline.pipeline.domain.model.source.GaugeDay;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StagingServiceTest {
    private final PipelineHarness h = new PipelineHarness();

    @Test
    void aPassIsStagedWithEveryInputItsJobCarries() {
        NisarPass pass = PipelineTestData.pass("2026-10-23", 161);
        h.passes.saveDiscovered(AREA_ID, pass, h.clock.instant());
        h.gauges.save(
                STATION,
                List.of(
                        new GaugeDay(LocalDate.parse("2026-10-17"), 1000),
                        new GaugeDay(LocalDate.parse("2026-10-18"), 990),
                        new GaugeDay(LocalDate.parse("2026-10-23"), 950),
                        new GaugeDay(LocalDate.parse("2026-10-28"), 900),
                        new GaugeDay(LocalDate.parse("2026-10-29"), 890)));
        h.sentinel1.scenes.addAll(List.of(
                new Scene("s2", LocalDate.parse("2026-10-25"), "https://pc/2.tif"),
                new Scene("s1", LocalDate.parse("2026-10-20"), "https://pc/1.tif"),
                new Scene("off", LocalDate.parse("2026-10-20"), "https://pc/0.tif"),
                new Scene("s3", LocalDate.parse("2026-10-25"), "https://pc/3.tif")));

        assertThat(h.staging.stageAssessments()).isEqualTo(1);

        JobInputs.AssessPass inputs = h.encoder.of(JobInputs.AssessPass.class).get(0);
        assertThat(inputs.pass()).isEqualTo(pass.info());
        assertThat(inputs.granules()).hasSize(1);
        assertThat(inputs.granules().get(0).hv()).isNotNull();
        assertThat(inputs.gauge().days())
                .extracting(d -> d.day().toString())
                .containsExactly("2026-10-18", "2026-10-23", "2026-10-28");
        assertThat(h.sentinel1.searches.get(0))
                .containsExactly(LocalDate.parse("2026-10-19"), LocalDate.parse("2026-10-27"));
        assertThat(inputs.sentinel1Candidates())
                .extracting(d -> d.date().toString())
                .containsExactly("2026-10-20", "2026-10-25");
        assertThat(inputs.sentinel1Candidates().get(1).tiles()).hasSize(2);
        assertThat(inputs.occurrence()).isNotNull();

        assertThat(h.jobs.ofType(JobType.ASSESS_PASS)).hasSize(1);
        var job = h.jobs.ofType(JobType.ASSESS_PASS).get(0);
        assertThat(job.job.subject()).isEqualTo("lake/2026-10-23_t161A");
        assertThat(job.rasters).hasSize(2 + 3 + 1);
        assertThat(h.rasters.rasters.values())
                .filteredOn(r -> r.role() == RasterRole.INPUT)
                .hasSize(5);
    }

    @Test
    void stagingStopsAtTheBudgetAndTakesTheNewestPassFirst() {
        for (String day : List.of("2026-09-29", "2026-10-11", "2026-10-23")) {
            h.passes.saveDiscovered(AREA_ID, PipelineTestData.pass(day, 161), h.clock.instant());
        }

        assertThat(h.staging.stageAssessments()).isEqualTo(2);
        assertThat(h.staging.stageAssessments()).isZero();
        assertThat(h.jobs.ofType(JobType.ASSESS_PASS))
                .extracting(j -> j.job.subject())
                .containsExactly("lake/2026-10-23_t161A", "lake/2026-10-11_t161A");
        assertThat(h.occurrenceSource.writes).isEqualTo(1);
    }

    @Test
    void aPassNoGranuleReachesIsGivenUpAndAFailureIsRetriedLater() {
        NisarPass outside = PipelineTestData.pass("2026-10-23", 161);
        h.granules.missing.add(outside.granules().get(0).name());
        h.passes.saveDiscovered(AREA_ID, outside, h.clock.instant());

        h.staging.stageAssessments();

        PassRow row = h.passes.row(AREA_ID, outside.info().id());
        assertThat(row.givenUp).isTrue();
        assertThat(row.stagingError).isEqualTo("no granule reaches the area");

        GranuleRef broken = granule("2026-10-11", 161, 176, "NISAR_L2_GCOV_V1");
        h.passes.saveDiscovered(
                AREA_ID,
                new NisarPass(PipelineTestData.pass("2026-10-11", 161).info(), List.of(broken)),
                h.clock.instant());
        h.sentinel1.fail = new IllegalStateException("search exploded");
        h.staging.stageAssessments();
        PassRow failed = h.passes.row(
                AREA_ID, PipelineTestData.pass("2026-10-11", 161).info().id());
        assertThat(failed.givenUp).isFalse();
        assertThat(failed.stagingAfter).isAfter(h.clock.instant());
        assertThat(failed.stagingAfter).isBefore(h.clock.instant().plus(Duration.ofHours(24)));
        assertThat(h.jobs.jobs).isEmpty();
    }

    @Test
    void nothingIsStagedWithoutTheEarthdataToken() {
        h.granules.available = false;
        h.passes.saveDiscovered(AREA_ID, PipelineTestData.pass("2026-10-23", 161), h.clock.instant());

        assertThat(h.staging.stageAssessments()).isZero();
        assertThat(h.granules.read).isEmpty();
    }
}
