package com.riverlifeline.pipeline.domain;

import static com.riverlifeline.pipeline.PipelineTestData.AREA_ID;
import static com.riverlifeline.pipeline.PipelineTestData.area;
import static com.riverlifeline.pipeline.PipelineTestData.assessment;
import static com.riverlifeline.pipeline.PipelineTestData.classes;
import static com.riverlifeline.pipeline.PipelineTestData.radar;
import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.job.Job;
import com.riverlifeline.pipeline.domain.model.job.JobId;
import com.riverlifeline.pipeline.domain.model.job.JobState;
import com.riverlifeline.pipeline.domain.model.job.JobSubject;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.pass.PassId;
import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import com.riverlifeline.pipeline.domain.model.raster.XY;
import com.riverlifeline.pipeline.domain.model.result.ChangeResult;
import com.riverlifeline.pipeline.domain.model.result.Classification;
import com.riverlifeline.pipeline.domain.model.result.CommunityRoute;
import com.riverlifeline.pipeline.domain.model.result.DroughtForecast;
import com.riverlifeline.pipeline.domain.model.result.PassAssessment;
import com.riverlifeline.pipeline.domain.model.result.RouteResult;
import com.riverlifeline.pipeline.domain.model.result.RouteStatus;
import com.riverlifeline.pipeline.domain.service.ResultCheck;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResultCheckTest {
    private static final PassId PASS = new PassId("2026-10-23_t161A");

    private static Job job(JobType type, String subject) {
        return new Job(JobId.random(), type, 1, AREA_ID, subject, JobState.LEASED, 1, 3, null, null);
    }

    @Test
    void aCompleteAssessmentOfTheJobsPassFits() {
        PassAssessment a = assessment(PASS, Map.of(ResultRasterName.WATER, classes(), ResultRasterName.RADAR, radar()));

        assertThat(ResultCheck.problems(job(JobType.ASSESS_PASS, JobSubject.pass(AREA_ID, PASS)), a, area()))
                .isEmpty();
    }

    @Test
    void anAssessmentMayBringTheSentinel1ImageAtHalfTheAreaGridLikeTheRadar() {
        String subject = JobSubject.pass(AREA_ID, PASS);
        PassAssessment withImage = assessment(
                PASS,
                Map.of(
                        ResultRasterName.WATER, classes(),
                        ResultRasterName.SENTINEL1, classes(),
                        ResultRasterName.SENTINEL1_VV, radar()));
        PassAssessment classesAsImage =
                assessment(PASS, Map.of(ResultRasterName.WATER, classes(), ResultRasterName.SENTINEL1_VV, classes()));

        assertThat(ResultCheck.problems(job(JobType.ASSESS_PASS, subject), withImage, area()))
                .isEmpty();
        assertThat(ResultCheck.problems(job(JobType.ASSESS_PASS, subject), classesAsImage, area()))
                .anyMatch(p -> p.startsWith("raster sentinel1_vv"));
        assertThat(ResultCheck.problems(job(JobType.REEVALUATE_PASS, subject), withImage, area()))
                .contains("raster sentinel1_vv is not expected from this job");
    }

    @Test
    void anotherPassAMissingWaterOrAnUnexpectedRadarDoNot() {
        PassAssessment other = assessment(new PassId("2026-10-11_t161A"), Map.of(ResultRasterName.WATER, classes()));
        PassAssessment noWater = assessment(PASS, Map.of());
        PassAssessment withRadar =
                assessment(PASS, Map.of(ResultRasterName.WATER, classes(), ResultRasterName.RADAR, radar()));
        String subject = JobSubject.pass(AREA_ID, PASS);

        assertThat(ResultCheck.problems(job(JobType.ASSESS_PASS, subject), other, area()))
                .anyMatch(p -> p.contains("not the job's pass"));
        assertThat(ResultCheck.problems(job(JobType.ASSESS_PASS, subject), noWater, area()))
                .contains("raster water is missing");
        assertThat(ResultCheck.problems(job(JobType.REEVALUATE_PASS, subject), withRadar, area()))
                .contains("raster radar is not expected from this job");
    }

    @Test
    void areaGridRastersMustBeOnTheAreaGrid() {
        RasterMeta shifted = new RasterMeta(
                classes().width(),
                classes().height(),
                DataType.UINT8,
                NoData.value(255),
                RasterUnit.CLASS,
                "EPSG:4326",
                new XY(classes().origin().x() + 0.01, classes().origin().y()),
                classes().step());
        RasterMeta floatWater = area().grid().meta(DataType.FLOAT32, NoData.NAN, RasterUnit.CLASS);
        String subject = JobSubject.pass(AREA_ID, PASS);

        assertThat(ResultCheck.problems(
                        job(JobType.ASSESS_PASS, subject),
                        assessment(PASS, Map.of(ResultRasterName.WATER, shifted)),
                        area()))
                .contains("raster water: must be on the area grid");
        assertThat(ResultCheck.problems(
                        job(JobType.ASSESS_PASS, subject),
                        assessment(PASS, Map.of(ResultRasterName.WATER, floatWater)),
                        area()))
                .contains("raster water: must be uint8 classes");
        assertThat(ResultCheck.problems(
                        job(JobType.ASSESS_PASS, subject),
                        assessment(PASS, Map.of(ResultRasterName.WATER, classes(), ResultRasterName.RADAR, classes())),
                        area()))
                .contains("raster radar: must be float32 dB");
    }

    @Test
    void communitiesMustBeTheAreasOnceEach() {
        PassAssessment base = assessment(PASS, Map.of(ResultRasterName.WATER, classes()));
        PassAssessment unknown = new PassAssessment(
                PASS,
                1,
                true,
                true,
                1,
                1,
                null,
                base.threshold(),
                null,
                null,
                base.hubLink(),
                List.of(
                        new CommunityRoute("zz", new RouteResult(RouteStatus.NO_DATA, null, null)),
                        new CommunityRoute("a", new RouteResult(RouteStatus.NO_DATA, null, null)),
                        new CommunityRoute("a", new RouteResult(RouteStatus.NO_DATA, null, null))),
                List.of(),
                null,
                null,
                base.rasters());

        assertThat(ResultCheck.problems(job(JobType.ASSESS_PASS, JobSubject.pass(AREA_ID, PASS)), unknown, area()))
                .contains("community zz is not in the area", "community a appears twice");
    }

    @Test
    void otherJobTypesCheckTheirSubjectAndKind() {
        PassId since = new PassId("2026-10-11_t161A");
        ChangeResult change = new ChangeResult(PASS, since, 1, 2, Map.of(ResultRasterName.CHANGE, classes()));
        Classification classification = new Classification(AREA_ID, Map.of(), List.of());
        DroughtForecast forecast = new DroughtForecast("lake", 30, LocalDate.of(2026, 11, 13), 0.3, "medium");

        assertThat(ResultCheck.problems(
                        job(JobType.DETECT_CHANGE, JobSubject.change(AREA_ID, PASS, since)), change, area()))
                .isEmpty();
        assertThat(ResultCheck.problems(
                        job(JobType.DETECT_CHANGE, JobSubject.change(AREA_ID, since, PASS)), change, area()))
                .isNotEmpty();
        assertThat(ResultCheck.problems(job(JobType.CLASSIFY_COMMUNITIES, "lake"), classification, area()))
                .isEmpty();
        assertThat(ResultCheck.problems(
                        job(JobType.CLASSIFY_COMMUNITIES, "lake"),
                        new Classification(new AreaId("other"), Map.of(), List.of()),
                        area()))
                .contains("areaId is not the job's area");
        assertThat(ResultCheck.problems(
                        job(JobType.FORECAST_DROUGHT, JobSubject.forecast("lake", LocalDate.of(2026, 11, 13), 30)),
                        forecast,
                        null))
                .isEmpty();
        assertThat(ResultCheck.problems(job(JobType.CLASSIFY_COMMUNITIES, "lake"), forecast, area()))
                .contains("classify_communities needs a Classification");
    }
}
