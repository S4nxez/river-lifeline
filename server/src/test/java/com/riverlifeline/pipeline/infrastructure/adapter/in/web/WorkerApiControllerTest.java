package com.riverlifeline.pipeline.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.riverlifeline.pipeline.application.exception.InvalidResultException;
import com.riverlifeline.pipeline.application.exception.NotLeasedException;
import com.riverlifeline.pipeline.application.exception.RasterTooLargeException;
import com.riverlifeline.pipeline.application.port.in.WorkerJobs;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.job.LeaseId;
import com.riverlifeline.pipeline.domain.model.job.LeasedJob;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkerApiControllerTest {
    private static final String TOKEN = "s3cret";
    private final WorkerJobs jobs = mock(WorkerJobs.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new WorkerApiController(jobs))
                .setControllerAdvice(new WorkerApiExceptionHandler())
                .addFilter(new WorkerTokenFilter(TOKEN), "/internal/worker/*")
                .build();
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    @Test
    void everyRequestNeedsTheToken() throws Exception {
        mvc.perform(get("/internal/worker/jobs/next?types=assess_pass&contract=1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing or wrong X-Worker-Token"));
        mvc.perform(get("/internal/worker/jobs/next?types=assess_pass&contract=1")
                        .header("X-Worker-Token", "nope"))
                .andExpect(status().isUnauthorized());
        MockMvc closed = MockMvcBuilders.standaloneSetup(new WorkerApiController(jobs))
                .addFilter(new WorkerTokenFilter(""), "/internal/worker/*")
                .build();
        closed.perform(get("/internal/worker/jobs/next?types=assess_pass&contract=1")
                        .header("X-Worker-Token", ""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aLeasedJobCarriesItsInputsAsIs() throws Exception {
        when(jobs.next(Set.of("assess_pass", "detect_change"), Set.of(1)))
                .thenReturn(Optional.of(
                        new LeasedJob(new LeaseId("L1"), JobType.ASSESS_PASS, 1, 900, "{\"area\":{\"id\":\"tefe\"}}")));

        mvc.perform(get("/internal/worker/jobs/next")
                        .param("types", "assess_pass, detect_change")
                        .param("contract", "1")
                        .header("X-Worker-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("L1"))
                .andExpect(jsonPath("$.type").value("assess_pass"))
                .andExpect(jsonPath("$.contractVersion").value(1))
                .andExpect(jsonPath("$.leaseSeconds").value(900))
                .andExpect(jsonPath("$.inputs.area.id").value("tefe"));
        mvc.perform(get("/internal/worker/jobs/next?types=classify_communities&contract=1")
                        .header("X-Worker-Token", TOKEN))
                .andExpect(status().isNoContent());
        mvc.perform(get("/internal/worker/jobs/next?types=assess_pass&contract=one")
                        .header("X-Worker-Token", TOKEN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void heartbeatsAnswerTheLeaseOr409() throws Exception {
        when(jobs.heartbeat("L1")).thenReturn(900);
        when(jobs.heartbeat("L0")).thenThrow(new NotLeasedException("L0"));

        mvc.perform(post("/internal/worker/jobs/L1/heartbeat").header("X-Worker-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseSeconds").value(900));
        mvc.perform(post("/internal/worker/jobs/L0/heartbeat").header("X-Worker-Token", TOKEN))
                .andExpect(status().isConflict());
    }

    @Test
    void aRasterIsSentGzippedWhenTheWorkerAcceptsItAndRawOtherwise() throws Exception {
        byte[] raw = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] gz = gzip(raw);
        when(jobs.raster("R1")).thenReturn(Optional.of(new WorkerJobs.RasterDownload() {
            @Override
            public long byteLength() {
                return raw.length;
            }

            @Override
            public InputStream openGzip() {
                return new ByteArrayInputStream(gz);
            }
        }));
        when(jobs.raster("R0")).thenReturn(Optional.empty());

        MvcResult zipped = mvc.perform(get("/internal/worker/rasters/R1")
                        .header("X-Worker-Token", TOKEN)
                        .header("Accept-Encoding", "gzip, deflate"))
                .andExpect(request().asyncStarted())
                .andReturn();
        byte[] body = mvc.perform(asyncDispatch(zipped))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Encoding", "gzip"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(new GZIPInputStream(new ByteArrayInputStream(body)).readAllBytes())
                .isEqualTo(raw);

        MvcResult plain = mvc.perform(get("/internal/worker/rasters/R1").header("X-Worker-Token", TOKEN))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(plain))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", 8))
                .andExpect(header().doesNotExist("Content-Encoding"))
                .andExpect(content().bytes(raw));
        mvc.perform(get("/internal/worker/rasters/R0").header("X-Worker-Token", TOKEN))
                .andExpect(status().isNotFound());
        assertThat(WorkerApiController.acceptsGzip("gzip;q=0")).isFalse();
        assertThat(WorkerApiController.acceptsGzip("br, GZIP;q=0.5")).isTrue();
    }

    @Test
    void anUploadArrivesDecompressedAndATooLargeOneIs413() throws Exception {
        byte[] raw = new byte[1000];
        raw[999] = 9;
        byte[][] received = new byte[1][];
        doAnswer(inv -> {
                    received[0] = ((InputStream) inv.getArgument(2)).readAllBytes();
                    return null;
                })
                .when(jobs)
                .uploadRaster(eq("L1"), eq("water"), any());
        doThrow(new RasterTooLargeException(10)).when(jobs).uploadRaster(eq("L1"), eq("radar"), any());

        mvc.perform(put("/internal/worker/jobs/L1/rasters/water")
                        .header("X-Worker-Token", TOKEN)
                        .header("Content-Encoding", "gzip")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(gzip(raw)))
                .andExpect(status().isNoContent());
        assertThat(received[0]).isEqualTo(raw);
        mvc.perform(put("/internal/worker/jobs/L1/rasters/radar")
                        .header("X-Worker-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(raw))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(put("/internal/worker/jobs/L1/rasters/water")
                        .header("X-Worker-Token", TOKEN)
                        .header("Content-Encoding", "br")
                        .content(raw))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aResultIsPassedOnAndARefusedOneIs422() throws Exception {
        doThrow(new InvalidResultException("raster water was not uploaded"))
                .when(jobs)
                .complete(eq("L2"), anyString());

        mvc.perform(post("/internal/worker/jobs/L1/result")
                        .header("X-Worker-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\": {\"passId\": \"p\", \"coverage\": 0.5}}"))
                .andExpect(status().isNoContent());
        verify(jobs).complete("L1", "{\"passId\":\"p\",\"coverage\":0.5}");
        mvc.perform(post("/internal/worker/jobs/L2/result")
                        .header("X-Worker-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\": {}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("raster water was not uploaded"));
        mvc.perform(post("/internal/worker/jobs/L1/result")
                        .header("X-Worker-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\": {}}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/internal/worker/jobs/L1/result")
                        .header("X-Worker-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aFailureNeedsItsReasonAndWhetherToRetry() throws Exception {
        mvc.perform(post("/internal/worker/jobs/L1/failure")
                        .header("X-Worker-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"InvalidJob: no granule reaches the area\", \"retryable\": false}"))
                .andExpect(status().isNoContent());
        verify(jobs).fail("L1", "InvalidJob: no granule reaches the area", false);
        mvc.perform(post("/internal/worker/jobs/L1/failure")
                        .header("X-Worker-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"x\"}"))
                .andExpect(status().isBadRequest());
    }
}
