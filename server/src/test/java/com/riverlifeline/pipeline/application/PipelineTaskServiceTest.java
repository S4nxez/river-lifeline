package com.riverlifeline.pipeline.application;

import static com.riverlifeline.pipeline.PipelineTestData.AREA_ID;
import static com.riverlifeline.pipeline.PipelineTestData.STATION;
import static com.riverlifeline.pipeline.PipelineTestData.assessment;
import static com.riverlifeline.pipeline.PipelineTestData.classes;
import static com.riverlifeline.pipeline.PipelineTestData.granule;
import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.pipeline.PipelineFakes.PassRow;
import com.riverlifeline.pipeline.PipelineTestData;
import com.riverlifeline.pipeline.domain.model.job.JobInputs;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.pass.NisarPass;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import com.riverlifeline.pipeline.domain.model.result.TrainedDroughtModel;
import com.riverlifeline.pipeline.domain.model.source.DroughtModel;
import com.riverlifeline.pipeline.domain.model.source.GaugeReading;
import com.riverlifeline.pipeline.domain.model.source.WeatherDay;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PipelineTaskServiceTest {
    private final PipelineHarness h = new PipelineHarness();

    @Test
    void theSearchRecordsEveryPassAndAnAssessedPassKeepsItsGranules() {
        h.found.add(granule("2026-10-23", 161, 176, "NISAR_L2_GCOV_PROVISIONAL_V1"));
        h.found.add(granule("2026-10-23", 161, 177, "NISAR_L2_GCOV_PROVISIONAL_V1"));
        h.found.add(granule("2026-10-11", 161, 176, "NISAR_L2_GCOV_PROVISIONAL_V1"));

        h.tasks.discoverPasses();

        assertThat(h.passes.rows).hasSize(2);
        NisarPass latest = h.passes.row(
                        AREA_ID, PipelineTestData.pass("2026-10-23", 161).info().id())
                .pass;
        assertThat(latest.granules()).hasSize(2);

        PassRow row = h.passes.row(AREA_ID, latest.info().id());
        row.assessment = assessment(latest.info().id(), Map.of());
        h.found.clear();
        h.found.add(granule("2026-10-23", 161, 176, "NISAR_L2_GCOV_V1"));
        h.tasks.discoverPasses();
        assertThat(row.pass.granules()).hasSize(2);
    }

    @Test
    void theGaugeIsReadInChunksFromBeforeTheFirstPassThenFromItsLastDay() {
        h.passes.saveDiscovered(AREA_ID, PipelineTestData.pass("2026-10-01", 161), h.clock.instant());
        h.readings.add(new GaugeReading(LocalDate.parse("2026-10-20"), 900));
        h.readings.add(new GaugeReading(LocalDate.parse("2026-10-20"), 910));
        h.readings.add(new GaugeReading(LocalDate.parse("2026-11-13"), 820));

        h.tasks.refreshGauges();

        assertThat(h.gaugeCalls.get(0)[0]).isEqualTo(LocalDate.parse("2026-09-16"));
        assertThat(h.gaugeCalls.get(0)[1]).isEqualTo(LocalDate.parse("2026-11-14"));
        assertThat(h.gauges
                        .between(STATION, LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-20"))
                        .get(0)
                        .levelCm())
                .isEqualTo(905);

        h.gaugeCalls.clear();
        h.tasks.refreshGauges();
        assertThat(h.gaugeCalls.get(0)[0]).isEqualTo(LocalDate.parse("2026-11-11"));
    }

    @Test
    void passesJudgedWithAnOlderAreaFileAreReevaluated() {
        NisarPass pass = PipelineTestData.pass("2026-10-23", 161);
        h.passes.saveDiscovered(AREA_ID, pass, h.clock.instant());
        PassRow row = h.passes.row(AREA_ID, pass.info().id());
        row.assessment = assessment(pass.info().id(), Map.of(ResultRasterName.WATER, classes()));
        row.rasters.put(ResultRasterName.WATER, RasterId.random());
        row.digest = "digest-0";

        h.tasks.queueReevaluations();
        h.tasks.queueReevaluations();

        assertThat(h.jobs.ofType(JobType.REEVALUATE_PASS)).hasSize(1);
        JobInputs.ReevaluatePass inputs =
                h.encoder.of(JobInputs.ReevaluatePass.class).get(0);
        assertThat(inputs.water().id()).isEqualTo(row.rasters.get(ResultRasterName.WATER));
        assertThat(inputs.sentinel1()).isNull();
        assertThat(inputs.occurrence()).isNotNull();
    }

    @Test
    void aForecastIsQueuedOnlyForARegionWithAModel() {
        h.tasks.queueForecasts();
        assertThat(h.jobs.jobs).isEmpty();

        h.results.models.add(new TrainedDroughtModel("lake", 400, 3, new DroughtModel("baseline", 2, 3, "{\"w\":1}")));
        for (int d = 10; d <= 13; d++) {
            h.weatherDays.add(new WeatherDay(LocalDate.of(2026, 11, d), 1.5, 28, null));
        }
        h.tasks.queueForecasts();

        JobInputs.ForecastDrought forecast =
                h.encoder.of(JobInputs.ForecastDrought.class).get(0);
        assertThat(forecast.asOf()).isEqualTo(LocalDate.parse("2026-11-13"));
        assertThat(forecast.horizonDays()).isEqualTo(30);
        assertThat(forecast.model().version()).isEqualTo(2);
        assertThat(forecast.recent()).extracting(d -> d.day().getDayOfMonth()).containsExactly(11, 12, 13);
        assertThat(h.jobs.ofType(JobType.FORECAST_DROUGHT).get(0).job.areaId()).isNull();
    }

    @Test
    void aLeaseThatRunsOutOnTheLastAttemptLeavesThePassAsideAndUnusedBodiesAreSwept() {
        NisarPass pass = PipelineTestData.pass("2026-10-23", 161);
        h.passes.saveDiscovered(AREA_ID, pass, h.clock.instant());
        h.staging.stageAssessments();
        h.worker.next(java.util.Set.of("assess_pass"), java.util.Set.of(1));
        h.clock.advance(Duration.ofMinutes(16));
        h.worker.next(java.util.Set.of("assess_pass"), java.util.Set.of(1));
        h.clock.advance(Duration.ofMinutes(16));

        h.tasks.expireLeases();

        assertThat(h.passes.row(AREA_ID, pass.info().id()).stagingAfter)
                .isEqualTo(h.clock.instant().plus(Duration.ofHours(24)));

        RasterId unused = h.jobs.ofType(JobType.ASSESS_PASS).get(0).rasters.get(0);
        h.rasters.unreferenced.add(unused);
        h.tasks.sweepRasters();
        assertThat(h.store.bodies).doesNotContainKey(unused);
        assertThat(h.rasters.find(unused)).isEmpty();
    }
}
