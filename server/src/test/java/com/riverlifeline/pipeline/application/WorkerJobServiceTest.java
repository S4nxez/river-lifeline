package com.riverlifeline.pipeline.application;

import static com.riverlifeline.pipeline.PipelineTestData.AREA_ID;
import static com.riverlifeline.pipeline.PipelineTestData.assessment;
import static com.riverlifeline.pipeline.PipelineTestData.classes;
import static com.riverlifeline.pipeline.PipelineTestData.radar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riverlifeline.pipeline.PipelineFakes.PassRow;
import com.riverlifeline.pipeline.PipelineTestData;
import com.riverlifeline.pipeline.application.exception.InvalidRequestException;
import com.riverlifeline.pipeline.application.exception.InvalidResultException;
import com.riverlifeline.pipeline.application.exception.NotLeasedException;
import com.riverlifeline.pipeline.application.exception.RasterTooLargeException;
import com.riverlifeline.pipeline.application.port.in.WorkerJobs.RasterDownload;
import com.riverlifeline.pipeline.domain.model.job.JobState;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.job.LeasedJob;
import com.riverlifeline.pipeline.domain.model.pass.NisarPass;
import com.riverlifeline.pipeline.domain.model.raster.RasterRole;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerJobServiceTest {
    private static final Set<String> ALL =
            Set.of("assess_pass", "reevaluate_pass", "detect_change", "classify_communities");

    private final PipelineHarness h = new PipelineHarness();
    private NisarPass pass;

    @BeforeEach
    void stageOnePass() {
        pass = PipelineTestData.pass("2026-10-23", 161);
        h.passes.saveDiscovered(AREA_ID, pass, h.clock.instant());
        h.staging.stageAssessments();
    }

    private LeasedJob lease() {
        return h.worker.next(ALL, Set.of(1)).orElseThrow();
    }

    private static InputStream bytes(long n) {
        return new ByteArrayInputStream(new byte[(int) n]);
    }

    @Test
    void onlyJobsOfTheAskedTypesAndContractVersionsAreHandedOut() {
        assertThat(h.worker.next(Set.of("classify_communities", "made_up"), Set.of(1)))
                .isEmpty();
        assertThat(h.worker.next(ALL, Set.of(2))).isEmpty();

        LeasedJob job = lease();

        assertThat(job.type()).isEqualTo(JobType.ASSESS_PASS);
        assertThat(job.contractVersion()).isEqualTo(1);
        assertThat(job.leaseSeconds()).isEqualTo(900);
        assertThat(job.inputsJson()).isEqualTo("{\"type\":\"assess_pass\"}");
        assertThat(h.worker.next(ALL, Set.of(1))).isEmpty();
    }

    @Test
    void theInputRastersCanBeDownloadedOnlyWhileTheJobIsLeased() throws IOException {
        var inputs = h.jobs.ofType(JobType.ASSESS_PASS).get(0).rasters;
        String id = inputs.get(0).value();
        assertThat(h.worker.raster(id)).isEmpty();

        lease();
        Optional<RasterDownload> download = h.worker.raster(id);

        assertThat(download).isPresent();
        assertThat(download.get().byteLength()).isEqualTo(24);
        try (InputStream in = new GZIPInputStream(download.get().openGzip())) {
            assertThat(in.readAllBytes()).hasSize(24);
        }
        assertThat(h.worker.raster("not-a-raster")).isEmpty();
        assertThat(h.worker.raster("../../etc/passwd")).isEmpty();
    }

    @Test
    void aHeartbeatExtendsTheLeaseAndAnExpiredLeaseIsAnswered409() {
        LeasedJob job = lease();
        h.clock.advance(Duration.ofMinutes(10));
        assertThat(h.worker.heartbeat(job.lease().value())).isEqualTo(900);
        h.clock.advance(Duration.ofMinutes(10));
        assertThat(h.worker.heartbeat(job.lease().value())).isEqualTo(900);

        h.clock.advance(Duration.ofMinutes(16));

        assertThatThrownBy(() -> h.worker.heartbeat(job.lease().value())).isInstanceOf(NotLeasedException.class);
        assertThatThrownBy(() -> h.worker.heartbeat("unknown")).isInstanceOf(NotLeasedException.class);
        assertThatThrownBy(() -> h.worker.heartbeat("not valid!")).isInstanceOf(NotLeasedException.class);
    }

    @Test
    void anExpiredJobIsHandedOutAgainUnderANewLeaseAndTheOldOneIsRefused() {
        LeasedJob first = lease();
        h.clock.advance(Duration.ofMinutes(16));

        LeasedJob second = lease();

        assertThat(second.lease()).isNotEqualTo(first.lease());
        assertThatThrownBy(() -> h.worker.complete(first.lease().value(), "{}")).isInstanceOf(NotLeasedException.class);
    }

    @Test
    void anAcceptedAssessmentIsStoredAndCallsForTheClassification() throws IOException {
        LeasedJob job = lease();
        long waterBytes = classes().byteLength();
        h.worker.uploadRaster(job.lease().value(), "water", bytes(1));
        h.worker.uploadRaster(job.lease().value(), "water", bytes(waterBytes));
        h.worker.uploadRaster(job.lease().value(), "radar", bytes(radar().byteLength()));
        h.worker.uploadRaster(job.lease().value(), "sentinel1", bytes(5));
        h.decoder.results.put(
                "ok",
                assessment(
                        pass.info().id(), Map.of(ResultRasterName.WATER, classes(), ResultRasterName.RADAR, radar())));

        h.worker.complete(job.lease().value(), "ok");

        PassRow row = h.passes.row(AREA_ID, pass.info().id());
        assertThat(row.assessment).isNotNull();
        assertThat(row.digest).isEqualTo("digest-1");
        assertThat(h.rasters
                        .find(row.rasters.get(ResultRasterName.WATER))
                        .orElseThrow()
                        .role())
                .isEqualTo(RasterRole.RESULT);
        assertThat(h.rasters.rasters.values()).noneMatch(r -> r.role() == RasterRole.PENDING);
        assertThat(h.store.bodies).hasSize(h.rasters.rasters.size());
        assertThat(h.jobs.ofType(JobType.ASSESS_PASS).get(0).job.state()).isEqualTo(JobState.DONE);
        assertThat(h.jobs.ofType(JobType.CLASSIFY_COMMUNITIES)).hasSize(1);
        assertThat(h.transactions.count).isGreaterThanOrEqualTo(2);
        assertThatThrownBy(() -> h.worker.complete(job.lease().value(), "ok")).isInstanceOf(NotLeasedException.class);
    }

    @Test
    void aResultThatDoesNotFitIsRefusedAndTheJobStaysLeased() throws IOException {
        LeasedJob job = lease();
        h.decoder.results.put("noUpload", assessment(pass.info().id(), Map.of(ResultRasterName.WATER, classes())));
        h.decoder.results.put(
                "wrongPass",
                assessment(
                        PipelineTestData.pass("2026-10-11", 161).info().id(),
                        Map.of(ResultRasterName.WATER, classes())));

        assertThatThrownBy(() -> h.worker.complete(job.lease().value(), "noUpload"))
                .isInstanceOf(InvalidResultException.class)
                .hasMessageContaining("raster water was not uploaded");
        h.worker.uploadRaster(job.lease().value(), "water", bytes(10));
        assertThatThrownBy(() -> h.worker.complete(job.lease().value(), "noUpload"))
                .isInstanceOf(InvalidResultException.class)
                .hasMessageContaining("has 10 bytes");
        assertThatThrownBy(() -> h.worker.complete(job.lease().value(), "wrongPass"))
                .isInstanceOf(InvalidResultException.class)
                .hasMessageContaining("not the job's pass");
        assertThatThrownBy(() -> h.worker.complete(job.lease().value(), "garbage"))
                .isInstanceOf(InvalidResultException.class);
        assertThat(h.jobs.ofType(JobType.ASSESS_PASS).get(0).job.state()).isEqualTo(JobState.LEASED);
    }

    @Test
    void uploadsAreLimitedToTheAreaGridAndToKnownNames() {
        LeasedJob job = lease();
        long limit = PipelineTestData.GRID.maxRasterBytes();

        assertThatThrownBy(() -> h.worker.uploadRaster(job.lease().value(), "water", bytes(limit + 1)))
                .isInstanceOf(RasterTooLargeException.class);
        assertThatThrownBy(() -> h.worker.uploadRaster(job.lease().value(), "backscatter", bytes(1)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> h.worker.uploadRaster("gone", "water", bytes(1)))
                .isInstanceOf(NotLeasedException.class);
        assertThat(h.rasters.rasters.values()).noneMatch(r -> r.role() == RasterRole.PENDING);
    }

    @Test
    void aRetryableFailureRequeuesUntilTheLastAttemptThenThePassWaits() throws IOException {
        LeasedJob first = lease();
        h.worker.uploadRaster(first.lease().value(), "water", bytes(3));
        h.worker.fail(first.lease().value(), "MemoryError: out of memory", true);

        assertThat(h.jobs.ofType(JobType.ASSESS_PASS).get(0).job.state()).isEqualTo(JobState.PENDING);
        assertThat(h.rasters.rasters.values()).noneMatch(r -> r.role() == RasterRole.PENDING);

        LeasedJob second = lease();
        h.worker.fail(second.lease().value(), "MemoryError: out of memory", true);

        assertThat(h.jobs.ofType(JobType.ASSESS_PASS).get(0).job.state()).isEqualTo(JobState.FAILED);
        PassRow row = h.passes.row(AREA_ID, pass.info().id());
        assertThat(row.givenUp).isFalse();
        assertThat(row.stagingAfter).isEqualTo(h.clock.instant().plus(Duration.ofHours(24)));
    }

    @Test
    void aFailureThatWouldRepeatGivesThePassUp() {
        LeasedJob job = lease();

        h.worker.fail(job.lease().value(), "InvalidJob: no granule reaches the area", false);

        assertThat(h.jobs.ofType(JobType.ASSESS_PASS).get(0).job.state()).isEqualTo(JobState.FAILED);
        assertThat(h.jobs.ofType(JobType.ASSESS_PASS).get(0).lastError)
                .isEqualTo("InvalidJob: no granule reaches the area");
        assertThat(h.passes.row(AREA_ID, pass.info().id()).givenUp).isTrue();
    }
}
