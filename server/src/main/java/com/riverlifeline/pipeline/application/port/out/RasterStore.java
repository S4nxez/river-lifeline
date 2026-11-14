package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;

/** Where raster bodies live. Bodies are written and read as streams, never held whole in memory. */
public interface RasterStore {
    /** Starts writing a body; it only exists once committed (closing without committing discards it). */
    Writer create(RasterId id) throws IOException;

    /** The stored body, gzip-compressed; empty when there is none. */
    Optional<InputStream> openGzip(RasterId id) throws IOException;

    void delete(RasterId id) throws IOException;

    interface Writer extends Closeable {
        /** Where the raw (uncompressed) body is written. */
        OutputStream body();

        StoredBody commit() throws IOException;
    }

    /** A committed body: its raw length and SHA-256, and the bytes it takes on disk. */
    record StoredBody(long byteLength, String sha256, long storedBytes) {}
}
