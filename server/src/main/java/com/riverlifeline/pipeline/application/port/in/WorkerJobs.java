package com.riverlifeline.pipeline.application.port.in;

import com.riverlifeline.pipeline.domain.model.job.LeasedJob;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Set;

/** The internal worker API (api/worker-api.yaml): the only way the worker reaches the backend. */
public interface WorkerJobs {
    /** Leases the oldest pending job of these types and contract versions; empty when there is none. */
    Optional<LeasedJob> next(Set<String> types, Set<Integer> contractVersions);

    /** Extends the lease of a job still running; returns the new lease length in seconds. */
    int heartbeat(String lease);

    /** Stores one raster of the result (the raw, uncompressed body), replacing an earlier upload of the same name. */
    void uploadRaster(String lease, String name, InputStream body) throws IOException;

    /** Stores the result of a job ({@code data} as JSON) and schedules what follows from it. */
    void complete(String lease, String dataJson);

    /** Records that a job could not be done; it is tried again or marked failed. */
    void fail(String lease, String reason, boolean retryable);

    /** The body of a raster a leased job references, gzip-compressed; empty when unknown or not referenced. */
    Optional<RasterDownload> raster(String rasterId);

    /** A raster body ready to send: its raw length and its gzip stream. */
    interface RasterDownload {
        long byteLength();

        InputStream openGzip() throws IOException;
    }
}
