package com.riverlifeline.pipeline.infrastructure.adapter.out.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.pipeline.application.exception.InvalidResultException;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import com.riverlifeline.pipeline.domain.model.result.ChangeResult;
import com.riverlifeline.pipeline.domain.model.result.Classification;
import com.riverlifeline.pipeline.domain.model.result.CommunityClass;
import com.riverlifeline.pipeline.domain.model.result.DroughtForecast;
import com.riverlifeline.pipeline.domain.model.result.PassAssessment;
import com.riverlifeline.pipeline.domain.model.result.RouteStatus;
import com.riverlifeline.pipeline.domain.model.result.TrainedDroughtModel;
import com.riverlifeline.pipeline.infrastructure.ContractSchema;
import com.riverlifeline.pipeline.infrastructure.json.ContractJson;
import org.junit.jupiter.api.Test;

/** Results as the worker posts them; each sample is first checked against its schema in api/worker-api.yaml. */
class JacksonJobResultDecoderTest {
    static final String WATER = """
            {"width": 20, "height": 20, "dtype": "uint8", "nodata": 255, "unit": "class", "crs": "EPSG:4326",
             "origin": {"x": -64.759, "y": -3.391}, "step": {"x": 0.0009, "y": -0.0009}}""";
    static final String ASSESSMENT = """
            {"passId": "2026-10-23_t161A", "coverage": 0.93, "shown": true, "evaluated": true,
             "waterKm2": 412.5, "narrowKm2": 12.25, "vegetatedKm2": null,
             "threshold": {"source": "fixed", "strongDb": -16, "weakDb": -14},
             "riverLevelCm": 823, "lowWater": true,
             "hubLink": {"status": "connected", "bottleneckM": 400, "distanceToWaterM": 0},
             "communities": [{"communityId": "a", "status": "at_risk", "bottleneckM": 40, "distanceToWaterM": 12},
                             {"communityId": "b", "status": "isolated", "bottleneckM": null, "distanceToWaterM": 950}],
             "unverifiedRoute": ["a"],
             "sentinel1": {"dates": ["2026-10-25"], "judgedKm2": 1500.5, "agreement": 0.91, "bothWaterKm2": 380,
                           "nisarOnlyKm2": 30, "sentinel1OnlyKm2": 5, "mudKm2": 12},
             "stats": {"seconds": 81.5, "spacingM": 20},
             "rasters": {"water": %s, "sentinel1": %s}}""".formatted(WATER, WATER);

    private final ObjectMapper json = ContractJson.mapper();
    private final JacksonJobResultDecoder decoder = new JacksonJobResultDecoder(json);

    private void valid(String result) throws Exception {
        assertThat(ContractSchema.violations(json.readTree(result), "JobResultData"))
                .isEmpty();
    }

    @Test
    void aPassAssessmentIsReadWhole() throws Exception {
        valid(ASSESSMENT);

        PassAssessment a = (PassAssessment) decoder.decode(JobType.ASSESS_PASS, ASSESSMENT);

        assertThat(a.passId().value()).isEqualTo("2026-10-23_t161A");
        assertThat(a.vegetatedKm2()).isNull();
        assertThat(a.threshold().weakDb()).isEqualTo(-14.0);
        assertThat(a.threshold().measuredDb()).isNull();
        assertThat(a.hubLink().status()).isEqualTo(RouteStatus.CONNECTED);
        assertThat(a.communities().get(1).route().bottleneckM()).isNull();
        assertThat(a.unverifiedRoute()).containsExactly("a");
        assertThat(a.sentinel1().agreement()).isEqualTo(0.91);
        assertThat(a.rasters()).containsOnlyKeys(ResultRasterName.WATER, ResultRasterName.SENTINEL1);
        assertThat(a.rasters().get(ResultRasterName.WATER).nodata()).isEqualTo(NoData.value(255));
        assertThat(a.stats().seconds()).isEqualTo(81.5);
    }

