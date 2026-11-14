package com.riverlifeline.pipeline.infrastructure.adapter.out.json;

import static com.riverlifeline.pipeline.PipelineTestData.AREA_ID;
import static com.riverlifeline.pipeline.PipelineTestData.area;
import static com.riverlifeline.pipeline.PipelineTestData.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.pipeline.PipelineTestData;
import com.riverlifeline.pipeline.domain.model.job.JobInputs;
import com.riverlifeline.pipeline.domain.model.pass.PassId;
import com.riverlifeline.pipeline.domain.model.pass.PassInfo;
import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import com.riverlifeline.pipeline.domain.model.raster.XY;
import com.riverlifeline.pipeline.domain.model.result.CommunityRoute;
import com.riverlifeline.pipeline.domain.model.result.RouteResult;
import com.riverlifeline.pipeline.domain.model.result.RouteStatus;
import com.riverlifeline.pipeline.domain.model.source.Drought;
import com.riverlifeline.pipeline.domain.model.source.DroughtModel;
import com.riverlifeline.pipeline.domain.model.source.GaugeDay;
import com.riverlifeline.pipeline.domain.model.source.WeatherDay;
import com.riverlifeline.pipeline.infrastructure.ContractSchema;
import com.riverlifeline.pipeline.infrastructure.json.ContractJson;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every input type, written by the encoder, is valid against its schema in api/worker-api.yaml. */
class JacksonJobInputsEncoderTest {
    private final ObjectMapper json = ContractJson.mapper();
    private final JacksonJobInputsEncoder encoder = new JacksonJobInputsEncoder(json);
    private final PassInfo pass = PipelineTestData.pass("2026-10-23", 161).info();

    private static RasterRef window(NoData nodata) {
        return new RasterRef(
                RasterId.random(),
                new RasterMeta(
                        5511,
                        5496,
                        DataType.FLOAT32,
                        nodata,
                        RasterUnit.LINEAR_POWER,
                        "EPSG:32720",
                        new XY(612345.0, 9654321.0),
                        new XY(20, -20)));
    }

    private JsonNode encode(JobInputs inputs, String schema) throws Exception {
        JsonNode node = json.readTree(encoder.encode(inputs));
        assertThat(ContractSchema.violations(node, schema)).as(schema).isEmpty();
        return node;
    }

    @Test
    void assessPassInputsFollowTheContract() throws Exception {
        RasterRef occurrence = new RasterRef(
                RasterId.random(), area().grid().meta(DataType.UINT8, NoData.value(255), RasterUnit.PERCENT));
        JobInputs.AssessPass inputs = new JobInputs.AssessPass(
                area(),
                pass,
                List.of(new JobInputs.GranuleWindow("granule.h5", 176, window(NoData.NAN), null)),
                new JobInputs.Gauge("12900001", List.of(new GaugeDay(LocalDate.parse("2026-10-23"), 950.5))),
                occurrence,
                List.of(new JobInputs.Sentinel1Date(
                        LocalDate.parse("2026-10-25"), List.of(window(NoData.value(-32768))))));

        JsonNode node = encode(inputs, "AssessPassInputs");

        JsonNode grid = node.at("/area/grid");
        assertThat(grid.get("width").asInt()).isEqualTo(area().grid().width());
        assertThat(grid.at("/circle/radiusKm").asDouble()).isEqualTo(1);
        assertThat(node.at("/area/communities/1/access/lon").asDouble()).isEqualTo(-64.7491);
        assertThat(node.at("/area/communities/0/access").isNull()).isTrue();
        assertThat(node.at("/area/method/sentinel1/maxDays").asInt()).isEqualTo(4);
        assertThat(node.at("/area/method/speckle/windowM").asDouble()).isEqualTo(60);
        assertThat(node.at("/area/method/waterDbAdaptive").isNull()).isTrue();
        assertThat(node.at("/pass/direction").asText()).isEqualTo("ascending");
        JsonNode hh = node.at("/granules/0/bands/HH");
        assertThat(hh.get("href").asText()).startsWith("/internal/worker/rasters/");
        assertThat(hh.get("nodata").asText()).isEqualTo("NaN");
        assertThat(node.at("/granules/0/bands").has("HV")).isFalse();
        assertThat(node.at("/gauge/days/0/levelCm").asDouble()).isEqualTo(950.5);
        assertThat(node.at("/occurrence/nodata").isIntegralNumber()).isTrue();
        assertThat(node.at("/sentinel1Candidates/0/tiles/0/nodata").asInt()).isEqualTo(-32768);
    }

    @Test
    void theOtherInputTypesFollowTheContract() throws Exception {
        RasterRef water = new RasterRef(RasterId.random(), classes());
        PassId since = new PassId("2026-10-11_t161A");
        encode(
                new JobInputs.ReevaluatePass(
                        area(),
                        pass,
                        water,
                        new JobInputs.Sentinel1Classes(List.of(LocalDate.parse("2026-10-25")), water),
                        null,
                        null),
                "ReevaluatePassInputs");
        JsonNode change = encode(
                new JobInputs.DetectChange(AREA_ID, area().grid(), pass.id(), since, water, water),
                "DetectChangeInputs");
        assertThat(change.has("areaId")).isFalse();
        encode(
                new JobInputs.ClassifyCommunities(
                        AREA_ID,
                        1000,
                        List.of("a", "b"),
                        List.of(new JobInputs.PassRoutes(
                                pass.id(),
                                pass.date(),
                                pass.timeUtc(),
                                List.of(new CommunityRoute("a", new RouteResult(RouteStatus.NO_DATA, null, null)))))),
                "ClassifyCommunitiesInputs");
        List<WeatherDay> weather = List.of(new WeatherDay(LocalDate.parse("2026-11-13"), 2.5, 27.1, null));
        encode(
                new JobInputs.TrainDroughtModel(
                        "lake",
                        weather,
                        List.of(new Drought(LocalDate.parse("2023-09-01"), LocalDate.parse("2023-11-30"), 3))),
                "TrainDroughtModelInputs");
        JsonNode forecast = encode(
                new JobInputs.ForecastDrought(
                        "lake",
                        30,
                        LocalDate.parse("2026-11-13"),
                        new DroughtModel("baseline", 2, 30, "{\"weights\":[1,2]}"),
                        weather),
                "ForecastDroughtInputs");
        assertThat(forecast.at("/model/parameters/weights/1").asInt()).isEqualTo(2);
    }
}
