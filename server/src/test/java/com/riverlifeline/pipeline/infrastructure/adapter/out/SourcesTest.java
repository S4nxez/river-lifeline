package com.riverlifeline.pipeline.infrastructure.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riverlifeline.pipeline.PipelineTestData;
import com.riverlifeline.pipeline.domain.model.pass.GranuleRef;
import com.riverlifeline.pipeline.domain.model.source.GaugeReading;
import com.riverlifeline.pipeline.domain.model.source.WeatherDay;
import com.riverlifeline.pipeline.infrastructure.LocalServer;
import com.riverlifeline.pipeline.infrastructure.adapter.out.ana.AnaGaugeSource;
import com.riverlifeline.pipeline.infrastructure.adapter.out.asf.AsfPassSearch;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.HttpCalls;
import com.riverlifeline.pipeline.infrastructure.adapter.out.openmeteo.OpenMeteoWeatherSource;
import com.riverlifeline.pipeline.infrastructure.json.ContractJson;
import com.riverlifeline.shared.domain.GeoPoint;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The ASF, ANA and Open-Meteo adapters against a local server answering as those services do. */
class SourcesTest {
    private LocalServer server;
    private HttpCalls http;

    @BeforeEach
    void start() throws IOException {
        server = new LocalServer();
        http = new HttpCalls(HttpClient.newHttpClient(), "river-lifeline-test", Duration.ofSeconds(10));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void asfGranulesAreParsedFromTheirFileNames() throws IOException {
        server.json("/search", """
                {"type": "FeatureCollection", "features": [
                  {"properties": {"fileName": "NISAR_L2_PR_GCOV_033_161_A_176_2005_DHDH_A_20261023T100258_20261023T100332_P05036_N_F_J_001.h5",
                                  "url": "https://nisar.asf.earthdatacloud.nasa.gov/a.h5", "collectionName": "NISAR_L2_GCOV_V1"}},
                  {"properties": {"fileName": "browse.png", "url": "https://x/b.png", "collectionName": "NISAR_L2_GCOV_V1"}},
                  {"properties": {"fileName": "NISAR_L2_PR_WRONG.h5", "url": "https://x/c.h5", "collectionName": "x"}}]}""");

        List<GranuleRef> granules = new AsfPassSearch(http, ContractJson.mapper(), server.url("/search"))
                .granules(PipelineTestData.GRID.bbox());

        assertThat(granules).hasSize(1);
        assertThat(granules.get(0).url()).isEqualTo("https://nisar.asf.earthdatacloud.nasa.gov/a.h5");
        String query = URLDecoder.decode(server.requestsTo("/search").get(0).query(), StandardCharsets.UTF_8);
        assertThat(query).contains("dataset=NISAR", "processingLevel=GCOV", "intersectsWith=POLYGON((");
    }

    @Test
    void anaReadingsAreTakenRowByRow() throws IOException {
        server.on("/ana", (ex, body) -> LocalServer.send(ex, 200, "text/xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <DataTable xmlns="http://MRCS/"><diffgr:diffgram xmlns:diffgr="urn:schemas-microsoft-com:xml-diffgram-v1">
                <DocumentElement>
                  <DadosHidrometereologicos><CodEstacao>12900001</CodEstacao><DataHora>2026-11-14 10:15:00</DataHora><Vazao/><Nivel>823.00</Nivel><Chuva>0.00</Chuva></DadosHidrometereologicos>
                  <DadosHidrometereologicos><CodEstacao>12900001</CodEstacao><DataHora>2026-11-14 10:00:00</DataHora><Nivel></Nivel></DadosHidrometereologicos>
                  <DadosHidrometereologicos><CodEstacao>12900001</CodEstacao><DataHora>2026-11-13 23:45:00</DataHora><Nivel>826.5</Nivel></DadosHidrometereologicos>
                </DocumentElement></diffgr:diffgram></DataTable>""".getBytes(StandardCharsets.UTF_8)));

        List<GaugeReading> readings = new AnaGaugeSource(http, server.url("/ana"))
                .readings("12900001", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-14"));

        assertThat(readings)
                .containsExactly(
                        new GaugeReading(LocalDate.parse("2026-11-14"), 823),
                        new GaugeReading(LocalDate.parse("2026-11-13"), 826.5));
        assertThat(server.requestsTo("/ana").get(0).query())
                .isEqualTo("codEstacao=12900001&dataInicio=01/10/2026&dataFim=14/11/2026");
    }

    @Test
    void openMeteoDaysWithoutRainOrTemperatureAreLeftOut() throws IOException {
        server.json("/archive", """
                {"daily": {"time": ["2026-11-11", "2026-11-12", "2026-11-13"],
                           "precipitation_sum": [0.4, null, 12.1],
                           "temperature_2m_mean": [27.5, 27.0, 26.1],
                           "soil_moisture_0_to_7cm_mean": [0.41, 0.4, null]}}""");

        List<WeatherDay> days = new OpenMeteoWeatherSource(http, ContractJson.mapper(), server.url("/archive"))
                .daily(new GeoPoint(-64.71, -3.35), LocalDate.parse("2026-11-11"), LocalDate.parse("2026-11-13"));

        assertThat(days)
                .containsExactly(
                        new WeatherDay(LocalDate.parse("2026-11-11"), 0.4, 27.5, 0.41),
                        new WeatherDay(LocalDate.parse("2026-11-13"), 12.1, 26.1, null));
        assertThat(server.requestsTo("/archive").get(0).query())
                .contains("latitude=-3.3500", "longitude=-64.7100", "start_date=2026-11-11", "timezone=UTC");
    }

    @Test
    void serverErrorsAreRetriedAndClientErrorsAreNot() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server.on("/flaky", (ex, body) -> {
            if (calls.incrementAndGet() == 1) {
                LocalServer.send(ex, 503, "text/plain", new byte[0]);
            } else {
                LocalServer.send(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
            }
        });

        assertThat(new String(http.get(java.net.URI.create(server.url("/flaky"))), StandardCharsets.UTF_8))
                .isEqualTo("ok");
        assertThatThrownBy(() -> http.get(java.net.URI.create(server.url("/missing"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 404");
        assertThat(server.requestsTo("/missing")).hasSize(1);
    }
}