    @Test
    void aResultThatBreaksTheContractIsRefused() {
        assertThatThrownBy(() -> decoder.decode(JobType.ASSESS_PASS, ASSESSMENT.replace("\"coverage\": 0.93,", "")))
                .isInstanceOf(InvalidResultException.class)
                .hasMessage("coverage is missing");
        assertThatThrownBy(() -> decoder.decode(
                        JobType.ASSESS_PASS,
                        ASSESSMENT.replace("\"sentinel1\": {\"width\"", "\"backscatter\": {\"width\"")))
                .isInstanceOf(InvalidResultException.class)
                .hasMessage("unknown raster backscatter");
        assertThatThrownBy(() -> decoder.decode(JobType.ASSESS_PASS, ASSESSMENT.replace("\"at_risk\"", "\"lost\"")))
                .isInstanceOf(InvalidResultException.class)
                .hasMessageContaining("lost");
        assertThatThrownBy(() -> decoder.decode(JobType.ASSESS_PASS, ASSESSMENT.replace("0.93", "1.5")))
                .isInstanceOf(InvalidResultException.class);
        assertThatThrownBy(() -> decoder.decode(JobType.ASSESS_PASS, "[1]")).isInstanceOf(InvalidResultException.class);
        assertThatThrownBy(() -> decoder.decode(JobType.ASSESS_PASS, "{oops"))
                .isInstanceOf(InvalidResultException.class);
        assertThatThrownBy(() ->
                        decoder.decode(JobType.ASSESS_PASS, ASSESSMENT.replace("\"shown\": true", "\"shown\": 1")))
                .isInstanceOf(InvalidResultException.class)
                .hasMessage("shown must be a boolean");
    }

    @Test
    void theOtherResultsAreReadToo() throws Exception {
        String change = """
                {"passId": "2026-10-23_t161A", "sincePassId": "2026-10-11_t161A", "lostKm2": 3.5, "gainedKm2": 0,
                 "rasters": {"change": %s}}""".formatted(WATER);
        String classification = """
                {"areaId": "lake", "classes": {"a": "monitored", "b": "inland"},
                 "totals": {"monitored": 1, "unresolved": 0, "inland": 1, "noData": 0},
                 "passes": [{"passId": "2026-10-23_t161A",
                             "counts": {"connected": 0, "atRisk": 1, "isolated": 0, "noData": 0}, "unconfirmed": []}]}""";
        String trained = """
                {"region": "lake", "observations": 3650, "droughts": 4,
                 "model": {"kind": "baseline", "version": 2, "lookbackDays": 90, "parameters": {"threshold": 0.4}}}""";
        String forecast = """
                {"region": "lake", "horizonDays": 30, "asOf": "2026-11-13", "probability": 0.62, "riskLevel": "high"}""";
        for (String sample : new String[] {change, classification, trained, forecast}) {
            valid(sample);
        }

        ChangeResult c = (ChangeResult) decoder.decode(JobType.DETECT_CHANGE, change);
        Classification k = (Classification) decoder.decode(JobType.CLASSIFY_COMMUNITIES, classification);
        TrainedDroughtModel t = (TrainedDroughtModel) decoder.decode(JobType.TRAIN_DROUGHT_MODEL, trained);
        DroughtForecast f = (DroughtForecast) decoder.decode(JobType.FORECAST_DROUGHT, forecast);

        assertThat(c.lostKm2()).isEqualTo(3.5);
        assertThat(c.rasters()).containsOnlyKeys(ResultRasterName.CHANGE);
        assertThat(k.classes()).containsEntry("b", CommunityClass.INLAND);
        assertThat(k.passes().get(0).atRisk()).isEqualTo(1);
        assertThat(t.model().parametersJson()).isEqualTo("{\"threshold\":0.4}");
        assertThat(f.riskLevel()).isEqualTo("high");
        assertThatThrownBy(() -> decoder.decode(JobType.FORECAST_DROUGHT, forecast.replace("0.62", "1.62")))
                .isInstanceOf(InvalidResultException.class);
        assertThatThrownBy(() -> decoder.decode(JobType.DETECT_CHANGE, change.replace("3.5", "-1")))
                .isInstanceOf(InvalidResultException.class);
    }
}
